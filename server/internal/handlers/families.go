package handlers

import (
	"context"
	"crypto/rand"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

// Invite codes: 8 characters of Crockford base32 (no I, L, O, U), ~40 bits.
const (
	inviteAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	inviteLen      = 8
)

func newInviteCode() (string, error) {
	b := make([]byte, inviteLen)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	for i := range b {
		b[i] = inviteAlphabet[b[i]&31] // 256 is a multiple of 32: unbiased
	}
	return string(b), nil
}

// normalizeInviteCode accepts what people type: lower case, spaces, dashes,
// and the Crockford look-alikes (O->0, I/L->1).
func normalizeInviteCode(raw string) (string, bool) {
	var sb strings.Builder
	for _, r := range strings.ToUpper(raw) {
		switch r {
		case ' ', '-', '\t':
			continue
		case 'O':
			r = '0'
		case 'I', 'L':
			r = '1'
		}
		if !strings.ContainsRune(inviteAlphabet, r) {
			return "", false
		}
		sb.WriteRune(r)
	}
	code := sb.String()
	return code, len(code) == inviteLen
}

type inviteJSON struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expires_at"`
	MaxUses   int       `json:"max_uses"`
}

func (s *Server) createInviteTx(ctx context.Context, tx pgx.Tx, familyID, uid uuid.UUID) (inviteJSON, error) {
	inv := inviteJSON{MaxUses: s.cfg.InviteUses}
	for attempt := 0; attempt < 5; attempt++ {
		code, err := newInviteCode()
		if err != nil {
			return inv, err
		}
		// ON CONFLICT keeps the transaction usable on the (unlikely) collision.
		err = tx.QueryRow(ctx, `INSERT INTO family_invites (code, family_id, created_by, expires_at, max_uses)
			VALUES ($1, $2, $3, now() + make_interval(secs => $4), $5)
			ON CONFLICT (code) DO NOTHING RETURNING code, expires_at`,
			code, familyID, uid, s.cfg.InviteTTL.Seconds(), s.cfg.InviteUses).Scan(&inv.Code, &inv.ExpiresAt)
		if db.IsNoRows(err) {
			continue
		}
		return inv, err
	}
	return inv, errors.New("could not allocate a unique invite code")
}

type familyRequest struct {
	Name string `json:"name"`
}

type createFamilyResponse struct {
	familyJSON
	Invite inviteJSON `json:"invite"`
}

func (s *Server) createFamily(w http.ResponseWriter, r *http.Request) error {
	var req familyRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	name, err := cleanName(req.Name, "name")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var resp createFamilyResponse
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		var fid uuid.UUID
		if err := tx.QueryRow(ctx, `INSERT INTO families (name, created_by) VALUES ($1, $2) RETURNING id`, name, uid).Scan(&fid); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1, $2, 'admin')`, fid, uid); err != nil {
			return err
		}
		var err error
		if resp.Invite, err = s.createInviteTx(ctx, tx, fid, uid); err != nil {
			return err
		}
		resp.familyJSON, err = loadFamily(ctx, tx, fid, uid)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusCreated, resp)
	return nil
}

func (s *Server) renameFamily(w http.ResponseWriter, r *http.Request) error {
	fid, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	var req familyRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	name, err := cleanName(req.Name, "name")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var out familyJSON
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		if err := rbac.RequireAdmin(ctx, tx, fid, uid); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE families SET name = $2 WHERE id = $1`, fid, name); err != nil {
			return err
		}
		var err error
		out, err = loadFamily(ctx, tx, fid, uid)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

func (s *Server) createInvite(w http.ResponseWriter, r *http.Request) error {
	fid, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var inv inviteJSON
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		if err := rbac.RequireAdmin(ctx, tx, fid, uid); err != nil {
			return err
		}
		var err error
		inv, err = s.createInviteTx(ctx, tx, fid, uid)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusCreated, inv)
	return nil
}

type joinRequest struct {
	Code string `json:"code"`
}

var errInviteInvalid = &apiError{http.StatusNotFound, "invite_invalid", "invite code is invalid, expired or used up"}

func (s *Server) joinFamily(w http.ResponseWriter, r *http.Request) error {
	var req joinRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	code, ok := normalizeInviteCode(req.Code)
	if !ok {
		return errInviteInvalid
	}
	ctx, uid := r.Context(), currentUser(r)
	var out familyJSON
	err := db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		var fid uuid.UUID
		err := tx.QueryRow(ctx, `SELECT family_id FROM family_invites
			WHERE code = $1 AND expires_at > now() AND uses < max_uses FOR UPDATE`, code).Scan(&fid)
		if db.IsNoRows(err) {
			return errInviteInvalid
		}
		if err != nil {
			return err
		}
		// Already a member: idempotent success without consuming a use.
		tag, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1, $2, 'member')
			ON CONFLICT (family_id, user_id) DO NOTHING`, fid, uid)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 1 {
			if _, err := tx.Exec(ctx, `UPDATE family_invites SET uses = uses + 1 WHERE code = $1`, code); err != nil {
				return err
			}
		}
		out, err = loadFamily(ctx, tx, fid, uid)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

type memberJSON struct {
	UserID      uuid.UUID   `json:"user_id"`
	DisplayName string      `json:"display_name"`
	Role        rbac.Role   `json:"role"`
	JoinedAt    time.Time   `json:"joined_at"`
	IsMe        bool        `json:"is_me"`
	MyGrant     *rbac.Level `json:"my_grant"`    // what I share with them (null for me)
	TheirGrant  *rbac.Level `json:"their_grant"` // what they share with me (null for me)
}

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request) error {
	fid, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	var out []memberJSON
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		if _, err := rbac.MemberRole(ctx, tx, fid, uid); err != nil {
			return err
		}
		// share_grants RLS lets both sharer and viewer read a row; a missing row is the default.
		rows, err := tx.Query(ctx, `
			SELECT m.user_id, u.display_name, m.role, m.joined_at, m.user_id = $2,
			       CASE WHEN m.user_id = $2 THEN NULL ELSE coalesce(g_out.level, $3) END,
			       CASE WHEN m.user_id = $2 THEN NULL ELSE coalesce(g_in.level, $3) END
			FROM family_members m
			JOIN users u ON u.id = m.user_id
			LEFT JOIN share_grants g_out ON g_out.sharer_id = $2 AND g_out.viewer_id = m.user_id
			LEFT JOIN share_grants g_in  ON g_in.sharer_id = m.user_id AND g_in.viewer_id = $2
			WHERE m.family_id = $1
			ORDER BY m.joined_at, u.display_name`, fid, uid, string(rbac.DefaultLevel))
		if err != nil {
			return err
		}
		out, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (memberJSON, error) {
			var m memberJSON
			err := row.Scan(&m.UserID, &m.DisplayName, &m.Role, &m.JoinedAt, &m.IsMe, &m.MyGrant, &m.TheirGrant)
			return m, err
		})
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

// removeMember: an admin removes anyone, or a member removes themself (leave).
// Grants between people who no longer share a family are pruned by a DB
// trigger, so visibility ends immediately.
func (s *Server) removeMember(w http.ResponseWriter, r *http.Request) error {
	fid, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	target, err := pathUUID(r, "userId")
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), currentUser(r)
	err = db.WithUser(ctx, s.pool, uid, func(tx pgx.Tx) error {
		// Serialise membership changes of this family (last-admin logic below).
		if _, err := tx.Exec(ctx, `SELECT 1 FROM families WHERE id = $1 FOR UPDATE`, fid); err != nil {
			return err
		}
		role, err := rbac.MemberRole(ctx, tx, fid, uid)
		if err != nil {
			return err
		}
		if target != uid {
			if role != rbac.RoleAdmin {
				return forbidden("only a family admin can remove other members")
			}
			if _, err := rbac.MemberRole(ctx, tx, fid, target); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `DELETE FROM family_members WHERE family_id = $1 AND user_id = $2`, fid, target); err != nil {
			return err
		}
		if target != uid {
			// The removed member may know every outstanding code (they are shared in
			// the family). Without this they could re-join at once and regain the
			// default 'emergency' visibility of everyone. The admin issues a new code.
			if _, err := tx.Exec(ctx, `DELETE FROM family_invites WHERE family_id = $1`, fid); err != nil {
				return err
			}
		}
		var remaining, admins int
		if err := tx.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE role = 'admin')
			FROM family_members WHERE family_id = $1`, fid).Scan(&remaining, &admins); err != nil {
			return err
		}
		switch {
		case remaining == 0:
			_, err = tx.Exec(ctx, `DELETE FROM families WHERE id = $1`, fid)
		case admins == 0:
			// Never leave a family without an admin: promote the longest-standing member.
			_, err = tx.Exec(ctx, `UPDATE family_members SET role = 'admin'
				WHERE family_id = $1 AND user_id = (
				  SELECT user_id FROM family_members WHERE family_id = $1 ORDER BY joined_at, user_id LIMIT 1)`, fid)
		}
		return err
	})
	if err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
