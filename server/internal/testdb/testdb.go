// Package testdb gives integration tests a real PostgreSQL/PostGIS database
// that went through exactly the production path: role bootstrap as superuser,
// then the embedded migrations as eldering_owner.
//
// Server selection, in order:
//  1. ELDERING_TEST_PG_URL: a superuser DSN (any database) on a PostGIS server.
//  2. Otherwise docker-compose.test.yml is brought up (idempotent, serialised
//     across parallel `go test` package processes with a file lock).
//
// Each test package gets its own fresh database, dropped afterwards. When no
// server is available the integration tests are skipped, unless
// ELDERING_REQUIRE_DB=1 (CI / `make test`), which turns that into a failure.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/db"
)

// Test-only role passwords (the roles are cluster-wide on the test server).
const (
	OwnerPassword = "eldering-owner-test-password"
	AppPassword   = "eldering-app-test-password"
)

// DB describes one per-package test database.
type DB struct {
	Name     string
	AdminURL string // superuser, connected to Name
	OwnerURL string // eldering_owner
	AppURL   string // eldering_app (what the API uses)
}

var (
	current     *DB
	unavailable string
)

// Main is called from a package's TestMain. It provisions the database, runs
// the tests and drops the database. pkg is a short label used in the db name.
func Main(m *testing.M, pkg string) int {
	flag.Parse()
	if testing.Short() {
		unavailable = "integration tests disabled by -short"
		return m.Run()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	server, err := serverURL(ctx)
	if err == nil {
		current, err = create(ctx, server, pkg)
	}
	if err != nil {
		if os.Getenv("ELDERING_REQUIRE_DB") == "1" {
			fmt.Fprintf(os.Stderr, "testdb: integration database required but unavailable: %v\n", err)
			return 1
		}
		unavailable = err.Error()
		fmt.Fprintf(os.Stderr, "testdb: skipping integration tests: %v\n", err)
		return m.Run()
	}
	code := m.Run()
	dropCtx, dropCancel := context.WithTimeout(context.Background(), time.Minute)
	defer dropCancel()
	if err := drop(dropCtx, server, current.Name); err != nil {
		fmt.Fprintf(os.Stderr, "testdb: drop %s: %v\n", current.Name, err)
	}
	return code
}

// Get returns the package database or skips the test.
func Get(t testing.TB) *DB {
	t.Helper()
	if current == nil {
		t.Skipf("integration database unavailable: %s", unavailable)
	}
	return current
}

func moduleRoot() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", ".."))
}

func serverURL(ctx context.Context) (string, error) {
	if u := os.Getenv("ELDERING_TEST_PG_URL"); u != "" {
		return u, nil
	}
	port := os.Getenv("ELDERING_TEST_DB_PORT")
	if port == "" {
		port = "55433"
	}
	if _, err := exec.LookPath("docker"); err != nil {
		return "", errors.New("docker not found and ELDERING_TEST_PG_URL not set")
	}
	infoCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(infoCtx, "docker", "info", "--format", "{{.ServerVersion}}").CombinedOutput(); err != nil {
		return "", fmt.Errorf("docker daemon not reachable: %v: %s", err, out)
	}
	unlock, err := lockFile(filepath.Join(os.TempDir(), "eldering-testdb.lock"))
	if err != nil {
		return "", err
	}
	defer unlock()
	compose := filepath.Join(moduleRoot(), "docker-compose.test.yml")
	cmd := exec.CommandContext(ctx, "docker", "compose", "-f", compose, "up", "-d", "--wait", "--wait-timeout", "300")
	cmd.Env = append(os.Environ(), "ELDERING_TEST_DB_PORT="+port)
	if out, err := cmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("docker compose up: %v: %s", err, out)
	}
	return "postgres://postgres:postgres@127.0.0.1:" + port + "/postgres?sslmode=disable", nil
}

func withDB(dsn, user, password, dbname string) (string, error) {
	u, err := url.Parse(dsn)
	if err != nil {
		return "", err
	}
	if user != "" {
		u.User = url.UserPassword(user, password)
	}
	u.Path = "/" + dbname
	q := u.Query()
	if q.Get("sslmode") == "" {
		q.Set("sslmode", "disable") // loopback test server; callers append "&..." params
	}
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func create(ctx context.Context, server, pkg string) (*DB, error) {
	// Role bootstrap touches cluster-wide catalogs: serialise across packages.
	unlock, err := lockFile(filepath.Join(os.TempDir(), "eldering-testdb-bootstrap.lock"))
	if err != nil {
		return nil, err
	}
	defer unlock()

	suffix := make([]byte, 4)
	_, _ = rand.Read(suffix)
	name := "eldering_t_" + pkg + "_" + hex.EncodeToString(suffix)

	conn, err := connect(ctx, server)
	if err != nil {
		return nil, err
	}
	_, err = conn.Exec(ctx, `CREATE DATABASE `+pgx.Identifier{name}.Sanitize()+` TEMPLATE template_postgis`)
	if err != nil { // plain PostgreSQL server without the PostGIS template
		_, err = conn.Exec(ctx, `CREATE DATABASE `+pgx.Identifier{name}.Sanitize())
	}
	conn.Close(ctx)
	if err != nil {
		return nil, fmt.Errorf("create database: %w", err)
	}

	d := &DB{Name: name}
	if d.AdminURL, err = withDB(server, "", "", name); err != nil {
		return nil, err
	}
	if d.OwnerURL, err = withDB(server, db.RoleOwner, OwnerPassword, name); err != nil {
		return nil, err
	}
	if d.AppURL, err = withDB(server, db.RoleApp, AppPassword, name); err != nil {
		return nil, err
	}

	admin, err := connect(ctx, d.AdminURL)
	if err != nil {
		return nil, err
	}
	err = db.Bootstrap(ctx, admin, OwnerPassword, AppPassword)
	admin.Close(ctx)
	if err != nil {
		return nil, err
	}
	owner, err := connect(ctx, d.OwnerURL)
	if err != nil {
		return nil, fmt.Errorf("connect as owner (bootstrap password check): %w", err)
	}
	defer owner.Close(ctx)
	if _, err := db.Migrate(ctx, owner); err != nil {
		return nil, err
	}
	return d, nil
}

func drop(ctx context.Context, server, name string) error {
	conn, err := connect(ctx, server)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	_, err = conn.Exec(ctx, `DROP DATABASE IF EXISTS `+pgx.Identifier{name}.Sanitize()+` WITH (FORCE)`)
	return err
}

func connect(ctx context.Context, dsn string) (*pgx.Conn, error) {
	var lastErr error
	for i := 0; i < 30; i++ {
		c, err := pgx.Connect(ctx, dsn)
		if err == nil {
			return c, nil
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return nil, lastErr
}

// Admin opens a superuser connection to the package database (for test
// fixtures that must bypass RLS, e.g. ageing rows).
func (d *DB) Admin(t testing.TB) *pgx.Conn {
	t.Helper()
	c, err := pgx.Connect(context.Background(), d.AdminURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close(context.Background()) })
	return c
}
