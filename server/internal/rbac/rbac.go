// Package rbac holds the consent/RBAC vocabulary and the handler-level checks
// that complement the database's row-level security (SPEC: enforce in SQL with
// RLS AND in handlers). RLS is the backstop; these checks produce precise
// 403/404 responses without leaking existence across users.
package rbac

import (
	"context"
	"errors"

	"github.com/google/uuid"

	"github.com/eldering/safety/server/internal/db"
)

// Errors returned by the checks. Handlers map them to 404 / 403.
var (
	// ErrNotFound hides existence: the caller may not know the resource exists.
	ErrNotFound = errors.New("rbac: not found")
	// ErrForbidden means the caller can see the resource but may not act on it.
	ErrForbidden = errors.New("rbac: forbidden")
)

// Level is a share-grant level (sharer -> viewer).
type Level string

// Grant levels. The absence of a grant row between co-members means LevelEmergency.
const (
	LevelLive      Level = "live"
	LevelEmergency Level = "emergency"
	LevelOff       Level = "off"
	DefaultLevel         = LevelEmergency
)

// ParseLevel validates a grant level.
func ParseLevel(s string) (Level, bool) {
	switch l := Level(s); l {
	case LevelLive, LevelEmergency, LevelOff:
		return l, true
	}
	return "", false
}

// Role is a family role. Admins manage invites, membership and the family
// name; they get no extra location visibility.
type Role string

// Family roles.
const (
	RoleAdmin  Role = "admin"
	RoleMember Role = "member"
)

// SharingState is a user's global sharing switch.
type SharingState string

// Sharing states.
const (
	SharingOn     SharingState = "on"
	SharingPaused SharingState = "paused"
	SharingOff    SharingState = "off"
)

// ParseSharingState validates a sharing state.
func ParseSharingState(s string) (SharingState, bool) {
	switch st := SharingState(s); st {
	case SharingOn, SharingPaused, SharingOff:
		return st, true
	}
	return "", false
}

// Resolution of an incident.
type Resolution string

// Incident resolutions.
const (
	ResolutionUserOK     Resolution = "user_ok"
	ResolutionFalseAlarm Resolution = "false_alarm"
	ResolutionHandled    Resolution = "handled"
)

// ParseResolution validates a resolution.
func ParseResolution(s string) (Resolution, bool) {
	switch r := Resolution(s); r {
	case ResolutionUserOK, ResolutionFalseAlarm, ResolutionHandled:
		return r, true
	}
	return "", false
}

// CanResolve: the owner may use any resolution; a viewer with access only 'handled'.
func CanResolve(isOwner bool, r Resolution) bool {
	return isOwner || r == ResolutionHandled
}

// StatusFor maps a resolution to the incident status it produces.
func StatusFor(r Resolution) string {
	if r == ResolutionFalseAlarm {
		return "cancelled"
	}
	return "resolved"
}

// MemberRole returns the caller's role in family, or ErrNotFound if the
// caller is not a member (so non-members cannot probe family ids).
func MemberRole(ctx context.Context, q db.Querier, familyID, userID uuid.UUID) (Role, error) {
	var role Role
	err := q.QueryRow(ctx, `SELECT role FROM family_members WHERE family_id = $1 AND user_id = $2`,
		familyID, userID).Scan(&role)
	if db.IsNoRows(err) {
		return "", ErrNotFound
	}
	return role, err
}

// RequireAdmin returns ErrNotFound for non-members and ErrForbidden for
// members who are not admins.
func RequireAdmin(ctx context.Context, q db.Querier, familyID, userID uuid.UUID) error {
	role, err := MemberRole(ctx, q, familyID, userID)
	if err != nil {
		return err
	}
	if role != RoleAdmin {
		return ErrForbidden
	}
	return nil
}

// CoMembers reports whether a and b (distinct) share at least one family.
func CoMembers(ctx context.Context, q db.Querier, a, b uuid.UUID) (bool, error) {
	if a == b {
		return false, nil
	}
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (
		SELECT 1 FROM family_members x JOIN family_members y USING (family_id)
		WHERE x.user_id = $1 AND y.user_id = $2)`, a, b).Scan(&ok)
	return ok, err
}
