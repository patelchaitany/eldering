package handlers

import (
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

type heartbeatRequest struct {
	Lat        *float64   `json:"lat"`
	Lng        *float64   `json:"lng"`
	AccuracyM  *float64   `json:"accuracy_m"`
	SpeedMps   *float64   `json:"speed_mps"`
	BearingDeg *float64   `json:"bearing_deg"`
	Activity   *string    `json:"activity"`
	BatteryPct *float64   `json:"battery_pct"`
	RecordedAt *time.Time `json:"recorded_at"`
}

type heartbeatResponse struct {
	PendingAlerts []incidentSummary `json:"pending_alerts"`
	ServerTime    time.Time         `json:"server_time"`
}

type heartbeatRow struct {
	lat, lng                 float64
	accuracy, speed, bearing *float32
	activity                 *string
	battery                  *int16
	recordedAt               time.Time
}

func (req heartbeatRequest) validate(now time.Time) (heartbeatRow, error) {
	var h heartbeatRow
	if req.Lat == nil || req.Lng == nil || !validLatLng(*req.Lat, *req.Lng) {
		return h, badRequest("invalid_location", "lat and lng are required and must be valid WGS84 degrees")
	}
	h.lat, h.lng = *req.Lat, *req.Lng
	if req.RecordedAt == nil {
		return h, badRequest("invalid_recorded_at", "recorded_at is required")
	}
	if err := checkTimestamp(*req.RecordedAt, now, "recorded_at"); err != nil {
		return h, err
	}
	h.recordedAt = req.RecordedAt.UTC()
	var err error
	if h.accuracy, err = optNonNeg(req.AccuracyM, 100_000, "accuracy_m"); err != nil {
		return h, err
	}
	if h.speed, err = optNonNeg(req.SpeedMps, 200, "speed_mps"); err != nil {
		return h, err
	}
	if req.BearingDeg != nil {
		b := *req.BearingDeg
		if b == 360 {
			b = 0
		}
		if h.bearing, err = optNonNeg(&b, 359.999999, "bearing_deg"); err != nil {
			return h, err
		}
	}
	if h.activity, err = normalizeActivity(req.Activity); err != nil {
		return h, err
	}
	if req.BatteryPct != nil {
		p := *req.BatteryPct
		if p < 0 || p > 100 {
			return h, badRequest("invalid_battery_pct", "battery_pct must be 0-100")
		}
		v := int16(p + 0.5)
		h.battery = &v
	}
	return h, nil
}

// heartbeat stores the caller's latest point (overwriting; no history) and
// returns alerts the caller can see and has not acknowledged.
func (s *Server) heartbeat(w http.ResponseWriter, r *http.Request) error {
	var req heartbeatRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	h, err := req.validate(time.Now())
	if err != nil {
		return err
	}
	resp := heartbeatResponse{PendingAlerts: []incidentSummary{}}
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		st, err := loadSettings(ctx, tx, uid)
		if err != nil {
			return err
		}
		if st.SharingState == rbac.SharingOff {
			// Sharing off: nobody may see it, so the server does not keep it.
			if _, err := tx.Exec(ctx, `DELETE FROM locations_latest WHERE user_id = $1`, uid); err != nil {
				return err
			}
		} else {
			// Out-of-order delivery never replaces a newer point.
			if _, err := tx.Exec(ctx, `
				INSERT INTO locations_latest (user_id, geom, accuracy_m, speed_mps, bearing_deg, activity, battery_pct, recorded_at)
				VALUES ($1, ST_SetSRID(ST_MakePoint($3, $2), 4326)::geography, $4, $5, $6, $7, $8, $9)
				ON CONFLICT (user_id) DO UPDATE SET
				  geom = EXCLUDED.geom, accuracy_m = EXCLUDED.accuracy_m, speed_mps = EXCLUDED.speed_mps,
				  bearing_deg = EXCLUDED.bearing_deg, activity = EXCLUDED.activity, battery_pct = EXCLUDED.battery_pct,
				  recorded_at = EXCLUDED.recorded_at, updated_at = now()
				WHERE locations_latest.recorded_at <= EXCLUDED.recorded_at`,
				uid, h.lat, h.lng, h.accuracy, h.speed, h.bearing, h.activity, h.battery, h.recordedAt); err != nil {
				return err
			}
		}
		rows, err := tx.Query(ctx, incidentSummarySelect+`
			WHERE i.user_id <> $1 AND i.status = 'active' AND i.share_expires_at > now()
			  AND NOT EXISTS (SELECT 1 FROM incident_acks a WHERE a.incident_id = i.id AND a.user_id = $1)
			ORDER BY i.detected_at DESC LIMIT 50`, uid)
		if err != nil {
			return err
		}
		if resp.PendingAlerts, err = collectSummaries(rows); err != nil {
			return err
		}
		return tx.QueryRow(ctx, `SELECT now()`).Scan(&resp.ServerTime)
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, resp)
	return nil
}

type memberLocationJSON struct {
	UserID      uuid.UUID  `json:"user_id"`
	DisplayName string     `json:"display_name"`
	Status      string     `json:"status"` // live | paused | hidden
	Lat         *float64   `json:"lat,omitempty"`
	Lng         *float64   `json:"lng,omitempty"`
	AccuracyM   *float32   `json:"accuracy_m,omitempty"`
	SpeedMps    *float32   `json:"speed_mps,omitempty"`
	BearingDeg  *float32   `json:"bearing_deg,omitempty"`
	Activity    *string    `json:"activity,omitempty"`
	BatteryPct  *int16     `json:"battery_pct,omitempty"`
	RecordedAt  *time.Time `json:"recorded_at,omitempty"`
}

// familyLocations lists every co-member (across all my families). Coordinates
// come from an RLS-filtered join, so they appear only when visible.
func (s *Server) familyLocations(w http.ResponseWriter, r *http.Request) error {
	ctx, uid := r.Context(), currentUser(r)
	var out []memberLocationJSON
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `
			WITH co AS (
			  SELECT DISTINCT o.user_id FROM family_members me
			  JOIN family_members o ON o.family_id = me.family_id AND o.user_id <> me.user_id
			  WHERE me.user_id = $1)
			SELECT u.id, u.display_name, rls.location_status(u.id),
			       ST_Y(l.geom::geometry), ST_X(l.geom::geometry), l.accuracy_m, l.speed_mps, l.bearing_deg,
			       l.activity, l.battery_pct, l.recorded_at
			FROM co JOIN users u ON u.id = co.user_id
			LEFT JOIN locations_latest l ON l.user_id = co.user_id
			ORDER BY u.display_name, u.id`, uid)
		if err != nil {
			return err
		}
		out, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (memberLocationJSON, error) {
			var m memberLocationJSON
			err := row.Scan(&m.UserID, &m.DisplayName, &m.Status, &m.Lat, &m.Lng, &m.AccuracyM, &m.SpeedMps,
				&m.BearingDeg, &m.Activity, &m.BatteryPct, &m.RecordedAt)
			if m.Status != "live" { // belt and braces: RLS already hides the row
				m = memberLocationJSON{UserID: m.UserID, DisplayName: m.DisplayName, Status: m.Status}
			}
			return m, err
		})
		return err
	})
	if err != nil {
		return err
	}
	if out == nil {
		out = []memberLocationJSON{}
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}
