-- rls_app_role.sql: row-level security checked directly in PostgreSQL AS THE APP ROLE.
--
-- Run through psql as the database superuser; the script switches to eldering_app itself:
--   docker compose -f server/docker-compose.yml exec -T db psql -X -q -U postgres -d eldering < server/e2e/rls_app_role.sql
-- (`make e2e-rls` does this). Each check prints "PASS: <name>" (NOTICE) or "FAIL: <name> (...)"
-- (WARNING); the runner counts them. The last line is "RLS-CHECKS-COMPLETE".
--
-- Part 1 builds its own fixtures in ONE transaction, runs the checks under SET LOCAL ROLE
--        eldering_app and ROLLS BACK: nothing is left behind.
-- Part 2 logs in as eldering_app (\c, socket auth inside the db container) and checks the
--        committed data with no identity, privilege-escalation attempts and GUC residue.
-- Optional psql variables :sharer, :viewer, :incident name committed API data (a live grant
-- sharer -> viewer and an active incident of the sharer, acked by the viewer) that Part 2
-- uses for positive checks. The Go test TestRLSAppRole sets them.

\set ON_ERROR_STOP 1
\o /dev/null
SET client_min_messages = notice;

-- Superuser view of the committed data (Part 2 proves the app role sees none of it).
SELECT (SELECT count(*) FROM share_grants)     AS n_share_grants,
       (SELECT count(*) FROM user_settings)    AS n_user_settings,
       (SELECT count(*) FROM locations_latest) AS n_locations_latest,
       (SELECT count(*) FROM incidents)        AS n_incidents,
       (SELECT count(*) FROM incident_trail)   AS n_incident_trail,
       (SELECT count(*) FROM incident_acks)    AS n_incident_acks \gset

-- ======================================================================================
-- Part 1: fixture matrix (rolled back)
-- ======================================================================================
BEGIN;
CREATE SCHEMA e2e_rls;
GRANT USAGE ON SCHEMA e2e_rls TO eldering_app;

-- Fixture ids live in transaction-local GUCs e2e.<key>.
CREATE FUNCTION e2e_rls.id(k text) RETURNS uuid LANGUAGE sql STABLE
  AS $$ SELECT current_setting('e2e.' || k)::uuid $$;

-- Switch the RLS identity ('' = anonymous).
CREATE FUNCTION e2e_rls.as_user(k text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user_id', CASE WHEN k = '' THEN '' ELSE current_setting('e2e.' || k) END, true);
END $$;

CREATE FUNCTION e2e_rls.ok(name text, cond boolean, detail text DEFAULT '') RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF cond THEN RAISE NOTICE 'PASS: %', name;
  ELSE RAISE WARNING 'FAIL: % %', name, detail; END IF;
END $$;

CREATE FUNCTION e2e_rls.count_of(q text) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN EXECUTE q INTO n; RETURN n; END $$;

-- The query q (returning one bigint) must yield want.
CREATE FUNCTION e2e_rls.expect_count(name text, q text, want bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE got bigint;
BEGIN
  EXECUTE q INTO got;
  PERFORM e2e_rls.ok(name, got IS NOT DISTINCT FROM want, format('(got %s, want %s)', got, want));
EXCEPTION WHEN others THEN
  RAISE WARNING 'FAIL: % (unexpected error %: %)', name, SQLSTATE, SQLERRM;
END $$;

CREATE FUNCTION e2e_rls.expect_text(name text, q text, want text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE got text;
BEGIN
  EXECUTE q INTO got;
  PERFORM e2e_rls.ok(name, got IS NOT DISTINCT FROM want, format('(got %L, want %L)', got, want));
EXCEPTION WHEN others THEN
  RAISE WARNING 'FAIL: % (unexpected error %: %)', name, SQLSTATE, SQLERRM;
END $$;

-- The statement q must fail with SQLSTATE code. If it succeeds, its effects are undone.
CREATE FUNCTION e2e_rls.expect_error(name text, q text, code text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE q;
  EXCEPTION WHEN others THEN
    PERFORM e2e_rls.ok(name, SQLSTATE = code, format('(got SQLSTATE %s: %s; want %s)', SQLSTATE, SQLERRM, code));
    RETURN;
  END;
  RAISE EXCEPTION USING ERRCODE = 'E2E00', MESSAGE = 'undo';
EXCEPTION WHEN SQLSTATE 'E2E00' THEN
  RAISE WARNING 'FAIL: % (statement succeeded; want SQLSTATE %)', name, code;
END $$;

-- The statement q must affect exactly want rows. Its effects are always undone.
CREATE FUNCTION e2e_rls.expect_affected(name text, q text, want bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE got bigint;
BEGIN
  BEGIN
    EXECUTE q;
    GET DIAGNOSTICS got = ROW_COUNT;
    RAISE EXCEPTION USING ERRCODE = 'E2E00', MESSAGE = got::text;
  EXCEPTION WHEN SQLSTATE 'E2E00' THEN
    got := SQLERRM::bigint;
  END;
  PERFORM e2e_rls.ok(name, got = want, format('(affected %s rows, want %s)', got, want));
EXCEPTION WHEN others THEN
  RAISE WARNING 'FAIL: % (unexpected error %: %)', name, SQLSTATE, SQLERRM;
END $$;

-- Fixtures: family F = admin A, sharer S, viewer V, viewer W; X is alone in family FX.
-- Grants: S->V live, S->A off, S->W none (= emergency). S has incident I1 (2 trail points,
-- acked by W); X has incident IX.
SELECT set_config('e2e.' || k, gen_random_uuid()::text, true)
FROM unnest(ARRAY['s','v','a','w','x','f','fx','i1','ix']) AS k;
SELECT set_config('e2e.tok', 'e2e-token-' || gen_random_uuid(), true);

INSERT INTO users (id, email, password_hash, display_name)
SELECT e2e_rls.id(k), 'e2e-rls-' || e2e_rls.id(k) || '@example.test', 'x', 'rls ' || k
FROM unnest(ARRAY['s','v','a','w','x']) AS k;
INSERT INTO user_settings (user_id) SELECT e2e_rls.id(k) FROM unnest(ARRAY['s','v','a','w','x']) AS k;
INSERT INTO families (id, name, created_by) VALUES
  (e2e_rls.id('f'), 'rls family', e2e_rls.id('a')),
  (e2e_rls.id('fx'), 'rls other family', e2e_rls.id('x'));
INSERT INTO family_members (family_id, user_id, role) VALUES
  (e2e_rls.id('f'), e2e_rls.id('a'), 'admin'),
  (e2e_rls.id('f'), e2e_rls.id('s'), 'member'),
  (e2e_rls.id('f'), e2e_rls.id('v'), 'member'),
  (e2e_rls.id('f'), e2e_rls.id('w'), 'member'),
  (e2e_rls.id('fx'), e2e_rls.id('x'), 'admin');
INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES
  (e2e_rls.id('s'), e2e_rls.id('v'), 'live'),
  (e2e_rls.id('s'), e2e_rls.id('a'), 'off');
INSERT INTO locations_latest (user_id, geom, accuracy_m, recorded_at)
SELECT e2e_rls.id(k), ST_SetSRID(ST_MakePoint(4.89, 52.37), 4326)::geography, 5, now()
FROM unnest(ARRAY['s','v','x']) AS k;
INSERT INTO incidents (id, user_id, kind, detected_at, geom, share_token_hash, share_expires_at) VALUES
  (e2e_rls.id('i1'), e2e_rls.id('s'), 'fall', now(), ST_SetSRID(ST_MakePoint(4.89, 52.37), 4326)::geography,
   sha256(convert_to(current_setting('e2e.tok'), 'UTF8')), now() + interval '72 hours'),
  (e2e_rls.id('ix'), e2e_rls.id('x'), 'sos', now(), NULL,
   sha256(convert_to(current_setting('e2e.tok') || '-x', 'UTF8')), now() + interval '72 hours');
INSERT INTO incident_trail (incident_id, recorded_at, geom, landmark) VALUES
  (e2e_rls.id('i1'), now() - interval '2 minutes', ST_SetSRID(ST_MakePoint(4.88, 52.36), 4326)::geography, 'Dam Square'),
  (e2e_rls.id('i1'), now() - interval '1 minute',  ST_SetSRID(ST_MakePoint(4.89, 52.37), 4326)::geography, NULL),
  (e2e_rls.id('ix'), now() - interval '1 minute',  ST_SetSRID(ST_MakePoint(2.35, 48.85), 4326)::geography, NULL);
INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('w'), 'seen');

-- Superuser sees the fixtures, so the "sees nothing" checks below are not vacuous.
SELECT e2e_rls.ok('setup.fixtures_present.' || t, e2e_rls.count_of(format('SELECT count(*) FROM %I', t)) > 0)
FROM unnest(ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks']) AS t;

-- ---------------------------------------------------------------- the app role itself
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.expect_text('role.current_user_is_eldering_app', 'SELECT current_user::text', 'eldering_app');
SELECT e2e_rls.expect_text('role.not_superuser_no_bypassrls',
  $q$SELECT rolsuper::text || '/' || rolbypassrls::text FROM pg_roles WHERE rolname = current_user$q$, 'false/false');
SELECT e2e_rls.expect_count('role.rls_enabled_and_forced_on_six_tables', $q$
  SELECT count(*) FROM pg_class
  WHERE oid IN ('public.share_grants'::regclass, 'public.user_settings'::regclass, 'public.locations_latest'::regclass,
                'public.incidents'::regclass, 'public.incident_trail'::regclass, 'public.incident_acks'::regclass)
    AND relrowsecurity AND relforcerowsecurity AND pg_get_userbyid(relowner) <> current_user$q$, 6);

-- ---------------------------------------------------------------- no identity => no rows
-- app.user_id has never been set in this session.
SELECT e2e_rls.expect_text('no_identity.guc_is_unset', $q$SELECT coalesce(current_setting('app.user_id', true), '<unset>')$q$, '<unset>');
SELECT e2e_rls.expect_count('no_identity.' || t, format('SELECT count(*) FROM %I', t), 0)
FROM unnest(ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks']) AS t;

SELECT e2e_rls.as_user('');
SELECT e2e_rls.expect_count('empty_identity.' || t, format('SELECT count(*) FROM %I', t), 0)
FROM unnest(ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks']) AS t;

SELECT set_config('app.user_id', 'not-a-uuid', true);
SELECT e2e_rls.expect_error('garbage_identity_fails_closed.' || t, format('SELECT count(*) FROM %I', t), '22P02')
FROM unnest(ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks']) AS t;
SELECT e2e_rls.as_user('');

-- Anonymous writes are refused too.
SELECT e2e_rls.expect_error('no_identity.cannot_insert_location',
  $q$INSERT INTO locations_latest (user_id, geom, recorded_at) VALUES (e2e_rls.id('a'), 'POINT(1 1)', now())$q$, '42501');
SELECT e2e_rls.expect_affected('no_identity.cannot_update_settings', $q$UPDATE user_settings SET sharing_state = 'off'$q$, 0);

-- ---------------------------------------------------------------- baseline: S on, always_alert, S->V live
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('viewer_live.sees_sharer_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 1);
SELECT e2e_rls.expect_count('viewer_live.sees_own_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('v')$q$, 1);
SELECT e2e_rls.expect_count('viewer_live.not_outsider_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('x')$q$, 0);
SELECT e2e_rls.expect_text('viewer_live.location_status_live', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'live');
SELECT e2e_rls.expect_count('viewer_live.sees_sharer_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.expect_count('viewer_live.not_outsider_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('ix')$q$, 0);
SELECT e2e_rls.expect_count('viewer_live.sees_sharer_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 2);
SELECT e2e_rls.expect_count('viewer_live.not_outsider_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('ix')$q$, 0);
SELECT e2e_rls.expect_count('viewer_live.not_other_viewers_acks', $q$SELECT count(*) FROM incident_acks WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('viewer_live.sees_only_own_incoming_grant', $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s')$q$, 1);
SELECT e2e_rls.expect_count('viewer_live.sees_only_own_settings', 'SELECT count(*) FROM user_settings', 1);
SELECT e2e_rls.expect_text('viewer_live.co_member_helper_is_caller_relative',
  $q$SELECT rls.co_member(e2e_rls.id('s'))::text || '/' || rls.co_member(e2e_rls.id('x'))::text$q$, 'true/false');

SELECT e2e_rls.as_user('a'); -- family admin, grant S->A = off
SELECT e2e_rls.expect_count('admin_grant_off.no_sharer_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('admin_grant_off.location_status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');
SELECT e2e_rls.expect_count('admin_grant_off.no_sharer_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('admin_grant_off.no_sharer_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('admin_grant_off.no_other_grants', $q$SELECT count(*) FROM share_grants WHERE viewer_id <> e2e_rls.id('a') AND sharer_id <> e2e_rls.id('a')$q$, 0);
SELECT e2e_rls.expect_count('admin_grant_off.no_member_settings', $q$SELECT count(*) FROM user_settings WHERE user_id <> e2e_rls.id('a')$q$, 0);

SELECT e2e_rls.as_user('w'); -- default emergency grant
SELECT e2e_rls.expect_count('viewer_emergency.no_sharer_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('viewer_emergency.location_status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');
SELECT e2e_rls.expect_count('viewer_emergency.sees_sharer_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.expect_count('viewer_emergency.sees_sharer_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 2);
SELECT e2e_rls.expect_count('viewer_emergency.sees_own_ack', $q$SELECT count(*) FROM incident_acks WHERE incident_id = e2e_rls.id('i1')$q$, 1);

SELECT e2e_rls.as_user('x'); -- not a co-member
SELECT e2e_rls.expect_count('outsider.no_sharer_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('outsider.location_status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');
SELECT e2e_rls.expect_count('outsider.no_sharer_incident', $q$SELECT count(*) FROM incidents WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_count('outsider.no_sharer_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('outsider.no_acks', $q$SELECT count(*) FROM incident_acks WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('outsider.no_grants', $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_count('outsider.sees_own_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('ix')$q$, 1);
SELECT e2e_rls.expect_count('outsider.sees_own_location_only', 'SELECT count(*) FROM locations_latest', 1);

SELECT e2e_rls.as_user('s'); -- the owner
SELECT e2e_rls.expect_count('owner.sees_viewer_acks_on_own_incident', $q$SELECT count(*) FROM incident_acks WHERE incident_id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.expect_count('owner.sees_own_outgoing_grants', $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s')$q$, 2);
SELECT e2e_rls.expect_count('owner.no_viewer_location_without_grant', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('v')$q$, 0);

-- ---------------------------------------------------------------- sharing states (mutated as superuser)
RESET ROLE;
UPDATE user_settings SET sharing_state = 'paused', paused_until = NULL WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('paused.live_viewer_no_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('paused.live_viewer_status_paused', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'paused');
SELECT e2e_rls.expect_count('paused.always_alert_incident_visible', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.as_user('a');
SELECT e2e_rls.expect_text('paused.non_live_viewer_status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');

RESET ROLE;
UPDATE user_settings SET always_alert_on_accident = false WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('paused_no_always_alert.live_viewer_no_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('paused_no_always_alert.live_viewer_no_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.as_user('w');
SELECT e2e_rls.expect_count('paused_no_always_alert.emergency_viewer_no_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.as_user('s');
SELECT e2e_rls.expect_count('paused_no_always_alert.owner_still_sees_own', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);

RESET ROLE;
UPDATE user_settings SET paused_until = now() - interval '1 minute' WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('elapsed_pause.location_visible', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 1);
SELECT e2e_rls.expect_text('elapsed_pause.status_live', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'live');
SELECT e2e_rls.expect_count('elapsed_pause.incident_visible_without_always_alert', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);

RESET ROLE;
UPDATE user_settings SET paused_until = now() + interval '1 hour' WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('future_pause.location_hidden', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);

RESET ROLE;
UPDATE user_settings SET sharing_state = 'off', paused_until = NULL, always_alert_on_accident = true WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('off_always_alert.no_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('off_always_alert.status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');
SELECT e2e_rls.expect_count('off_always_alert.incident_visible', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.as_user('w');
SELECT e2e_rls.expect_count('off_always_alert.emergency_viewer_incident_visible', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);

RESET ROLE;
UPDATE user_settings SET always_alert_on_accident = false WHERE user_id = e2e_rls.id('s');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('off_no_always_alert.no_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.as_user('w');
SELECT e2e_rls.expect_count('off_no_always_alert.emergency_viewer_no_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);

RESET ROLE;
UPDATE user_settings SET sharing_state = 'on', always_alert_on_accident = true WHERE user_id = e2e_rls.id('s');
UPDATE share_grants SET level = 'off' WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('v');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('grant_off.no_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_count('grant_off.no_incident_despite_always_alert', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('grant_off.no_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 0);

-- ---------------------------------------------------------------- removal revokes immediately
RESET ROLE;
UPDATE share_grants SET level = 'live' WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('v');
DELETE FROM family_members WHERE family_id = e2e_rls.id('f') AND user_id = e2e_rls.id('v');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('removed.no_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_text('removed.status_hidden', $q$SELECT rls.location_status(e2e_rls.id('s'))$q$, 'hidden');
SELECT e2e_rls.expect_count('removed.no_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_count('removed.no_trail', $q$SELECT count(*) FROM incident_trail WHERE incident_id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_text('removed.not_co_member', $q$SELECT rls.co_member(e2e_rls.id('s'))::text$q$, 'false');
SELECT e2e_rls.expect_count('removed.has_no_acks', $q$SELECT count(*) FROM incident_acks WHERE user_id = e2e_rls.id('v')$q$, 0);
RESET ROLE;
SELECT e2e_rls.expect_count('removed.grants_with_removed_member_pruned',
  $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('v')$q$, 0);
SELECT e2e_rls.expect_count('removed.grants_between_remaining_members_kept',
  $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('a')$q$, 1);

INSERT INTO family_members (family_id, user_id, role) VALUES (e2e_rls.id('f'), e2e_rls.id('v'), 'member');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('rejoined.back_to_default_no_location', $q$SELECT count(*) FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_count('rejoined.default_emergency_sees_incident', $q$SELECT count(*) FROM incidents WHERE id = e2e_rls.id('i1')$q$, 1);

-- ---------------------------------------------------------------- write attempts by the viewer
RESET ROLE;
INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('s'), e2e_rls.id('v'), 'emergency');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.as_user('v');
SELECT e2e_rls.expect_count('viewer_write.can_read_incoming_grant',
  $q$SELECT count(*) FROM share_grants WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('v')$q$, 1);
SELECT e2e_rls.expect_error('viewer_write.insert_grant_as_sharer',
  $q$INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('s'), e2e_rls.id('w'), 'live')$q$, '42501');
SELECT e2e_rls.expect_affected('viewer_write.update_incoming_grant',
  $q$UPDATE share_grants SET level = 'live' WHERE sharer_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_affected('viewer_write.delete_incoming_grant',
  $q$DELETE FROM share_grants WHERE sharer_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_error('viewer_write.upsert_flip_grant_to_self',
  $q$INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('s'), e2e_rls.id('v'), 'live')
     ON CONFLICT (sharer_id, viewer_id) DO UPDATE SET level = 'live'$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.grant_to_non_co_member',
  $q$INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('v'), e2e_rls.id('x'), 'live')$q$, '42501');
SELECT e2e_rls.expect_affected('viewer_write.own_grant_to_co_member_allowed',
  $q$INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('v'), e2e_rls.id('s'), 'live')$q$, 1);
SELECT e2e_rls.expect_affected('viewer_write.update_sharer_location',
  $q$UPDATE locations_latest SET accuracy_m = 1 WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_affected('viewer_write.delete_sharer_location',
  $q$DELETE FROM locations_latest WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_error('viewer_write.insert_location_for_other',
  $q$INSERT INTO locations_latest (user_id, geom, recorded_at) VALUES (e2e_rls.id('a'), 'POINT(1 1)', now())$q$, '42501');
SELECT e2e_rls.expect_affected('viewer_write.update_sharer_settings',
  $q$UPDATE user_settings SET sharing_state = 'on' WHERE user_id = e2e_rls.id('s')$q$, 0);
SELECT e2e_rls.expect_error('viewer_write.delete_settings_no_privilege',
  $q$DELETE FROM user_settings WHERE user_id = e2e_rls.id('s')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.insert_incident_for_other',
  $q$INSERT INTO incidents (user_id, kind, detected_at, share_token_hash, share_expires_at)
     VALUES (e2e_rls.id('s'), 'sos', now(), sha256('\x01'::bytea), now() + interval '1 hour')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.append_trail_to_sharer_incident',
  $q$INSERT INTO incident_trail (incident_id, recorded_at, geom) VALUES (e2e_rls.id('i1'), now(), 'POINT(1 1)')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.ack_as_another_user',
  $q$INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('a'), 'seen')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.resolve_as_user_ok',
  $q$UPDATE incidents SET status = 'resolved', resolution = 'user_ok', resolved_at = now(), resolved_by = e2e_rls.id('v')
     WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.cancel_as_false_alarm',
  $q$UPDATE incidents SET status = 'cancelled', resolution = 'false_alarm', resolved_at = now(), resolved_by = e2e_rls.id('v')
     WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.resolve_in_another_users_name',
  $q$UPDATE incidents SET status = 'resolved', resolution = 'handled', resolved_at = now(), resolved_by = e2e_rls.id('w')
     WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.rotate_share_token',
  $q$UPDATE incidents SET share_token_hash = sha256('\x02'::bytea) WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.extend_share_expiry',
  $q$UPDATE incidents SET share_expires_at = now() + interval '1 year' WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.rewrite_details_no_column_privilege',
  $q$UPDATE incidents SET details = '{"x":1}' WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.steal_incident_no_column_privilege',
  $q$UPDATE incidents SET user_id = e2e_rls.id('v') WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.delete_incident_no_privilege',
  $q$DELETE FROM incidents WHERE id = e2e_rls.id('i1')$q$, '42501');
SELECT e2e_rls.expect_error('viewer_write.truncate_incidents', 'TRUNCATE incidents CASCADE', '42501');
SELECT e2e_rls.expect_error('viewer_write.truncate_locations', 'TRUNCATE locations_latest', '42501');
SELECT e2e_rls.expect_affected('viewer_write.resolve_as_handled_allowed',
  $q$UPDATE incidents SET status = 'resolved', resolution = 'handled', resolved_at = now(), resolved_by = e2e_rls.id('v')
     WHERE id = e2e_rls.id('i1')$q$, 1);
SELECT e2e_rls.expect_affected('viewer_write.ack_visible_incident_allowed',
  $q$INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('v'), 'on_my_way')$q$, 1);

SELECT e2e_rls.as_user('x');
SELECT e2e_rls.expect_affected('outsider_write.resolve_invisible_incident',
  $q$UPDATE incidents SET status = 'resolved', resolution = 'handled', resolved_at = now(), resolved_by = e2e_rls.id('x')
     WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_error('outsider_write.ack_invisible_incident',
  $q$INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('x'), 'seen')$q$, '42501');
SELECT e2e_rls.expect_error('outsider_write.grant_to_stranger',
  $q$INSERT INTO share_grants (sharer_id, viewer_id, level) VALUES (e2e_rls.id('x'), e2e_rls.id('s'), 'live')$q$, '42501');

SELECT e2e_rls.as_user('a'); -- admin with grant off
SELECT e2e_rls.expect_affected('admin_write.resolve_invisible_incident',
  $q$UPDATE incidents SET status = 'resolved', resolution = 'handled', resolved_at = now(), resolved_by = e2e_rls.id('a')
     WHERE id = e2e_rls.id('i1')$q$, 0);
SELECT e2e_rls.expect_error('admin_write.ack_invisible_incident',
  $q$INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('a'), 'seen')$q$, '42501');
SELECT e2e_rls.expect_affected('admin_write.change_member_grant',
  $q$UPDATE share_grants SET level = 'live' WHERE sharer_id = e2e_rls.id('s') AND viewer_id = e2e_rls.id('a')$q$, 0);

SELECT e2e_rls.as_user('s');
SELECT e2e_rls.expect_error('owner_write.ack_own_incident',
  $q$INSERT INTO incident_acks (incident_id, user_id, state) VALUES (e2e_rls.id('i1'), e2e_rls.id('s'), 'seen')$q$, '42501');
SELECT e2e_rls.expect_affected('owner_write.append_own_trail_allowed',
  $q$INSERT INTO incident_trail (incident_id, recorded_at, geom) VALUES (e2e_rls.id('i1'), now(), 'POINT(4.9 52.37)')$q$, 1);
SELECT e2e_rls.expect_error('owner_write.rewrite_identity_columns',
  $q$UPDATE incidents SET kind = 'sos' WHERE id = e2e_rls.id('i1')$q$, '42501');

-- ---------------------------------------------------------------- no way around RLS for the app role
SELECT e2e_rls.expect_error('app.internal_helper_co_members_denied', $q$SELECT rls.co_members(e2e_rls.id('s'), e2e_rls.id('a'))$q$, '42501');
SELECT e2e_rls.expect_error('app.internal_helper_grant_level_denied', $q$SELECT rls.grant_level(e2e_rls.id('s'), e2e_rls.id('v'))$q$, '42501');
SELECT e2e_rls.expect_error('app.internal_helper_sharing_on_denied', $q$SELECT rls.sharing_on(e2e_rls.id('s'))$q$, '42501');
SELECT e2e_rls.expect_error('app.disable_rls_denied', 'ALTER TABLE incidents DISABLE ROW LEVEL SECURITY', '42501');
SELECT e2e_rls.expect_error('app.no_force_rls_denied', 'ALTER TABLE locations_latest NO FORCE ROW LEVEL SECURITY', '42501');
SELECT e2e_rls.expect_error('app.drop_policy_denied', 'DROP POLICY inc_read ON incidents', '42501');
SELECT e2e_rls.expect_error('app.create_permissive_policy_denied',
  'CREATE POLICY e2e_all ON incidents FOR SELECT USING (true)', '42501');
SET LOCAL row_security = off;
SELECT e2e_rls.expect_error('app.row_security_off_refused', 'SELECT count(*) FROM incidents', '42501');
SET LOCAL row_security = on;

-- ---------------------------------------------------------------- share-link lookups (anonymous)
SELECT e2e_rls.as_user('');
SELECT e2e_rls.expect_count('share.valid_token_finds_its_incident',
  $q$SELECT count(*) FROM rls.shared_incident(sha256(convert_to(current_setting('e2e.tok'), 'UTF8'))) WHERE kind = 'fall'$q$, 1);
SELECT e2e_rls.expect_count('share.valid_token_trail',
  $q$SELECT count(*) FROM rls.shared_trail(sha256(convert_to(current_setting('e2e.tok'), 'UTF8')))$q$, 2);
SELECT e2e_rls.expect_count('share.other_token_other_incident_only',
  $q$SELECT count(*) FROM rls.shared_incident(sha256(convert_to(current_setting('e2e.tok') || '-x', 'UTF8'))) WHERE kind = 'sos'$q$, 1);
SELECT e2e_rls.expect_count('share.unknown_token_nothing',
  $q$SELECT count(*) FROM rls.shared_incident(sha256(convert_to('nope', 'UTF8')))$q$, 0);
SELECT e2e_rls.expect_count('share.no_direct_table_access_by_hash',
  $q$SELECT count(*) FROM incidents WHERE share_token_hash = sha256(convert_to(current_setting('e2e.tok'), 'UTF8'))$q$, 0);
RESET ROLE;
UPDATE incidents SET share_expires_at = now() - interval '1 second' WHERE id = e2e_rls.id('i1');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.expect_count('share.expired_token_nothing',
  $q$SELECT count(*) FROM rls.shared_incident(sha256(convert_to(current_setting('e2e.tok'), 'UTF8')))$q$, 0);
SELECT e2e_rls.expect_count('share.expired_token_no_trail',
  $q$SELECT count(*) FROM rls.shared_trail(sha256(convert_to(current_setting('e2e.tok'), 'UTF8')))$q$, 0);
RESET ROLE;
UPDATE incidents SET share_expires_at = now() + interval '1 hour', status = 'resolved', resolution = 'user_ok',
  resolved_at = now(), resolved_by = e2e_rls.id('s') WHERE id = e2e_rls.id('i1');
SET LOCAL ROLE eldering_app;
SELECT e2e_rls.expect_count('share.resolved_hides_location',
  $q$SELECT count(*) FROM rls.shared_incident(sha256(convert_to(current_setting('e2e.tok'), 'UTF8'))) WHERE lat IS NULL AND lng IS NULL$q$, 1);
SELECT e2e_rls.expect_count('share.resolved_hides_trail',
  $q$SELECT count(*) FROM rls.shared_trail(sha256(convert_to(current_setting('e2e.tok'), 'UTF8')))$q$, 0);

ROLLBACK;

-- ======================================================================================
-- Part 2: a real login as eldering_app against the committed data
-- ======================================================================================
\c eldering eldering_app
\o /dev/null
SET client_min_messages = notice;
SELECT set_config('e2e.n_share_grants', :'n_share_grants', false),
       set_config('e2e.n_user_settings', :'n_user_settings', false),
       set_config('e2e.n_locations_latest', :'n_locations_latest', false),
       set_config('e2e.n_incidents', :'n_incidents', false),
       set_config('e2e.n_incident_trail', :'n_incident_trail', false),
       set_config('e2e.n_incident_acks', :'n_incident_acks', false);

DO $$
DECLARE t text; n bigint; total bigint;
BEGIN
  IF current_user = 'eldering_app' AND session_user = 'eldering_app' THEN RAISE NOTICE 'PASS: login.session_is_app_role';
  ELSE RAISE WARNING 'FAIL: login.session_is_app_role (current_user %, session_user %)', current_user, session_user; END IF;
  IF current_setting('app.user_id', true) IS NULL THEN RAISE NOTICE 'PASS: login.identity_unset_at_login';
  ELSE RAISE WARNING 'FAIL: login.identity_unset_at_login (%)', current_setting('app.user_id', true); END IF;
  FOREACH t IN ARRAY ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks'] LOOP
    EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
    total := current_setting('e2e.n_' || t)::bigint;
    IF n = 0 AND total > 0 THEN RAISE NOTICE 'PASS: login.no_identity.%', t;
    ELSIF n = 0 THEN RAISE NOTICE 'SKIP: login.no_identity.% (table is empty; run the API suite first)', t;
    ELSE RAISE WARNING 'FAIL: login.no_identity.% (saw % of % rows)', t, n, total; END IF;
  END LOOP;
  PERFORM set_config('app.user_id', gen_random_uuid()::text, false);
  FOREACH t IN ARRAY ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks'] LOOP
    EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
    IF n = 0 THEN RAISE NOTICE 'PASS: login.unknown_identity.%', t;
    ELSE RAISE WARNING 'FAIL: login.unknown_identity.% (saw % rows)', t, n; END IF;
  END LOOP;
  PERFORM set_config('app.user_id', '', false);
END $$;

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('login.set_role_owner', 'SET ROLE eldering_owner'),
      ('login.set_role_definer', 'SET ROLE eldering_definer'),
      ('login.set_role_superuser', 'SET ROLE postgres'),
      ('login.set_session_authorization', 'SET SESSION AUTHORIZATION postgres'),
      ('login.create_table_in_public', 'CREATE TABLE public.e2e_probe (x int)'),
      ('login.create_temp_table', 'CREATE TEMP TABLE e2e_probe (x int)'),
      ('login.create_function_in_rls_schema', 'CREATE FUNCTION rls.e2e_probe() RETURNS int LANGUAGE sql AS ''SELECT 1'''),
      ('login.disable_rls', 'ALTER TABLE incidents DISABLE ROW LEVEL SECURITY'),
      ('login.drop_policy', 'DROP POLICY ll_read ON locations_latest'),
      ('login.call_internal_helper', 'SELECT rls.grant_level(gen_random_uuid(), gen_random_uuid())'),
      ('login.read_password_verifiers', 'SELECT count(*) FROM pg_authid'),
      ('login.copy_to_program', 'COPY (SELECT 1) TO PROGRAM ''true'''),
      ('login.read_server_files', 'SELECT pg_read_file(''/etc/passwd'')'),
      ('login.grant_self_bypassrls', 'ALTER ROLE eldering_app BYPASSRLS'),
      ('login.truncate_incidents', 'TRUNCATE incidents CASCADE'),
      ('login.delete_incidents', 'DELETE FROM incidents')
    ) AS v(name, stmt)
  LOOP
    BEGIN
      EXECUTE r.stmt;
      RAISE WARNING 'FAIL: % (statement succeeded)', r.name;
    EXCEPTION
      WHEN insufficient_privilege THEN RAISE NOTICE 'PASS: %', r.name;
      WHEN others THEN RAISE WARNING 'FAIL: % (SQLSTATE %: %; want 42501)', r.name, SQLSTATE, SQLERRM;
    END;
  END LOOP;
END $$;

\if :{?viewer}
SELECT set_config('e2e.sharer', :'sharer', false),
       set_config('e2e.viewer', :'viewer', false),
       set_config('e2e.incident', :'incident', false);

DO $$
DECLARE
  s uuid := current_setting('e2e.sharer')::uuid;
  v uuid := current_setting('e2e.viewer')::uuid;
  i uuid := current_setting('e2e.incident')::uuid;
  n bigint;
BEGIN
  PERFORM set_config('app.user_id', v::text, false);
  SELECT count(*) INTO n FROM locations_latest WHERE user_id = s;
  IF n = 1 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_live_location'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_live_location (%)', n; END IF;
  SELECT count(*) INTO n FROM incidents WHERE id = i;
  IF n = 1 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_incident'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_incident (%)', n; END IF;
  SELECT count(*) INTO n FROM incident_trail WHERE incident_id = i;
  IF n > 0 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_trail'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_trail (%)', n; END IF;
  SELECT count(*) INTO n FROM share_grants WHERE sharer_id = s AND viewer_id = v;
  IF n = 1 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_incoming_grant'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_incoming_grant (%)', n; END IF;
  SELECT count(*) INTO n FROM user_settings;
  IF n = 1 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_only_own_settings'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_only_own_settings (%)', n; END IF;
  SELECT count(*) INTO n FROM incident_acks WHERE incident_id = i;
  IF n = 1 THEN RAISE NOTICE 'PASS: login.api_viewer_sees_own_ack'; ELSE RAISE WARNING 'FAIL: login.api_viewer_sees_own_ack (%)', n; END IF;
  UPDATE share_grants SET level = 'off' WHERE sharer_id = s AND viewer_id = v;
  GET DIAGNOSTICS n = ROW_COUNT;
  IF n = 0 THEN RAISE NOTICE 'PASS: login.api_viewer_cannot_change_incoming_grant'; ELSE RAISE WARNING 'FAIL: login.api_viewer_cannot_change_incoming_grant (%)', n; END IF;
  PERFORM set_config('app.user_id', s::text, false);
  SELECT count(*) INTO n FROM incident_acks WHERE incident_id = i;
  IF n >= 1 THEN RAISE NOTICE 'PASS: login.api_owner_sees_viewer_ack'; ELSE RAISE WARNING 'FAIL: login.api_owner_sees_viewer_ack (%)', n; END IF;
  PERFORM set_config('app.user_id', '', false);
END $$;

-- A transaction-local identity must not survive COMMIT on a pooled connection
-- (fresh session: the placeholder GUC is created by the SET LOCAL itself).
\c eldering eldering_app
\o /dev/null
SET client_min_messages = notice;
BEGIN;
SELECT set_config('app.user_id', :'viewer', true);
COMMIT;
DO $$
DECLARE t text; n bigint;
BEGIN
  IF coalesce(current_setting('app.user_id', true), '') = '' THEN RAISE NOTICE 'PASS: login.local_identity_reset_after_commit';
  ELSE RAISE WARNING 'FAIL: login.local_identity_reset_after_commit (%)', current_setting('app.user_id', true); END IF;
  FOREACH t IN ARRAY ARRAY['share_grants','user_settings','locations_latest','incidents','incident_trail','incident_acks'] LOOP
    EXECUTE format('SELECT count(*) FROM %I', t) INTO n;
    IF n = 0 THEN RAISE NOTICE 'PASS: login.no_rows_after_local_identity_commit.%', t;
    ELSE RAISE WARNING 'FAIL: login.no_rows_after_local_identity_commit.% (saw % rows)', t, n; END IF;
  END LOOP;
END $$;
\endif

\echo RLS-CHECKS-COMPLETE
