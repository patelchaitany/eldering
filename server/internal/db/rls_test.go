package db_test

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/testdb"
)

func TestMain(m *testing.M) { os.Exit(testdb.Main(m, "db")) }

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

type fixture struct {
	t       *testing.T
	d       *testdb.DB
	pool    *pgxpool.Pool
	a, b, c uuid.UUID // alice + bob share a family; carol is a stranger
	family  uuid.UUID
}

func setup(t *testing.T) *fixture {
	t.Helper()
	d := testdb.Get(t)
	ctx := context.Background()
	// ONE connection: proves the identity never leaks from one transaction to the next.
	pool, err := db.Open(ctx, d.AppURL+"&pool_max_conns=1")
	must(t, err)
	t.Cleanup(pool.Close)
	f := &fixture{t: t, d: d, pool: pool}
	f.a, f.b, f.c = f.user("alice"), f.user("bob"), f.user("carol")
	must(t, db.Anon(ctx, pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO families (name, created_by) VALUES ('fam', $1) RETURNING id`, f.a).Scan(&f.family); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1,$2,'admin'),($1,$3,'member')`, f.family, f.a, f.b)
		return err
	}))
	_, err = f.exec(f.a, `INSERT INTO locations_latest (user_id, geom, accuracy_m, recorded_at)
		VALUES ($1, ST_SetSRID(ST_MakePoint(72.8777, 19.0760), 4326)::geography, 8, now())`, f.a)
	must(t, err)
	return f
}

func (f *fixture) user(name string) uuid.UUID {
	ctx := context.Background()
	var id uuid.UUID
	must(f.t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `INSERT INTO users (email, password_hash, display_name) VALUES ($1, 'x', $2) RETURNING id`,
			name+"-"+uuid.NewString()+"@example.test", name).Scan(&id)
	}))
	_, err := f.exec(id, `INSERT INTO user_settings (user_id) VALUES ($1)`, id)
	must(f.t, err)
	return id
}

func (f *fixture) count(uid uuid.UUID, sql string, args ...any) int {
	f.t.Helper()
	var n int
	must(f.t, db.WithUser(context.Background(), f.pool, uid, func(tx pgx.Tx) error {
		return tx.QueryRow(context.Background(), sql, args...).Scan(&n)
	}))
	return n
}

func (f *fixture) exec(uid uuid.UUID, sql string, args ...any) (int64, error) {
	var n int64
	err := db.WithUser(context.Background(), f.pool, uid, func(tx pgx.Tx) error {
		ct, err := tx.Exec(context.Background(), sql, args...)
		n = ct.RowsAffected()
		return err
	})
	return n, err
}

func (f *fixture) status(viewer, sharer uuid.UUID) string {
	f.t.Helper()
	var s string
	must(f.t, db.WithUser(context.Background(), f.pool, viewer, func(tx pgx.Tx) error {
		return tx.QueryRow(context.Background(), `SELECT rls.location_status($1)`, sharer).Scan(&s)
	}))
	return s
}

const seeLoc = `SELECT count(*) FROM locations_latest WHERE user_id = $1`

func TestLocationVisibility(t *testing.T) {
	f := setup(t)
	ctx := context.Background()

	if n := f.count(f.a, seeLoc, f.a); n != 1 {
		t.Fatalf("self should see own location, got %d", n)
	}
	if n := f.count(f.b, seeLoc, f.a); n != 0 || f.status(f.b, f.a) != "hidden" {
		t.Fatalf("no grant row => default 'emergency' => no live location; got %d", n)
	}
	// Bob cannot write Alice's grant; Alice cannot grant to a non-co-member.
	if _, err := f.exec(f.b, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.b); !db.IsRLSViolation(err) {
		t.Fatalf("viewer wrote sharer's grant: %v", err)
	}
	if _, err := f.exec(f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.c); !db.IsRLSViolation(err) {
		t.Fatalf("grant to non-member allowed: %v", err)
	}
	_, err := f.exec(f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.b)
	must(t, err)
	if n := f.count(f.b, seeLoc, f.a); n != 1 || f.status(f.b, f.a) != "live" {
		t.Fatalf("live grant should expose location, got %d %s", n, f.status(f.b, f.a))
	}
	if n := f.count(f.c, seeLoc, f.a); n != 0 || f.status(f.c, f.a) != "hidden" {
		t.Fatalf("non-member sees location: %d", n)
	}
	// Bob cannot change or delete Alice's grant or location (USING hides => 0 rows).
	if n, err := f.exec(f.b, `UPDATE share_grants SET level='off' WHERE sharer_id = $1`, f.a); err != nil || n != 0 {
		t.Fatalf("viewer updated sharer's grant: n=%d err=%v", n, err)
	}
	if n, err := f.exec(f.b, `UPDATE locations_latest SET accuracy_m = 1 WHERE user_id = $1`, f.a); err != nil || n != 0 {
		t.Fatalf("viewer updated sharer's location: n=%d err=%v", n, err)
	}
	if n, err := f.exec(f.b, `DELETE FROM locations_latest WHERE user_id = $1`, f.a); err != nil || n != 0 {
		t.Fatalf("viewer deleted sharer's location: n=%d err=%v", n, err)
	}
	// Bob cannot read Alice's settings.
	if n := f.count(f.b, `SELECT count(*) FROM user_settings WHERE user_id = $1`, f.a); n != 0 {
		t.Fatalf("settings leaked: %d", n)
	}
	// Pause in the future hides (status 'paused'); an elapsed pause shows again.
	_, err = f.exec(f.a, `UPDATE user_settings SET sharing_state='paused', paused_until = now() + interval '1 hour' WHERE user_id = $1`, f.a)
	must(t, err)
	if n := f.count(f.b, seeLoc, f.a); n != 0 || f.status(f.b, f.a) != "paused" {
		t.Fatalf("paused sharer visible: %d %s", n, f.status(f.b, f.a))
	}
	_, err = f.exec(f.a, `UPDATE user_settings SET paused_until = now() - interval '1 minute' WHERE user_id = $1`, f.a)
	must(t, err)
	if n := f.count(f.b, seeLoc, f.a); n != 1 || f.status(f.b, f.a) != "live" {
		t.Fatalf("expired pause should be live again: %d", n)
	}
	// Sharing off hides even with a live grant.
	_, err = f.exec(f.a, `UPDATE user_settings SET sharing_state='off', paused_until = NULL WHERE user_id = $1`, f.a)
	must(t, err)
	if n := f.count(f.b, seeLoc, f.a); n != 0 || f.status(f.b, f.a) != "hidden" {
		t.Fatalf("sharing off still visible: %d", n)
	}
	_, err = f.exec(f.a, `UPDATE user_settings SET sharing_state='on' WHERE user_id = $1`, f.a)
	must(t, err)

	// Removal from the family revokes immediately, and the trigger prunes the
	// grant so a later re-join starts again from the default (emergency).
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `DELETE FROM family_members WHERE family_id=$1 AND user_id=$2`, f.family, f.b)
		return err
	}))
	if n := f.count(f.b, seeLoc, f.a); n != 0 {
		t.Fatalf("ex-member still sees location: %d", n)
	}
	if n := f.count(f.a, `SELECT count(*) FROM share_grants WHERE sharer_id = $1`, f.a); n != 0 {
		t.Fatalf("grant not pruned on leave: %d", n)
	}
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1,$2,'member')`, f.family, f.b)
		return err
	}))
	if n := f.count(f.b, seeLoc, f.a); n != 0 {
		t.Fatalf("re-joined member regained live location without consent: %d", n)
	}
}

func TestGrantSurvivesWhileStillCoMembersElsewhere(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	var fam2 uuid.UUID
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO families (name, created_by) VALUES ('fam2', $1) RETURNING id`, f.a).Scan(&fam2); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role) VALUES ($1,$2,'admin'),($1,$3,'member')`, fam2, f.a, f.b)
		return err
	}))
	_, err := f.exec(f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'live')`, f.a, f.b)
	must(t, err)
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `DELETE FROM family_members WHERE family_id=$1 AND user_id=$2`, f.family, f.b)
		return err
	}))
	if n := f.count(f.b, seeLoc, f.a); n != 1 {
		t.Fatalf("still co-members via fam2: grant must survive, got %d", n)
	}
}

func newShareHash() []byte {
	raw := make([]byte, 32)
	_, _ = rand.Read(raw)
	h := sha256.Sum256(raw)
	return h[:]
}

func TestIncidentVisibilityAndShareToken(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	h := newShareHash()
	var inc uuid.UUID
	must(t, db.WithUser(ctx, f.pool, f.a, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO incidents (user_id, kind, detected_at, geom, share_token_hash, share_expires_at)
			VALUES ($1,'fall',now(), ST_SetSRID(ST_MakePoint(72.8,19.0),4326)::geography, $2, now() + interval '72 hours')
			RETURNING id`, f.a, h).Scan(&inc); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO incident_trail (incident_id, recorded_at, geom, landmark)
			VALUES ($1, now() - interval '5 minutes', ST_SetSRID(ST_MakePoint(72.79,19.01),4326)::geography, 'Station')`, inc)
		return err
	}))
	seeInc := `SELECT count(*) FROM incidents WHERE id = $1`
	seeTrail := `SELECT count(*) FROM incident_trail WHERE incident_id = $1`
	if n := f.count(f.b, seeInc, inc); n != 1 {
		t.Fatalf("default emergency grant should expose incident: %d", n)
	}
	if n := f.count(f.b, seeTrail, inc); n != 1 {
		t.Fatalf("trail should follow incident visibility: %d", n)
	}
	if n := f.count(f.c, seeInc, inc); n != 0 || f.count(f.c, seeTrail, inc) != 0 {
		t.Fatal("non-member sees incident or trail")
	}
	// Only the owner appends trail; a viewer cannot.
	if _, err := f.exec(f.b, `INSERT INTO incident_trail (incident_id, recorded_at, geom)
		VALUES ($1, now(), ST_SetSRID(ST_MakePoint(1,1),4326)::geography)`, inc); !db.IsRLSViolation(err) {
		t.Fatalf("viewer appended trail: %v", err)
	}
	// Bob acks; Carol cannot ack an invisible incident; Alice cannot ack her own.
	_, err := f.exec(f.b, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1,$2,'on_my_way')`, inc, f.b)
	must(t, err)
	if _, err := f.exec(f.c, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1,$2,'seen')`, inc, f.c); !db.IsRLSViolation(err) {
		t.Fatalf("ack on invisible incident: %v", err)
	}
	if _, err := f.exec(f.a, `INSERT INTO incident_acks (incident_id, user_id, state) VALUES ($1,$2,'seen')`, inc, f.a); !db.IsRLSViolation(err) {
		t.Fatalf("owner acked own incident: %v", err)
	}
	if n := f.count(f.a, `SELECT count(*) FROM incident_acks WHERE incident_id=$1`, inc); n != 1 {
		t.Fatalf("owner should see acks: %d", n)
	}
	// A viewer may only resolve as 'handled' (guard trigger), never rewrite the row.
	if _, err := f.exec(f.b, `UPDATE incidents SET share_expires_at = now() + interval '1 year' WHERE id = $1`, inc); !db.IsRLSViolation(err) {
		t.Fatalf("viewer extended share link: %v", err)
	}
	if _, err := f.exec(f.b, `UPDATE incidents SET status='cancelled', resolution='false_alarm', resolved_at=now(), resolved_by=$2 WHERE id = $1`, inc, f.b); !db.IsRLSViolation(err) {
		t.Fatalf("viewer cancelled as false alarm: %v", err)
	}
	if _, err := f.exec(f.b, `UPDATE incidents SET details = '{"x":1}' WHERE id = $1`, inc); err == nil {
		t.Fatal("app may update details (column privilege missing)")
	}
	// Sharing off + always_alert (default true) => still visible; always_alert false => hidden.
	_, err = f.exec(f.a, `UPDATE user_settings SET sharing_state='off' WHERE user_id=$1`, f.a)
	must(t, err)
	if n := f.count(f.b, seeInc, inc); n != 1 {
		t.Fatalf("always_alert should keep incident visible: %d", n)
	}
	_, err = f.exec(f.a, `UPDATE user_settings SET always_alert_on_accident=false WHERE user_id=$1`, f.a)
	must(t, err)
	if n := f.count(f.b, seeInc, inc); n != 0 {
		t.Fatalf("sharing off + no always_alert should hide: %d", n)
	}
	// Grant 'off' => never, even with sharing on.
	_, err = f.exec(f.a, `UPDATE user_settings SET sharing_state='on' WHERE user_id=$1`, f.a)
	must(t, err)
	_, err = f.exec(f.a, `INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES ($1,$2,'off')`, f.a, f.b)
	must(t, err)
	if n := f.count(f.b, seeInc, inc); n != 0 || f.count(f.b, seeTrail, inc) != 0 {
		t.Fatal("grant off must hide incidents and trail")
	}
	_, err = f.exec(f.a, `UPDATE share_grants SET level='emergency' WHERE sharer_id=$1`, f.a)
	must(t, err)
	// Viewer resolves as handled: allowed.
	n, err := f.exec(f.b, `UPDATE incidents SET status='resolved', resolution='handled', resolved_at=now(), resolved_by=$2 WHERE id = $1`, inc, f.b)
	if err != nil || n != 1 {
		t.Fatalf("viewer handled: n=%d err=%v", n, err)
	}

	// Public share token: anonymous, via SECURITY DEFINER functions only.
	// Resolved incidents keep the link but stop exposing location and trail.
	var status string
	var lat *float64
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT status, lat FROM rls.shared_incident($1)`, h).Scan(&status, &lat)
	}))
	if status != "resolved" || lat != nil {
		t.Fatalf("resolved share should hide location: %s %v", status, lat)
	}
	var trailN int
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT count(*) FROM rls.shared_trail($1)`, h).Scan(&trailN)
	}))
	if trailN != 0 {
		t.Fatalf("resolved share exposes trail: %d", trailN)
	}
	// Unknown token: nothing. Anonymous direct table read: nothing (fail closed).
	err = db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT status FROM rls.shared_incident($1)`, newShareHash()).Scan(&status)
	})
	if !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("bad token should find nothing: %v", err)
	}
	var cnt int
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT (SELECT count(*) FROM incidents) + (SELECT count(*) FROM incident_trail)
			+ (SELECT count(*) FROM locations_latest) + (SELECT count(*) FROM share_grants) + (SELECT count(*) FROM user_settings)`).Scan(&cnt)
	}))
	if cnt != 0 {
		t.Fatalf("anonymous saw %d protected rows", cnt)
	}
}

func TestSharedIncidentActiveAndExpiry(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	h := newShareHash()
	var inc uuid.UUID
	must(t, db.WithUser(ctx, f.pool, f.a, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO incidents (user_id, kind, detected_at, geom, share_token_hash, share_expires_at)
			VALUES ($1,'vehicle_crash',now(), ST_SetSRID(ST_MakePoint(72.8,19.0),4326)::geography, $2, now() + interval '72 hours')
			RETURNING id`, f.a, h).Scan(&inc); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO incident_trail (incident_id, recorded_at, geom, landmark) VALUES
			($1, now() - interval '5 minutes', ST_SetSRID(ST_MakePoint(72.79,19.01),4326)::geography, 'Station'),
			($1, now() - interval '1 minute',  ST_SetSRID(ST_MakePoint(72.80,19.00),4326)::geography, NULL)`, inc)
		return err
	}))
	var lat, lng float64
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT lat, lng FROM rls.shared_incident($1)`, h).Scan(&lat, &lng)
	}))
	if lat != 19.0 || lng != 72.8 {
		t.Fatalf("share lookup: %v %v", lat, lng)
	}
	var n int
	var landmark *string
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT count(*), max(landmark) FROM rls.shared_trail($1)`, h).Scan(&n, &landmark)
	}))
	if n != 2 || landmark == nil || *landmark != "Station" {
		t.Fatalf("shared trail: n=%d landmark=%v", n, landmark)
	}
	// Expire the link (as superuser: the fixture ages rows).
	_, err := f.d.Admin(t).Exec(ctx, `UPDATE incidents SET share_expires_at = now() - interval '1 second' WHERE id=$1`, inc)
	must(t, err)
	err = db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT lat FROM rls.shared_incident($1)`, h).Scan(&lat)
	})
	if !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("expired token should find nothing: %v", err)
	}
	// Retention: 72 h after expiry (never resolved) the incident and its trail are gone.
	_, err = f.d.Admin(t).Exec(ctx, `UPDATE incidents SET share_expires_at = now() - interval '73 hours' WHERE id=$1`, inc)
	must(t, err)
	var purged int64
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT rls.purge_expired_incidents()`).Scan(&purged)
	}))
	if purged != 1 {
		t.Fatalf("purge removed %d", purged)
	}
	var left int
	must(t, f.d.Admin(t).QueryRow(ctx, `SELECT count(*) FROM incident_trail WHERE incident_id = $1`, inc).Scan(&left))
	if left != 0 {
		t.Fatalf("trail not cascaded: %d", left)
	}
}

// The GUC placeholder survives the transaction with value ” on the pooled session.
func TestPooledSessionGUCResidue(t *testing.T) {
	f := setup(t) // pool has exactly one connection, already used by WithUser
	ctx := context.Background()
	var v *string
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT current_setting('app.user_id', true)`).Scan(&v)
	}))
	if v == nil || *v != "" {
		t.Fatalf("expected empty-string residue, got %v", v)
	}
	var uid *uuid.UUID
	must(t, db.Anon(ctx, f.pool, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT rls.uid()`).Scan(&uid)
	}))
	if uid != nil {
		t.Fatalf("rls.uid() must be NULL without identity, got %v", uid)
	}
}

func TestRolePrivileges(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	// The API role: not superuser, not BYPASSRLS, owns nothing.
	var super, bypass bool
	var owned int
	must(t, f.d.Admin(t).QueryRow(ctx, `SELECT rolsuper, rolbypassrls,
		(SELECT count(*) FROM pg_class c WHERE c.relowner = r.oid)
		FROM pg_roles r WHERE rolname = 'eldering_app'`).Scan(&super, &bypass, &owned))
	if super || bypass || owned != 0 {
		t.Fatalf("eldering_app: super=%v bypassrls=%v owns=%d", super, bypass, owned)
	}
	// RLS enabled AND forced on every SPEC table.
	var forced int
	must(t, f.d.Admin(t).QueryRow(ctx, `SELECT count(*) FROM pg_class WHERE relname = ANY($1) AND relrowsecurity AND relforcerowsecurity`,
		[]string{"locations_latest", "incidents", "incident_trail", "incident_acks", "share_grants", "user_settings"}).Scan(&forced))
	if forced != 6 {
		t.Fatalf("RLS forced on %d/6 tables", forced)
	}
	// Internal helpers that take two arbitrary users are not callable by the app.
	for _, q := range []string{
		`SELECT rls.grant_level($1, $2)`,
		`SELECT rls.co_members($1, $2)`,
	} {
		_, err := f.exec(f.c, q, f.a, f.b)
		if db.Code(err) != db.CodeInsufficientPriv {
			t.Errorf("%s: expected permission denied, got %v", q, err)
		}
	}
	if _, err := f.exec(f.c, `SELECT rls.sharing_on($1)`, f.a); db.Code(err) != db.CodeInsufficientPriv {
		t.Errorf("sharing_on callable by app: %v", err)
	}
	// No TRUNCATE (not subject to RLS) and no DDL.
	if _, err := f.exec(f.a, `TRUNCATE incidents`); db.Code(err) != db.CodeInsufficientPriv {
		t.Errorf("app can TRUNCATE: %v", err)
	}
	if _, err := f.exec(f.a, `CREATE TABLE x (id int)`); err == nil {
		t.Error("app can create tables")
	}
	if _, err := f.exec(f.a, `DELETE FROM incidents`); db.Code(err) != db.CodeInsufficientPriv {
		t.Errorf("app can DELETE incidents directly: %v", err)
	}
}

// FORCE RLS: the table owner is filtered too; the superuser bypasses.
func TestForceRLSOnOwner(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	owner, err := pgx.Connect(ctx, f.d.OwnerURL)
	must(t, err)
	defer owner.Close(ctx)
	var n int
	must(t, owner.QueryRow(ctx, `SELECT count(*) FROM locations_latest`).Scan(&n))
	if n != 0 {
		t.Fatalf("owner should be subject to FORCEd RLS, saw %d rows", n)
	}
	must(t, f.d.Admin(t).QueryRow(ctx, `SELECT count(*) FROM locations_latest`).Scan(&n))
	if n == 0 {
		t.Fatal("superuser should bypass RLS")
	}
}

// Re-running bootstrap and migrations is a no-op (every deploy runs them).
func TestBootstrapAndMigrateIdempotent(t *testing.T) {
	d := testdb.Get(t)
	ctx := context.Background()
	must(t, db.Bootstrap(ctx, d.Admin(t), testdb.OwnerPassword, testdb.AppPassword))
	owner, err := pgx.Connect(ctx, d.OwnerURL)
	must(t, err)
	defer owner.Close(ctx)
	applied, err := db.Migrate(ctx, owner)
	must(t, err)
	if len(applied) != 0 {
		t.Fatalf("re-run applied %v", applied)
	}
	app, err := pgx.Connect(ctx, d.AppURL)
	must(t, err)
	app.Close(ctx)
}
