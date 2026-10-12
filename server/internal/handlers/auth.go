package handlers

import (
	"context"
	"net/http"
	"net/mail"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/ratelimit"
)

type userJSON struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"display_name"`
	CreatedAt   time.Time `json:"created_at"`
}

type authResponse struct {
	AccessToken      string    `json:"access_token"`
	TokenType        string    `json:"token_type"`
	ExpiresIn        int       `json:"expires_in"`
	AccessExpiresAt  time.Time `json:"access_expires_at"`
	RefreshToken     string    `json:"refresh_token"`
	RefreshExpiresAt time.Time `json:"refresh_expires_at"`
	User             userJSON  `json:"user"`
}

const (
	minPasswordLen = 8
	maxPasswordLen = 128
)

func normalizeEmail(raw string) (string, error) {
	e := strings.ToLower(strings.TrimSpace(raw))
	if len(e) < 3 || len(e) > 254 {
		return "", badRequest("invalid_email", "email must be a valid address")
	}
	addr, err := mail.ParseAddress(e)
	if err != nil || addr.Address != e || !strings.Contains(e[strings.LastIndexByte(e, '@')+1:], ".") {
		return "", badRequest("invalid_email", "email must be a valid address")
	}
	return e, nil
}

func validatePassword(pw string) error {
	n := utf8.RuneCountInString(pw)
	if !utf8.ValidString(pw) || n < minPasswordLen || n > maxPasswordLen {
		return badRequest("invalid_password", "password must be 8-128 characters")
	}
	return nil
}

// cleanName trims and validates a human-entered name (display or family name).
func cleanName(raw, field string) (string, error) {
	s := strings.TrimSpace(raw)
	n := utf8.RuneCountInString(s)
	if !utf8.ValidString(s) || n < 1 || n > 80 {
		return "", badRequest("invalid_"+field, field+" must be 1-80 characters")
	}
	for _, r := range s {
		if unicode.IsControl(r) {
			return "", badRequest("invalid_"+field, field+" must not contain control characters")
		}
	}
	return s, nil
}

// issueSession creates a refresh token row inside tx and signs an access token.
func (s *Server) issueSession(ctx context.Context, tx pgx.Tx, u userJSON) (authResponse, error) {
	refresh, hash, err := auth.NewOpaqueToken()
	if err != nil {
		return authResponse{}, err
	}
	var refreshExp time.Time
	if err := tx.QueryRow(ctx, `INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
		VALUES ($1, $2, now() + make_interval(secs => $3)) RETURNING expires_at`,
		u.ID, hash, s.cfg.RefreshTTL.Seconds()).Scan(&refreshExp); err != nil {
		return authResponse{}, err
	}
	access, accessExp, err := s.tokens.Issue(u.ID)
	if err != nil {
		return authResponse{}, err
	}
	return authResponse{
		AccessToken:      access,
		TokenType:        "Bearer",
		ExpiresIn:        int(s.tokens.TTL().Seconds()),
		AccessExpiresAt:  accessExp,
		RefreshToken:     refresh,
		RefreshExpiresAt: refreshExp,
		User:             u,
	}, nil
}

type registerRequest struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"display_name"`
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) error {
	var req registerRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	email, err := normalizeEmail(req.Email)
	if err != nil {
		return err
	}
	if err := validatePassword(req.Password); err != nil {
		return err
	}
	name, err := cleanName(req.DisplayName, "display_name")
	if err != nil {
		return err
	}
	hash, err := s.hasher.Hash(r.Context(), req.Password)
	if err != nil {
		return err
	}
	ctx := r.Context()
	var resp authResponse
	err = db.Anon(ctx, s.pool, func(tx pgx.Tx) error {
		u := userJSON{Email: email, DisplayName: name}
		err := tx.QueryRow(ctx, `INSERT INTO users (email, password_hash, display_name)
			VALUES ($1, $2, $3) ON CONFLICT (email) DO NOTHING RETURNING id, created_at`,
			email, hash, name).Scan(&u.ID, &u.CreatedAt)
		if db.IsNoRows(err) {
			return conflict("email_taken", "an account with this email already exists")
		}
		if err != nil {
			return err
		}
		// user_settings is RLS-protected (self only): adopt the new identity.
		if err := db.SetUser(ctx, tx, u.ID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO user_settings (user_id) VALUES ($1)`, u.ID); err != nil {
			return err
		}
		resp, err = s.issueSession(ctx, tx, u)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusCreated, resp)
	return nil
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

var errBadCredentials = unauthorized("invalid_credentials", "email or password is incorrect")

func (s *Server) login(w http.ResponseWriter, r *http.Request) error {
	var req loginRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	ctx := r.Context()
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if email == "" || req.Password == "" || len(req.Password) > 4*maxPasswordLen {
		return badRequest("invalid_request", "email and password are required")
	}
	// Per-account limit on top of the per-IP one: a pool of IPs cannot
	// brute-force one account.
	if ok, retry := s.loginAcctLimit.Allow("login:" + email); !ok {
		ratelimit.SetRetryAfter(w, retry)
		return &apiError{http.StatusTooManyRequests, "rate_limited", "too many login attempts, retry later"}
	}

	var u userJSON
	var stored string
	err := db.Anon(ctx, s.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT id, email, display_name, created_at, password_hash FROM users WHERE email = $1`,
			email).Scan(&u.ID, &u.Email, &u.DisplayName, &u.CreatedAt, &stored)
	})
	if db.IsNoRows(err) {
		s.hasher.DummyVerify(ctx, req.Password) // equalise timing for unknown accounts
		return errBadCredentials
	}
	if err != nil {
		return err
	}
	// Hash outside any transaction so slow argon2 never pins a pooled connection.
	ok, rehash, err := s.hasher.Verify(ctx, req.Password, stored)
	if err != nil {
		s.log.ErrorContext(ctx, "stored password hash unusable", "user_id", u.ID, "err", err)
		return errBadCredentials
	}
	if !ok {
		return errBadCredentials
	}
	var newHash string
	if rehash {
		if newHash, err = s.hasher.Hash(ctx, req.Password); err != nil {
			return err
		}
	}
	var resp authResponse
	err = db.Anon(ctx, s.pool, func(tx pgx.Tx) error {
		if newHash != "" {
			if _, err := tx.Exec(ctx, `UPDATE users SET password_hash = $2 WHERE id = $1`, u.ID, newHash); err != nil {
				return err
			}
		}
		var err error
		resp, err = s.issueSession(ctx, tx, u)
		return err
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, resp)
	return nil
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

var errBadRefresh = unauthorized("invalid_refresh_token", "refresh token is invalid, expired or revoked")

// refresh rotates a refresh token. Presenting an already-rotated token is
// treated as theft: every session of that user is revoked.
func (s *Server) refresh(w http.ResponseWriter, r *http.Request) error {
	var req refreshRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	if !auth.LooksLikeOpaqueToken(req.RefreshToken) {
		return errBadRefresh
	}
	ctx := r.Context()
	hash := auth.HashToken(req.RefreshToken)
	var resp authResponse
	reused := false
	err := db.Anon(ctx, s.pool, func(tx pgx.Tx) error {
		var uid uuid.UUID
		err := tx.QueryRow(ctx, `UPDATE refresh_tokens SET revoked_at = now()
			WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now()
			RETURNING user_id`, hash).Scan(&uid)
		if db.IsNoRows(err) {
			var owner uuid.UUID
			var revoked bool
			err := tx.QueryRow(ctx, `SELECT user_id, revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash = $1`,
				hash).Scan(&owner, &revoked)
			if db.IsNoRows(err) {
				return errBadRefresh
			}
			if err != nil {
				return err
			}
			if revoked {
				reused = true
				_, err = tx.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, owner)
				return err // commit the mass revocation, then report 401
			}
			return errBadRefresh // expired
		}
		if err != nil {
			return err
		}
		var u userJSON
		if err := tx.QueryRow(ctx, `SELECT id, email, display_name, created_at FROM users WHERE id = $1`, uid).
			Scan(&u.ID, &u.Email, &u.DisplayName, &u.CreatedAt); err != nil {
			return err
		}
		resp, err = s.issueSession(ctx, tx, u)
		return err
	})
	if err != nil {
		return err
	}
	if reused {
		s.log.WarnContext(ctx, "refresh token reuse detected; all sessions revoked")
		return errBadRefresh
	}
	writeJSON(w, http.StatusOK, resp)
	return nil
}

// logout revokes one refresh token. Always 204 so it is idempotent and
// reveals nothing about token validity.
func (s *Server) logout(w http.ResponseWriter, r *http.Request) error {
	var req refreshRequest
	if err := decodeJSON(r, &req); err != nil {
		return err
	}
	if auth.LooksLikeOpaqueToken(req.RefreshToken) {
		ctx := r.Context()
		err := db.Anon(ctx, s.pool, func(tx pgx.Tx) error {
			_, err := tx.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL`,
				auth.HashToken(req.RefreshToken))
			return err
		})
		if err != nil {
			return err
		}
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
