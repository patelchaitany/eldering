package retention

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/testdb"
)

func TestMain(m *testing.M) { os.Exit(testdb.Main(m, "retention")) }

func TestRunOncePurges(t *testing.T) {
	d := testdb.Get(t)
	ctx := context.Background()
	pool, err := db.Open(ctx, d.AppURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	admin := d.Admin(t)

	var uid uuid.UUID
	if err := db.Anon(ctx, pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `INSERT INTO users (email, password_hash, display_name) VALUES ($1,'x','R') RETURNING id`,
			"r-"+uuid.NewString()+"@example.test").Scan(&uid)
	}); err != nil {
		t.Fatal(err)
	}
	// Four incidents: resolved 73 h ago (purge), resolved 1 h ago (keep),
	// unresolved with link expired 73 h ago (purge), active (keep).
	type inc struct {
		resolvedAgo, expiresIn time.Duration
		resolved, purge        bool
	}
	cases := []inc{
		{resolvedAgo: 73 * time.Hour, expiresIn: -time.Hour, resolved: true, purge: true},
		{resolvedAgo: time.Hour, expiresIn: 70 * time.Hour, resolved: true},
		{expiresIn: -73 * time.Hour, purge: true},
		{expiresIn: 72 * time.Hour},
	}
	ids := make([]uuid.UUID, len(cases))
	for i, c := range cases {
		_, hash, _ := auth.NewOpaqueToken()
		var resolvedAt *time.Time
		status, resolution := "active", (*string)(nil)
		if c.resolved {
			ts := time.Now().Add(-c.resolvedAgo)
			resolvedAt, status = &ts, "resolved"
			r := "user_ok"
			resolution = &r
		}
		if err := admin.QueryRow(ctx, `INSERT INTO incidents (user_id, kind, detected_at, share_token_hash, share_expires_at, status, resolved_at, resolution)
			VALUES ($1, 'fall', now() - interval '4 days', $2, $3, $4, $5, $6) RETURNING id`,
			uid, hash, time.Now().Add(c.expiresIn), status, resolvedAt, resolution).Scan(&ids[i]); err != nil {
			t.Fatal(err)
		}
		if _, err := admin.Exec(ctx, `INSERT INTO incident_trail (incident_id, recorded_at, geom)
			VALUES ($1, now(), ST_SetSRID(ST_MakePoint(1,1),4326)::geography)`, ids[i]); err != nil {
			t.Fatal(err)
		}
	}
	_, live, _ := auth.NewOpaqueToken()
	_, dead, _ := auth.NewOpaqueToken()
	if _, err := admin.Exec(ctx, `INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES
		($1, $2, now() + interval '1 day'), ($1, $3, now() - interval '1 second')`, uid, live, dead); err != nil {
		t.Fatal(err)
	}

	job := New(pool, slog.New(slog.NewTextHandler(io.Discard, nil)), time.Hour)
	res, err := job.RunOnce(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if res.Incidents != 2 || res.RefreshTokens != 1 {
		t.Fatalf("purge result: %+v", res)
	}
	for i, c := range cases {
		var n int
		if err := admin.QueryRow(ctx, `SELECT (SELECT count(*) FROM incidents WHERE id=$1) + (SELECT count(*) FROM incident_trail WHERE incident_id=$1)`, ids[i]).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if c.purge && n != 0 || !c.purge && n != 2 {
			t.Errorf("case %d (purge=%v): %d rows remain", i, c.purge, n)
		}
	}
	// Idempotent.
	if res, err := job.RunOnce(ctx); err != nil || res.Incidents != 0 {
		t.Fatalf("second run: %+v %v", res, err)
	}
}

func TestRunStopsOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		New(nil, slog.New(slog.NewTextHandler(io.Discard, nil)), time.Hour).Run(ctx)
		close(done)
	}()
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop")
	}
}
