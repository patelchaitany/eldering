package handlers

import (
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

type grantJSON struct {
	ViewerID    uuid.UUID  `json:"viewer_id"`
	DisplayName string     `json:"display_name"`
	Level       rbac.Level `json:"level"`
	// IsDefault is true when no explicit grant exists (SPEC default: emergency).
	IsDefault bool       `json:"is_default"`
	UpdatedAt *time.Time `json:"updated_at"`
}

const grantSelect = `
	SELECT u.id, u.display_name, coalesce(g.level, $2), g.sharer_id IS NULL, g.updated_at
	FROM users u
	LEFT JOIN share_grants g ON g.sharer_id = $1 AND g.viewer_id = u.id`

func scanGrant(row pgx.Row) (grantJSON, error) {
	var g grantJSON
	err := row.Scan(&g.ViewerID, &g.DisplayName, &g.Level, &g.IsDefault, &g.UpdatedAt)
	return g, err
}

// listGrants returns my outgoing grants towards every current co-member,
// including the implicit default ones.
func (s *Server) listGrants(w http.ResponseWriter, r *http.Request) error {
	ctx, uid := r.Context(), currentUser(r)
	var out []grantJSON
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, grantSelect+`
			WHERE u.id IN (SELECT o.user_id FROM family_members me
			               JOIN family_members o ON o.family_id = me.family_id AND o.user_id <> me.user_id
			               WHERE me.user_id = $1)
			ORDER BY u.display_name, u.id`, uid, string(rbac.DefaultLevel))
		if err != nil {
			return err
		}
		out, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (grantJSON, error) { return scanGrant(row) })
		return err
	})
	if err != nil {
		return err
	}
	if out == nil {
		out = []grantJSON{}
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

type grantRequest struct {
	Level string `json:"level"`
}

// putGrant sets what the caller (always the sharer) shares with viewerId.
func (s *Server) putGrant(w http.ResponseWriter, r *http.Request) error {
	viewer, err := pathUUID(r, "viewerId")
	if err != nil {
		return err
	}
	var req grantRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	level, ok := rbac.ParseLevel(req.Level)
	if !ok {
		return badRequest("invalid_level", "level must be live, emergency or off")
	}
	ctx, uid := r.Context(), currentUser(r)
	if viewer == uid {
		return badRequest("invalid_viewer", "cannot grant to yourself")
	}
	var out grantJSON
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		ok, err := rbac.CoMembers(ctx, tx, uid, viewer)
		if err != nil {
			return err
		}
		if !ok {
			return notFound() // do not reveal whether the user exists
		}
		// RLS (sg_ins/sg_upd) independently enforces sharer = caller and co-membership.
		if _, err := tx.Exec(ctx, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1, $2, $3)
			ON CONFLICT (sharer_id, viewer_id) DO UPDATE SET level = EXCLUDED.level, updated_at = now()`,
			uid, viewer, level); err != nil {
			return err
		}
		out, err = scanGrant(tx.QueryRow(ctx, grantSelect+` WHERE u.id = $3`, uid, string(rbac.DefaultLevel), viewer))
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}
