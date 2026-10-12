package handlers

import (
	"context"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

type settingsJSON struct {
	SharingState          rbac.SharingState `json:"sharing_state"`
	PausedUntil           *time.Time        `json:"paused_until"`
	AlwaysAlertOnAccident bool              `json:"always_alert_on_accident"`
	// EffectiveSharingState folds an elapsed pause back to "on".
	EffectiveSharingState rbac.SharingState `json:"effective_sharing_state"`
}

type familyJSON struct {
	ID          uuid.UUID `json:"id"`
	Name        string    `json:"name"`
	Role        rbac.Role `json:"role"`
	JoinedAt    time.Time `json:"joined_at"`
	MemberCount int       `json:"member_count"`
	CreatedAt   time.Time `json:"created_at"`
}

type meResponse struct {
	User     userJSON     `json:"user"`
	Settings settingsJSON `json:"settings"`
	Families []familyJSON `json:"families"`
}

func (st *settingsJSON) computeEffective(now time.Time) {
	st.EffectiveSharingState = st.SharingState
	if st.SharingState == rbac.SharingPaused && st.PausedUntil != nil && !st.PausedUntil.After(now) {
		st.EffectiveSharingState = rbac.SharingOn
	}
}

// loadSettings reads (creating with defaults if somehow missing) the caller's settings.
func loadSettings(ctx context.Context, tx pgx.Tx, uid uuid.UUID) (settingsJSON, error) {
	var st settingsJSON
	if _, err := tx.Exec(ctx, `INSERT INTO user_settings (user_id) VALUES ($1) ON CONFLICT (user_id) DO NOTHING`, uid); err != nil {
		return st, err
	}
	var now time.Time
	err := tx.QueryRow(ctx, `SELECT sharing_state, paused_until, always_alert_on_accident, now()
		FROM user_settings WHERE user_id = $1`, uid).Scan(&st.SharingState, &st.PausedUntil, &st.AlwaysAlertOnAccident, &now)
	if err != nil {
		return st, err
	}
	st.computeEffective(now)
	return st, nil
}

const familySelect = `SELECT f.id, f.name, m.role, m.joined_at,
	(SELECT count(*) FROM family_members x WHERE x.family_id = f.id), f.created_at
	FROM family_members m JOIN families f ON f.id = m.family_id`

func scanFamily(row pgx.Row) (familyJSON, error) {
	var f familyJSON
	err := row.Scan(&f.ID, &f.Name, &f.Role, &f.JoinedAt, &f.MemberCount, &f.CreatedAt)
	return f, err
}

func loadFamily(ctx context.Context, tx pgx.Tx, familyID, uid uuid.UUID) (familyJSON, error) {
	return scanFamily(tx.QueryRow(ctx, familySelect+` WHERE m.family_id = $1 AND m.user_id = $2`, familyID, uid))
}

func (s *Server) getMe(w http.ResponseWriter, r *http.Request) error {
	ctx, uid := r.Context(), currentUser(r)
	resp := meResponse{Families: []familyJSON{}}
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT id, email, display_name, created_at FROM users WHERE id = $1`, uid).
			Scan(&resp.User.ID, &resp.User.Email, &resp.User.DisplayName, &resp.User.CreatedAt); err != nil {
			if db.IsNoRows(err) {
				return unauthorized("unauthorized", "account no longer exists")
			}
			return err
		}
		var err error
		if resp.Settings, err = loadSettings(ctx, tx, uid); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, familySelect+` WHERE m.user_id = $1 ORDER BY m.joined_at`, uid)
		if err != nil {
			return err
		}
		resp.Families, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (familyJSON, error) { return scanFamily(row) })
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, resp)
	return nil
}

type settingsPatch struct {
	SharingState          Optional[string]    `json:"sharing_state"`
	PausedUntil           Optional[time.Time] `json:"paused_until"`
	AlwaysAlertOnAccident Optional[bool]      `json:"always_alert_on_accident"`
}

// maxPause bounds a timed pause; an indefinite pause is paused_until = null.
const maxPause = 30 * 24 * time.Hour

func (s *Server) patchSettings(w http.ResponseWriter, r *http.Request) error {
	var p settingsPatch
	if err := decodeJSON(r, &p); err != nil {
		return err
	}
	if p.SharingState.Set && p.SharingState.Null || p.AlwaysAlertOnAccident.Set && p.AlwaysAlertOnAccident.Null {
		return badRequest("invalid_settings", "sharing_state and always_alert_on_accident cannot be null")
	}
	ctx, uid := r.Context(), currentUser(r)
	var out settingsJSON
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		cur, err := loadSettings(ctx, tx, uid)
		if err != nil {
			return err
		}
		var now time.Time
		if err := tx.QueryRow(ctx, `SELECT now()`).Scan(&now); err != nil {
			return err
		}
		next := cur
		if p.SharingState.Set {
			st, ok := rbac.ParseSharingState(p.SharingState.Value)
			if !ok {
				return badRequest("invalid_settings", "sharing_state must be on, paused or off")
			}
			next.SharingState = st
			if st != rbac.SharingPaused {
				next.PausedUntil = nil
			} else if cur.SharingState != rbac.SharingPaused {
				next.PausedUntil = nil // a fresh pause is indefinite unless paused_until is given
			}
		}
		if p.PausedUntil.Set {
			if next.SharingState != rbac.SharingPaused {
				if !p.PausedUntil.Null {
					return badRequest("invalid_settings", "paused_until requires sharing_state paused")
				}
			} else if p.PausedUntil.Null {
				next.PausedUntil = nil
			} else {
				until := p.PausedUntil.Value.UTC()
				if !until.After(now) || until.Sub(now) > maxPause {
					return badRequest("invalid_settings", "paused_until must be in the future and within 30 days")
				}
				next.PausedUntil = &until
			}
		}
		if p.AlwaysAlertOnAccident.Set {
			next.AlwaysAlertOnAccident = p.AlwaysAlertOnAccident.Value
		}
		if _, err := tx.Exec(ctx, `UPDATE user_settings
			SET sharing_state = $2, paused_until = $3, always_alert_on_accident = $4 WHERE user_id = $1`,
			uid, next.SharingState, next.PausedUntil, next.AlwaysAlertOnAccident); err != nil {
			return err
		}
		if next.SharingState == rbac.SharingOff {
			// Data minimisation: nobody may see it, so do not keep it.
			if _, err := tx.Exec(ctx, `DELETE FROM locations_latest WHERE user_id = $1`, uid); err != nil {
				return err
			}
		}
		next.computeEffective(now)
		out = next
		return nil
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}
