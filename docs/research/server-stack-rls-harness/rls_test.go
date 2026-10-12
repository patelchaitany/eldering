package rlsharness

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ELDERING_RLS_DB=host:port of a throwaway postgis container started as in run.sh.
var (
	base     = "@" + os.Getenv("ELDERING_RLS_DB") + "/eldering?sslmode=disable"
	appDSN   = "postgres://eldering_app:apptest" + base
	ownerDSN = "postgres://eldering_owner:ownertest" + base
	superDSN = "postgres://postgres:supertest" + base
)

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func TestMain(m *testing.M) {
	if os.Getenv("ELDERING_RLS_DB") == "" {
		fmt.Println("skipping: set ELDERING_RLS_DB=127.0.0.1:55432 (see run.sh)")
		os.Exit(0)
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, ownerDSN)
	if err != nil {
		panic(err)
	}
	if err := Migrate(ctx, conn); err != nil {
		panic(err)
	}
	if err := Migrate(ctx, conn); err != nil { // idempotent
		panic(err)
	}
	conn.Close(ctx)
	su, err := pgx.Connect(ctx, superDSN)
	if err != nil {
		panic(err)
	}
	if _, err := su.Exec(ctx, `TRUNCATE users, families CASCADE`); err != nil {
		panic(err)
	}
	su.Close(ctx)
	os.Exit(m.Run())
}

type fixture struct {
	pool    *pgxpool.Pool
	a, b, c uuid.UUID
	family  uuid.UUID
}

func setup(t *testing.T) *fixture {
	t.Helper()
	ctx := context.Background()
	pool, err := NewPool(ctx, appDSN, 1) // ONE connection: proves identity never leaks across requests
	must(t, err)
	t.Cleanup(pool.Close)
	f := &fixture{pool: pool}
	mk := func(name string) uuid.UUID {
		var id uuid.UUID
		must(t, Anon(ctx, pool, func(tx pgx.Tx) error {
			return tx.QueryRow(ctx, `INSERT INTO users (email, password_hash, display_name)
				VALUES ($1, 'x', $2) RETURNING id`, name+"-"+uuid.NewString()+"@ex.test", name).Scan(&id)
		}))
		must(t, WithUser(ctx, pool, id, func(tx pgx.Tx) error {
			_, err := tx.Exec(ctx, `INSERT INTO user_settings (user_id) VALUES ($1)`, id)
			return err
		}))
		return id
	}
	f.a, f.b, f.c = mk("alice"), mk("bob"), mk("carol")
	must(t, Anon(ctx, pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO families (name, created_by) VALUES ('fam', $1) RETURNING id`, f.a).Scan(&f.family); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1,$2,'admin'),($1,$3,'member')`, f.family, f.a, f.b)
		return err
	}))
	// Alice heartbeat (upsert own row).
	must(t, WithUser(ctx, pool, f.a, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO locations_latest (user_id, geom, accuracy_m, recorded_at)
			VALUES ($1, ST_SetSRID(ST_MakePoint($2, $3), 4326)::geography, 8, now())
			ON CONFLICT (user_id) DO UPDATE SET geom = EXCLUDED.geom, accuracy_m = EXCLUDED.accuracy_m,
			  recorded_at = EXCLUDED.recorded_at, updated_at = now()`, f.a, 72.8777, 19.0760)
		return err
	}))
	return f
}

func (f *fixture) countAs(t *testing.T, uid uuid.UUID, sql string, args ...any) int {
	t.Helper()
	var n int
	must(t, WithUser(context.Background(), f.pool, uid, func(tx pgx.Tx) error {
		return tx.QueryRow(context.Background(), sql, args...).Scan(&n)
	}))
	return n
}

func (f *fixture) execAs(t *testing.T, uid uuid.UUID, sql string, args ...any) (int64, error) {
	t.Helper()
	var n int64
	err := WithUser(context.Background(), f.pool, uid, func(tx pgx.Tx) error {
		ct, err := tx.Exec(context.Background(), sql, args...)
		n = ct.RowsAffected()
		return err
	})
	return n, err
}

func isRLSViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "42501"
}

const seeLoc = `SELECT count(*) FROM locations_latest WHERE user_id = $1`

func TestLocationVisibility(t *testing.T) {
	f := setup(t)
	ctx := context.Background()

	if n := f.countAs(t, f.a, seeLoc, f.a); n != 1 {
		t.Fatalf("self should see own location, got %d", n)
	}
	if n := f.countAs(t, f.b, seeLoc, f.a); n != 0 {
		t.Fatalf("no grant row => default 'emergency' => no live location; got %d", n)
	}
	// Bob cannot write Alice's grant.
	if _, err := f.execAs(t, f.b, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.b); !isRLSViolation(err) {
		t.Fatalf("viewer wrote sharer's grant: %v", err)
	}
	// Alice cannot grant to a non-co-member.
	if _, err := f.execAs(t, f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.c); !isRLSViolation(err) {
		t.Fatalf("grant to non-member allowed: %v", err)
	}
	_, err := f.execAs(t, f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')
		ON CONFLICT (sharer_id, viewer_id) DO UPDATE SET level = EXCLUDED.level, updated_at = now()`, f.a, f.b)
	must(t, err)
	if n := f.countAs(t, f.b, seeLoc, f.a); n != 1 {
		t.Fatalf("live grant should expose location, got %d", n)
	}
	if n := f.countAs(t, f.c, seeLoc, f.a); n != 0 {
		t.Fatalf("non-member sees location: %d", n)
	}
	// Bob cannot overwrite Alice's location row (USING hides it => 0 rows).
	if n, err := f.execAs(t, f.b, `UPDATE locations_latest SET accuracy_m = 1 WHERE user_id = $1`, f.a); err != nil || n != 0 {
		t.Fatalf("viewer updated sharer's location: n=%d err=%v", n, err)
	}
	// Pause in the future hides; pause already expired shows.
	_, err = f.execAs(t, f.a, `UPDATE user_settings SET sharing_state='paused', paused_until = now() + interval '1 hour' WHERE user_id = $1`, f.a)
	must(t, err)
	if n := f.countAs(t, f.b, seeLoc, f.a); n != 0 {
		t.Fatalf("paused sharer visible: %d", n)
	}
	_, err = f.execAs(t, f.a, `UPDATE user_settings SET paused_until = now() - interval '1 minute' WHERE user_id = $1`, f.a)
	must(t, err)
	if n := f.countAs(t, f.b, seeLoc, f.a); n != 1 {
		t.Fatalf("expired pause should be live again: %d", n)
	}
	// Removal from family revokes immediately even though the 'live' grant row remains.
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `DELETE FROM family_members WHERE family_id=$1 AND user_id=$2`, f.family, f.b)
		return err
	}))
	if n := f.countAs(t, f.b, seeLoc, f.a); n != 0 {
		t.Fatalf("ex-member still sees location: %d", n)
	}
}

func TestIncidentVisibilityAndShareToken(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	raw := make([]byte, 32)
	_, _ = rand.Read(raw)
	h := sha256.Sum256(raw)
	var inc uuid.UUID
	must(t, WithUser(ctx, f.pool, f.a, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO incidents (user_id, kind, detected_at, geom, share_token_hash, share_expires_at)
			VALUES ($1,'fall',now(), ST_SetSRID(ST_MakePoint(72.8,19.0),4326)::geography, $2, now() + interval '72 hours')
			RETURNING id`, f.a, h[:]).Scan(&inc); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO incident_trail (incident_id, recorded_at, geom, landmark)
			VALUES ($1, now() - interval '5 minutes', ST_SetSRID(ST_MakePoint(72.79,19.01),4326)::geography, 'Station')`, inc)
		return err
	}))
	seeInc := `SELECT count(*) FROM incidents WHERE id = $1`
	seeTrail := `SELECT count(*) FROM incident_trail WHERE incident_id = $1`
	if n := f.countAs(t, f.b, seeInc, inc); n != 1 {
		t.Fatalf("default emergency grant should expose incident: %d", n)
	}
	if n := f.countAs(t, f.b, seeTrail, inc); n != 1 {
		t.Fatalf("trail should follow incident visibility: %d", n)
	}
	if n := f.countAs(t, f.c, seeInc, inc); n != 0 {
		t.Fatalf("non-member sees incident: %d", n)
	}
	// Bob acks; Carol cannot ack an invisible incident.
	_, err := f.execAs(t, f.b, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1,$2,'on_my_way')`, inc, f.b)
	must(t, err)
	if _, err := f.execAs(t, f.c, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1,$2,'seen')`, inc, f.c); !isRLSViolation(err) {
		t.Fatalf("ack on invisible incident: %v", err)
	}
	if n := f.countAs(t, f.a, `SELECT count(*) FROM incident_acks WHERE incident_id=$1`, inc); n != 1 {
		t.Fatalf("owner should see acks: %d", n)
	}
	// Sharing off + always_alert (default true) => still visible; always_alert false => hidden.
	_, err = f.execAs(t, f.a, `UPDATE user_settings SET sharing_state='off' WHERE user_id=$1`, f.a)
	must(t, err)
	if n := f.countAs(t, f.b, seeInc, inc); n != 1 {
		t.Fatalf("always_alert should keep incident visible: %d", n)
	}
	_, err = f.execAs(t, f.a, `UPDATE user_settings SET always_alert_on_accident=false WHERE user_id=$1`, f.a)
	must(t, err)
	if n := f.countAs(t, f.b, seeInc, inc); n != 0 {
		t.Fatalf("sharing off + no always_alert should hide: %d", n)
	}
	// grant 'off' => never, even with sharing on.
	_, err = f.execAs(t, f.a, `UPDATE user_settings SET sharing_state='on' WHERE user_id=$1`, f.a)
	must(t, err)
	_, err = f.execAs(t, f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'off')`, f.a, f.b)
	must(t, err)
	if n := f.countAs(t, f.b, seeInc, inc); n != 0 {
		t.Fatalf("grant off must hide incidents: %d", n)
	}
	// Public share token: anonymous, via SECURITY DEFINER function only.
	var gotID uuid.UUID
	var lat, lng float64
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT id, lat, lng FROM rls.shared_incident($1)`, h[:]).Scan(&gotID, &lat, &lng)
	}))
	if gotID != inc || lat != 19.0 || lng != 72.8 {
		t.Fatalf("share lookup: %v %v %v", gotID, lat, lng)
	}
	var trailN int
	var landmark *string
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT count(*), max(landmark) FROM rls.shared_trail($1)`, h[:]).Scan(&trailN, &landmark)
	}))
	if trailN != 1 || landmark == nil || *landmark != "Station" {
		t.Fatalf("shared trail: n=%d landmark=%v", trailN, landmark)
	}
	// Expired link returns nothing.
	_, err = f.execAs(t, f.a, `UPDATE incidents SET share_expires_at = now() - interval '1 second' WHERE id=$1`, inc)
	must(t, err)
	err = Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT id FROM rls.shared_incident($1)`, h[:]).Scan(&gotID)
	})
	if !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("expired token should find nothing: %v", err)
	}
	bad := sha256.Sum256([]byte("nope"))
	err = Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT id FROM rls.shared_incident($1)`, bad[:]).Scan(&gotID)
	})
	if !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("bad token should find nothing: %v", err)
	}
	// Anonymous direct table read sees nothing (fail closed).
	var n int
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT count(*) FROM incidents`).Scan(&n)
	}))
	if n != 0 {
		t.Fatalf("anonymous saw %d incidents", n)
	}
	// Retention purge.
	_, err = f.execAs(t, f.a, `UPDATE incidents SET status='resolved', resolved_at = now() - interval '73 hours' WHERE id=$1`, inc)
	must(t, err)
	var purged int64
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT rls.purge_expired_incidents()`).Scan(&purged)
	}))
	if purged < 1 {
		t.Fatalf("purge removed %d", purged)
	}
}

// The GUC placeholder survives the transaction with value ” on the pooled session.
func TestPooledSessionGUCResidue(t *testing.T) {
	f := setup(t) // pool has exactly one connection, already used by WithUser
	ctx := context.Background()
	var v *string
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT current_setting('app.user_id', true)`).Scan(&v)
	}))
	if v == nil || *v != "" {
		t.Fatalf("expected empty-string residue, got %v", v)
	}
	var uid *uuid.UUID
	must(t, Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT rls.uid()`).Scan(&uid)
	}))
	if uid != nil {
		t.Fatalf("rls.uid() must be NULL without identity, got %v", uid)
	}
	// Without nullif the naive cast blows up (why rls.uid() uses nullif):
	err := Anon(ctx, f.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `SELECT current_setting('app.user_id', true)::uuid`)
		return err
	})
	if err == nil {
		t.Fatal("expected invalid uuid error on ''::uuid")
	}
}

// FORCE RLS: the table owner is filtered too; superuser bypasses.
func TestForceRLSOnOwner(t *testing.T) {
	_ = setup(t)
	ctx := context.Background()
	count := func(dsn string) int {
		conn, err := pgx.Connect(ctx, dsn)
		must(t, err)
		defer conn.Close(ctx)
		var n int
		must(t, conn.QueryRow(ctx, `SELECT count(*) FROM locations_latest`).Scan(&n))
		return n
	}
	if n := count(ownerDSN); n != 0 {
		t.Fatalf("owner should be subject to FORCEd RLS, saw %d rows", n)
	}
	if n := count(superDSN); n == 0 {
		t.Fatal("superuser should bypass RLS")
	}
	// pg_dump-style row_security=off makes the owner ERROR instead of silently filtering.
	conn, err := pgx.Connect(ctx, ownerDSN)
	must(t, err)
	defer conn.Close(ctx)
	_, err = conn.Exec(ctx, `SET row_security = off`)
	must(t, err)
	var n int
	err = conn.QueryRow(ctx, `SELECT count(*) FROM locations_latest`).Scan(&n)
	if err == nil {
		t.Fatal("expected 'query would be affected by row-level security policy' error")
	}
	t.Logf("owner with row_security=off: %v", err)
}
