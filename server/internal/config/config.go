// Package config loads the API configuration from environment variables.
package config

import (
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds everything the API process needs. All values come from the
// environment so that secrets never live in files baked into the image.
type Config struct {
	ListenAddr     string
	DatabaseURL    string // eldering_app DSN (non-owner, NOBYPASSRLS)
	PublicBaseURL  string // used to build share URLs, no trailing slash
	JWTSecret      []byte
	ClientIPHeader string // header set (overwritten) by the reverse proxy; empty = use RemoteAddr

	AccessTTL   time.Duration
	RefreshTTL  time.Duration
	ShareTTL    time.Duration
	InviteTTL   time.Duration
	InviteUses  int
	Retention   time.Duration // how often the purge job runs
	BodyLimit   int64
	MaxHashJobs int // concurrent argon2id computations

	// Rate limits (per client IP unless noted).
	AuthPerMinute   float64 // register / login / join
	AuthBurst       int
	TokenPerMinute  float64 // refresh / logout
	TokenBurst      int
	LoginPerAccount float64 // failed+successful logins per email per minute
	LoginAcctBurst  int
	SharePerMinute  float64 // public /s/{token} page + data
	ShareBurst      int
}

// MigrateConfig is what `eldering-api migrate` needs.
type MigrateConfig struct {
	AdminURL      string // optional superuser DSN: enables idempotent role/grant bootstrap
	OwnerURL      string // eldering_owner DSN: runs schema migrations
	OwnerPassword string // role passwords applied by the bootstrap (only with AdminURL)
	AppPassword   string
}

// Load reads the serve configuration.
func Load() (Config, error) {
	return load(os.Getenv)
}

// LoadMigrate reads the migrate configuration.
func LoadMigrate() (MigrateConfig, error) {
	return loadMigrate(os.Getenv)
}

type getenv func(string) string

func load(env getenv) (Config, error) {
	var errs []error
	c := Config{
		ListenAddr:     def(env("LISTEN_ADDR"), ":8080"),
		DatabaseURL:    env("DATABASE_URL"),
		PublicBaseURL:  strings.TrimRight(def(env("PUBLIC_BASE_URL"), "http://localhost:8080"), "/"),
		ClientIPHeader: def(env("CLIENT_IP_HEADER"), "X-Real-IP"),
	}
	if strings.EqualFold(c.ClientIPHeader, "none") {
		c.ClientIPHeader = ""
	}
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("DATABASE_URL is required"))
	}
	if u, err := url.Parse(c.PublicBaseURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		errs = append(errs, fmt.Errorf("PUBLIC_BASE_URL must be an absolute http(s) URL, got %q", c.PublicBaseURL))
	}
	secret, err := hex.DecodeString(strings.TrimSpace(env("JWT_SECRET")))
	switch {
	case err != nil:
		errs = append(errs, errors.New("JWT_SECRET must be hex encoded"))
	case len(secret) < 32:
		errs = append(errs, errors.New("JWT_SECRET must be at least 32 bytes (64 hex chars)"))
	default:
		c.JWTSecret = secret
	}

	d := func(key string, fallback time.Duration) time.Duration {
		v, e := duration(env(key), fallback)
		if e != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, e))
		}
		return v
	}
	i := func(key string, fallback int) int {
		v, e := integer(env(key), fallback)
		if e != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, e))
		}
		return v
	}
	f := func(key string, fallback float64) float64 {
		v, e := float(env(key), fallback)
		if e != nil {
			errs = append(errs, fmt.Errorf("%s: %w", key, e))
		}
		return v
	}

	c.AccessTTL = d("ACCESS_TOKEN_TTL", 15*time.Minute)
	c.RefreshTTL = d("REFRESH_TOKEN_TTL", 30*24*time.Hour)
	c.ShareTTL = d("SHARE_TOKEN_TTL", 72*time.Hour)
	c.InviteTTL = d("INVITE_TTL", 48*time.Hour)
	c.InviteUses = i("INVITE_MAX_USES", 10)
	c.Retention = d("RETENTION_INTERVAL", time.Hour)
	c.BodyLimit = int64(i("BODY_LIMIT_BYTES", 1<<20))
	c.MaxHashJobs = i("MAX_PASSWORD_HASH_JOBS", 2)

	c.AuthPerMinute = f("RATE_AUTH_PER_MIN", 10)
	c.AuthBurst = i("RATE_AUTH_BURST", 10)
	c.TokenPerMinute = f("RATE_TOKEN_PER_MIN", 60)
	c.TokenBurst = i("RATE_TOKEN_BURST", 30)
	c.LoginPerAccount = f("RATE_LOGIN_ACCOUNT_PER_MIN", 1)
	c.LoginAcctBurst = i("RATE_LOGIN_ACCOUNT_BURST", 5)
	c.SharePerMinute = f("RATE_SHARE_PER_MIN", 60)
	c.ShareBurst = i("RATE_SHARE_BURST", 30)

	for _, p := range []struct {
		name string
		ok   bool
	}{
		{"ACCESS_TOKEN_TTL", c.AccessTTL > 0},
		{"REFRESH_TOKEN_TTL", c.RefreshTTL > c.AccessTTL},
		{"SHARE_TOKEN_TTL", c.ShareTTL > 0},
		{"INVITE_TTL", c.InviteTTL > 0},
		{"INVITE_MAX_USES", c.InviteUses > 0},
		{"RETENTION_INTERVAL", c.Retention >= time.Minute},
		{"BODY_LIMIT_BYTES", c.BodyLimit >= 1024},
		{"MAX_PASSWORD_HASH_JOBS", c.MaxHashJobs > 0},
		{"RATE_*", c.AuthPerMinute > 0 && c.TokenPerMinute > 0 && c.LoginPerAccount > 0 && c.SharePerMinute > 0 &&
			c.AuthBurst > 0 && c.TokenBurst > 0 && c.LoginAcctBurst > 0 && c.ShareBurst > 0},
	} {
		if !p.ok {
			errs = append(errs, fmt.Errorf("%s has an invalid value", p.name))
		}
	}
	return c, errors.Join(errs...)
}

func loadMigrate(env getenv) (MigrateConfig, error) {
	c := MigrateConfig{
		AdminURL:      env("DATABASE_ADMIN_URL"),
		OwnerURL:      env("DATABASE_OWNER_URL"),
		OwnerPassword: env("ELDERING_OWNER_PASSWORD"),
		AppPassword:   env("ELDERING_APP_PASSWORD"),
	}
	var errs []error
	if c.OwnerURL == "" {
		errs = append(errs, errors.New("DATABASE_OWNER_URL is required"))
	}
	if c.AdminURL != "" && (len(c.OwnerPassword) < 16 || len(c.AppPassword) < 16) {
		errs = append(errs, errors.New("ELDERING_OWNER_PASSWORD and ELDERING_APP_PASSWORD (>=16 chars) are required with DATABASE_ADMIN_URL"))
	}
	return c, errors.Join(errs...)
}

func def(v, fallback string) string {
	if strings.TrimSpace(v) == "" {
		return fallback
	}
	return strings.TrimSpace(v)
}

func duration(v string, fallback time.Duration) (time.Duration, error) {
	if strings.TrimSpace(v) == "" {
		return fallback, nil
	}
	return time.ParseDuration(strings.TrimSpace(v))
}

func integer(v string, fallback int) (int, error) {
	if strings.TrimSpace(v) == "" {
		return fallback, nil
	}
	return strconv.Atoi(strings.TrimSpace(v))
}

func float(v string, fallback float64) (float64, error) {
	if strings.TrimSpace(v) == "" {
		return fallback, nil
	}
	return strconv.ParseFloat(strings.TrimSpace(v), 64)
}
