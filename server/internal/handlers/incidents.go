package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"regexp"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

const (
	maxTrailPoints  = 5000
	maxDetailsBytes = 16 << 10
)

var (
	incidentKinds     = map[string]bool{"vehicle_crash": true, "pedestrian_impact": true, "fall": true, "sos": true}
	idempotencyKeyRE  = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,64}$`)
	errIncidentClosed = conflict("incident_closed", "incident is no longer active")
)

type pointJSON struct {
	Lat        float64   `json:"lat"`
	Lng        float64   `json:"lng"`
	RecordedAt time.Time `json:"recorded_at"`
	AccuracyM  *float32  `json:"accuracy_m"`
}

type incidentSummary struct {
	ID             uuid.UUID  `json:"id"`
	UserID         uuid.UUID  `json:"user_id"`
	DisplayName    string     `json:"display_name"`
	Kind           string     `json:"kind"`
	Status         string     `json:"status"`
	DetectedAt     time.Time  `json:"detected_at"`
	Lat            *float64   `json:"lat"`
	Lng            *float64   `json:"lng"`
	LastLocation   *pointJSON `json:"last_location"` // newest trail point (live updates)
	ShareExpiresAt time.Time  `json:"share_expires_at"`
	ResolvedAt     *time.Time `json:"resolved_at"`
	Resolution     *string    `json:"resolution"`
	IsMine         bool       `json:"is_mine"`
	MyAck          *string    `json:"my_ack"`
}

// incidentSummarySelect reads only RLS-visible incidents; the trail lateral
// join is filtered by the same policy through tr_read.
const incidentSummarySelect = `
	SELECT i.id, i.user_id, u.display_name, i.kind, i.status, i.detected_at,
	       ST_Y(i.geom::geometry), ST_X(i.geom::geometry),
	       lt.lat, lt.lng, lt.recorded_at, lt.accuracy_m,
	       i.share_expires_at, i.resolved_at, i.resolution,
	       i.user_id = rls.uid(),
	       (SELECT a.state FROM incident_acks a WHERE a.incident_id = i.id AND a.user_id = rls.uid())
	FROM incidents i
	JOIN users u ON u.id = i.user_id
	LEFT JOIN LATERAL (
	  SELECT ST_Y(t.geom::geometry) AS lat, ST_X(t.geom::geometry) AS lng, t.recorded_at, t.accuracy_m
	  FROM incident_trail t WHERE t.incident_id = i.id
	  ORDER BY t.recorded_at DESC LIMIT 1) lt ON true`

func scanSummary(row pgx.Row) (incidentSummary, error) {
	var s incidentSummary
	var lat, lng *float64
	var at *time.Time
	var acc *float32
	err := row.Scan(&s.ID, &s.UserID, &s.DisplayName, &s.Kind, &s.Status, &s.DetectedAt, &s.Lat, &s.Lng,
		&lat, &lng, &at, &acc, &s.ShareExpiresAt, &s.ResolvedAt, &s.Resolution, &s.IsMine, &s.MyAck)
	if err == nil && lat != nil && lng != nil && at != nil {
		s.LastLocation = &pointJSON{Lat: *lat, Lng: *lng, RecordedAt: *at, AccuracyM: acc}
	}
	return s, err
}

func collectSummaries(rows pgx.Rows) ([]incidentSummary, error) {
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (incidentSummary, error) { return scanSummary(row) })
	if out == nil {
		out = []incidentSummary{}
	}
	return out, err
}

type trailPointRequest struct {
	Lat        *float64   `json:"lat"`
	Lng        *float64   `json:"lng"`
	RecordedAt *time.Time `json:"recorded_at"`
	AccuracyM  *float64   `json:"accuracy_m"`
	Landmark   *string    `json:"landmark"`
}

// trailColumns is the column-oriented form inserted with unnest().
type trailColumns struct {
	at       []time.Time
	lat, lng []float64
	acc      []*float32
	landmark []*string
}

func parseTrail(points []trailPointRequest, now time.Time) (trailColumns, error) {
	var c trailColumns
	if len(points) > maxTrailPoints {
		return c, badRequest("invalid_trail", "too many trail points (max 5000)")
	}
	for _, p := range points {
		if p.Lat == nil || p.Lng == nil || !validLatLng(*p.Lat, *p.Lng) {
			return c, badRequest("invalid_trail", "every trail point needs a valid lat and lng")
		}
		if p.RecordedAt == nil {
			return c, badRequest("invalid_trail", "every trail point needs recorded_at")
		}
		if err := checkTimestamp(*p.RecordedAt, now, "recorded_at"); err != nil {
			return c, badRequest("invalid_trail", "trail point recorded_at is out of range")
		}
		acc, err := optNonNeg(p.AccuracyM, 100_000, "accuracy_m")
		if err != nil {
			return c, err
		}
		lm, err := cleanLandmark(p.Landmark)
		if err != nil {
			return c, err
		}
		c.at = append(c.at, p.RecordedAt.UTC())
		c.lat = append(c.lat, *p.Lat)
		c.lng = append(c.lng, *p.Lng)
		c.acc = append(c.acc, acc)
		c.landmark = append(c.landmark, lm)
	}
	return c, nil
}

// insertTrail appends points; duplicates (same recorded_at) are ignored so
// client retries are harmless. Returns the number of new points.
func insertTrail(ctx context.Context, tx pgx.Tx, incidentID uuid.UUID, c trailColumns) (int64, error) {
	if len(c.at) == 0 {
		return 0, nil
	}
	tag, err := tx.Exec(ctx, `
		INSERT INTO incident_trail (incident_id, recorded_at, geom, accuracy_m, landmark)
		SELECT $1, p.t, ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)::geography, p.acc, p.lm
		FROM unnest($2::timestamptz[], $3::float8[], $4::float8[], $5::float4[], $6::text[]) AS p(t, lat, lng, acc, lm)
		ON CONFLICT (incident_id, recorded_at) DO NOTHING`,
		incidentID, c.at, c.lat, c.lng, c.acc, c.landmark)
	return tag.RowsAffected(), err
}

type createIncidentRequest struct {
	Kind       string              `json:"kind"`
	DetectedAt *time.Time          `json:"detected_at"`
	Lat        *float64            `json:"lat"`
	Lng        *float64            `json:"lng"`
	Details    json.RawMessage     `json:"details"`
	Trail      []trailPointRequest `json:"trail"`
}

type createIncidentResponse struct {
	ID             uuid.UUID `json:"id"`
	Status         string    `json:"status"`
	ShareURL       string    `json:"share_url"`
	ShareExpiresAt time.Time `json:"share_expires_at"`
}

func normalizeDetails(raw json.RawMessage) ([]byte, error) {
	b := bytes.TrimSpace(raw)
	if len(b) == 0 || bytes.Equal(b, []byte("null")) {
		return []byte("{}"), nil
	}
	if len(b) > maxDetailsBytes {
		return nil, badRequest("invalid_details", "details must be at most 16 KiB")
	}
	if b[0] != '{' || !json.Valid(b) {
		return nil, badRequest("invalid_details", "details must be a JSON object")
	}
	return b, nil
}

// createIncident records an accident/SOS with its pre-event trail and returns a
// public share link. An optional Idempotency-Key header makes retries return
// the same incident (with a freshly rotated link, since only hashes are stored).
func (s *Server) createIncident(w http.ResponseWriter, r *http.Request) error {
	var req createIncidentRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	now := time.Now()
	if !incidentKinds[req.Kind] {
		return badRequest("invalid_kind", "kind must be vehicle_crash, pedestrian_impact, fall or sos")
	}
	if req.DetectedAt == nil {
		return badRequest("invalid_detected_at", "detected_at is required")
	}
	if err := checkTimestamp(*req.DetectedAt, now, "detected_at"); err != nil {
		return err
	}
	if (req.Lat == nil) != (req.Lng == nil) || (req.Lat != nil && !validLatLng(*req.Lat, *req.Lng)) {
		return badRequest("invalid_location", "lat and lng must both be valid or both be omitted")
	}
	details, err := normalizeDetails(req.Details)
	if err != nil {
		return err
	}
	trail, err := parseTrail(req.Trail, now)
	if err != nil {
		return err
	}
	var key *string
	if k := r.Header.Get("Idempotency-Key"); k != "" {
		if !idempotencyKeyRE.MatchString(k) {
			return badRequest("invalid_idempotency_key", "Idempotency-Key must be 1-64 characters [A-Za-z0-9._:-]")
		}
		key = &k
	}
	token, tokenHash, err := auth.NewOpaqueToken()
	if err != nil {
		return err
	}

	ctx, uid := r.Context(), currentUser(r)
	resp := createIncidentResponse{ShareURL: s.cfg.PublicBaseURL + "/s/" + token}
	created := true
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		err := tx.QueryRow(ctx, `
			INSERT INTO incidents (user_id, kind, detected_at, geom, details, share_token_hash, share_expires_at, client_key)
			VALUES ($1, $2, $3,
			        CASE WHEN $4::float8 IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint($5, $4), 4326)::geography END,
			        $6, $7, now() + make_interval(secs => $8), $9)
			ON CONFLICT (user_id, client_key) DO NOTHING
			RETURNING id, status, share_expires_at`,
			uid, req.Kind, req.DetectedAt.UTC(), req.Lat, req.Lng, details, tokenHash, s.cfg.ShareTTL.Seconds(), key,
		).Scan(&resp.ID, &resp.Status, &resp.ShareExpiresAt)
		if db.IsNoRows(err) && key != nil {
			// Retry of an incident we already have: rotate its link and return it.
			created = false
			err = tx.QueryRow(ctx, `UPDATE incidents SET share_token_hash = $3
				WHERE user_id = $1 AND client_key = $2
				RETURNING id, status, share_expires_at`, uid, *key, tokenHash).Scan(&resp.ID, &resp.Status, &resp.ShareExpiresAt)
		}
		if err != nil {
			return err
		}
		_, err = insertTrail(ctx, tx, resp.ID, trail)
		return err
	})
	if err != nil {
		return err
	}
	status := http.StatusCreated
	if !created {
		status = http.StatusOK
	}
	writeJSON(w, status, resp)
	return nil
}

// loadIncidentForAction returns owner and status of an RLS-visible incident.
func loadIncidentForAction(ctx context.Context, tx pgx.Tx, id uuid.UUID, lock bool) (owner uuid.UUID, status string, err error) {
	q := `SELECT user_id, status FROM incidents WHERE id = $1`
	if lock {
		q += ` FOR UPDATE`
	}
	err = tx.QueryRow(ctx, q, id).Scan(&owner, &status)
	if db.IsNoRows(err) {
		err = notFound()
	}
	return owner, status, err
}

type trailRequest struct {
	Points []trailPointRequest `json:"points"`
}

func (s *Server) appendTrail(w http.ResponseWriter, r *http.Request) error {
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	var req trailRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	trail, err := parseTrail(req.Points, time.Now())
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var accepted int64
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		owner, status, err := loadIncidentForAction(ctx, tx, id, false)
		if err != nil {
			return err
		}
		if owner != uid {
			return forbidden("only the incident owner can add trail points")
		}
		if status != "active" {
			return errIncidentClosed
		}
		accepted, err = insertTrail(ctx, tx, id, trail)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]int64{"accepted": accepted})
	return nil
}

type resolveRequest struct {
	Resolution string `json:"resolution"`
}

func (s *Server) resolveIncident(w http.ResponseWriter, r *http.Request) error {
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	var req resolveRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	res, ok := rbac.ParseResolution(req.Resolution)
	if !ok {
		return badRequest("invalid_resolution", "resolution must be user_ok, false_alarm or handled")
	}
	ctx, uid := r.Context(), currentUser(r)
	var out incidentSummary
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		owner, status, err := loadIncidentForAction(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if !rbac.CanResolve(owner == uid, res) {
			return forbidden("viewers can only resolve an incident as handled")
		}
		if status != "active" {
			return errIncidentClosed
		}
		if _, err := tx.Exec(ctx, `UPDATE incidents
			SET status = $2, resolution = $3, resolved_at = now(), resolved_by = $4
			WHERE id = $1 AND status = 'active'`, id, rbac.StatusFor(res), string(res), uid); err != nil {
			return err
		}
		out, err = scanSummary(tx.QueryRow(ctx, incidentSummarySelect+` WHERE i.id = $1`, id))
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

type ackRequest struct {
	State string `json:"state"`
}

type ackJSON struct {
	IncidentID  uuid.UUID `json:"incident_id"`
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	State       string    `json:"state"`
	At          time.Time `json:"at"`
}

func (s *Server) ackIncident(w http.ResponseWriter, r *http.Request) error {
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	var req ackRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	if req.State != "seen" && req.State != "on_my_way" {
		return badRequest("invalid_state", "state must be seen or on_my_way")
	}
	ctx, uid := r.Context(), currentUser(r)
	out := ackJSON{IncidentID: id, UserID: uid}
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		owner, status, err := loadIncidentForAction(ctx, tx, id, false)
		if err != nil {
			return err
		}
		if owner == uid {
			return badRequest("cannot_ack_own", "you cannot acknowledge your own incident")
		}
		if status != "active" {
			return errIncidentClosed
		}
		// 'seen' never downgrades an existing 'on_my_way'.
		if err := tx.QueryRow(ctx, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1, $2, $3)
			ON CONFLICT (incident_id, user_id) DO UPDATE
			SET state = CASE WHEN incident_acks.state = 'on_my_way' THEN 'on_my_way' ELSE EXCLUDED.state END, at = now()
			RETURNING state, at`, id, uid, req.State).Scan(&out.State, &out.At); err != nil {
			return err
		}
		return tx.QueryRow(ctx, `SELECT display_name FROM users WHERE id = $1`, uid).Scan(&out.DisplayName)
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

func (s *Server) activeIncidents(w http.ResponseWriter, r *http.Request) error {
	ctx, uid := r.Context(), currentUser(r)
	var out []incidentSummary
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, incidentSummarySelect+`
			WHERE i.status = 'active' AND i.share_expires_at > now()
			ORDER BY i.detected_at DESC LIMIT 100`)
		if err != nil {
			return err
		}
		out, err = collectSummaries(rows)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

type trailPointJSON struct {
	Lat        float64   `json:"lat"`
	Lng        float64   `json:"lng"`
	RecordedAt time.Time `json:"recorded_at"`
	AccuracyM  *float32  `json:"accuracy_m"`
	Landmark   *string   `json:"landmark"`
}

type incidentDetail struct {
	incidentSummary
	Details    json.RawMessage  `json:"details"`
	ResolvedBy *uuid.UUID       `json:"resolved_by"`
	Trail      []trailPointJSON `json:"trail"`
	Acks       []ackJSON        `json:"acks"`
}

func (s *Server) getIncident(w http.ResponseWriter, r *http.Request) error {
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var out incidentDetail
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		var err error
		if out.incidentSummary, err = scanSummary(tx.QueryRow(ctx, incidentSummarySelect+` WHERE i.id = $1`, id)); err != nil {
			return err // no row => 404 (invisible and nonexistent look the same)
		}
		if err := tx.QueryRow(ctx, `SELECT details, resolved_by FROM incidents WHERE id = $1`, id).
			Scan(&out.Details, &out.ResolvedBy); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT ST_Y(geom::geometry), ST_X(geom::geometry), recorded_at, accuracy_m, landmark
			FROM incident_trail WHERE incident_id = $1 ORDER BY recorded_at`, id)
		if err != nil {
			return err
		}
		if out.Trail, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (trailPointJSON, error) {
			var p trailPointJSON
			err := row.Scan(&p.Lat, &p.Lng, &p.RecordedAt, &p.AccuracyM, &p.Landmark)
			return p, err
		}); err != nil {
			return err
		}
		// RLS: the owner sees every ack, a viewer only their own.
		rows, err = tx.Query(ctx, `SELECT a.incident_id, a.user_id, u.display_name, a.state, a.at
			FROM incident_acks a JOIN users u ON u.id = a.user_id
			WHERE a.incident_id = $1 ORDER BY a.at`, id)
		if err != nil {
			return err
		}
		out.Acks, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (ackJSON, error) {
			var a ackJSON
			err := row.Scan(&a.IncidentID, &a.UserID, &a.DisplayName, &a.State, &a.At)
			return a, err
		})
		return err
	})
	if err != nil {
		return err
	}
	if out.Trail == nil {
		out.Trail = []trailPointJSON{}
	}
	if out.Acks == nil {
		out.Acks = []ackJSON{}
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}
