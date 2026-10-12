package auth

import (
	"context"
	"net/http"
	"strings"

	"github.com/google/uuid"
)

type ctxKey struct{}

// WithUserID stores the authenticated user id in ctx.
func WithUserID(ctx context.Context, uid uuid.UUID) context.Context {
	return context.WithValue(ctx, ctxKey{}, uid)
}

// UserID returns the authenticated user id stored by Middleware.
func UserID(ctx context.Context) (uuid.UUID, bool) {
	uid, ok := ctx.Value(ctxKey{}).(uuid.UUID)
	return uid, ok && uid != uuid.Nil
}

// Middleware requires a valid "Authorization: Bearer <access JWT>" header.
// unauthorized writes the error response (kept outside this package so the
// JSON error format lives in one place).
func (t *Tokens) Middleware(unauthorized http.HandlerFunc) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			h := r.Header.Get("Authorization")
			scheme, tok, ok := strings.Cut(h, " ")
			if !ok || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(tok) == "" {
				unauthorized(w, r)
				return
			}
			uid, err := t.Parse(strings.TrimSpace(tok))
			if err != nil {
				unauthorized(w, r)
				return
			}
			next.ServeHTTP(w, r.WithContext(WithUserID(r.Context(), uid)))
		})
	}
}
