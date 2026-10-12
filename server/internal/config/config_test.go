package config

import (
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) getenv {
	return func(k string) string { return m[k] }
}

const secret64 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

func TestLoadDefaults(t *testing.T) {
	c, err := load(envOf(map[string]string{
		"DATABASE_URL":    "postgres://x",
		"JWT_SECRET":      secret64,
		"PUBLIC_BASE_URL": "https://safety.example.test/",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if c.ListenAddr != ":8080" || c.AccessTTL != 15*time.Minute || c.RefreshTTL != 30*24*time.Hour ||
		c.ShareTTL != 72*time.Hour || c.InviteTTL != 48*time.Hour || c.BodyLimit != 1<<20 || c.Retention != time.Hour {
		t.Fatalf("unexpected defaults: %+v", c)
	}
	if c.PublicBaseURL != "https://safety.example.test" {
		t.Fatalf("trailing slash not trimmed: %q", c.PublicBaseURL)
	}
	if c.ClientIPHeader != "X-Real-IP" || len(c.JWTSecret) != 32 {
		t.Fatalf("bad header/secret: %q %d", c.ClientIPHeader, len(c.JWTSecret))
	}
}

func TestLoadErrors(t *testing.T) {
	_, err := load(envOf(map[string]string{
		"JWT_SECRET":       "abcd",
		"PUBLIC_BASE_URL":  "ftp://nope",
		"ACCESS_TOKEN_TTL": "soon",
		"INVITE_MAX_USES":  "0",
	}))
	if err == nil {
		t.Fatal("expected errors")
	}
	for _, want := range []string{"DATABASE_URL", "JWT_SECRET", "PUBLIC_BASE_URL", "ACCESS_TOKEN_TTL", "INVITE_MAX_USES"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error %q does not mention %s", err, want)
		}
	}
}

func TestClientIPHeaderNone(t *testing.T) {
	c, err := load(envOf(map[string]string{"DATABASE_URL": "x", "JWT_SECRET": secret64, "CLIENT_IP_HEADER": "none"}))
	if err != nil {
		t.Fatal(err)
	}
	if c.ClientIPHeader != "" {
		t.Fatalf("want empty header, got %q", c.ClientIPHeader)
	}
}

func TestLoadMigrate(t *testing.T) {
	if _, err := loadMigrate(envOf(nil)); err == nil {
		t.Fatal("owner URL should be required")
	}
	if _, err := loadMigrate(envOf(map[string]string{"DATABASE_OWNER_URL": "x", "DATABASE_ADMIN_URL": "y"})); err == nil {
		t.Fatal("role passwords should be required with admin URL")
	}
	c, err := loadMigrate(envOf(map[string]string{
		"DATABASE_OWNER_URL": "x", "DATABASE_ADMIN_URL": "y",
		"ELDERING_OWNER_PASSWORD": strings.Repeat("a", 16), "ELDERING_APP_PASSWORD": strings.Repeat("b", 16),
	}))
	if err != nil || c.AdminURL != "y" {
		t.Fatalf("unexpected: %+v %v", c, err)
	}
}
