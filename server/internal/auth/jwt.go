package auth

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	issuer   = "eldering"
	audience = "eldering-api"
)

// ErrInvalidToken is returned for any access token that fails validation.
var ErrInvalidToken = errors.New("auth: invalid access token")

// Tokens issues and validates HS256 access tokens.
type Tokens struct {
	secret []byte
	ttl    time.Duration
	now    func() time.Time
}

// NewTokens returns an issuer/validator. secret must be at least 32 bytes.
func NewTokens(secret []byte, ttl time.Duration) (*Tokens, error) {
	if len(secret) < 32 {
		return nil, fmt.Errorf("auth: JWT secret must be at least 32 bytes")
	}
	return &Tokens{secret: secret, ttl: ttl, now: time.Now}, nil
}

// TTL is the access-token lifetime.
func (t *Tokens) TTL() time.Duration { return t.ttl }

// Issue returns a signed access token for uid and its expiry.
func (t *Tokens) Issue(uid uuid.UUID) (string, time.Time, error) {
	now := t.now().UTC().Truncate(time.Second)
	exp := now.Add(t.ttl)
	claims := jwt.RegisteredClaims{
		Issuer:    issuer,
		Audience:  jwt.ClaimStrings{audience},
		Subject:   uid.String(),
		IssuedAt:  jwt.NewNumericDate(now),
		NotBefore: jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(exp),
	}
	s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(t.secret)
	if err != nil {
		return "", time.Time{}, err
	}
	return s, exp, nil
}

// Parse validates tok (algorithm pinned to HS256, iss/aud/exp required) and
// returns the subject user id.
func (t *Tokens) Parse(tok string) (uuid.UUID, error) {
	var claims jwt.RegisteredClaims
	_, err := jwt.ParseWithClaims(tok, &claims,
		func(*jwt.Token) (any, error) { return t.secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(issuer),
		jwt.WithAudience(audience),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(30*time.Second),
		jwt.WithTimeFunc(t.now),
	)
	if err != nil {
		return uuid.Nil, ErrInvalidToken
	}
	uid, err := uuid.Parse(claims.Subject)
	if err != nil || uid == uuid.Nil {
		return uuid.Nil, ErrInvalidToken
	}
	return uid, nil
}
