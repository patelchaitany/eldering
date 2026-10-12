-- 0001_init: schema, privileges, RLS helpers and policies (SPEC "Data model" + "RBAC rules").
-- Runs as eldering_owner inside one transaction. Roles, extensions and schema `rls`
-- are created beforehand by the idempotent bootstrap (internal/db/bootstrap.go).

-- ===================== identity / families (handler-enforced; no RLS per SPEC) =====================
CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         citext NOT NULL UNIQUE CHECK (length(email) BETWEEN 3 AND 254),
  password_hash text   NOT NULL,
  display_name  text   NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE families (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name       text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  created_by uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- 8 chars, Crockford base32 alphabet (no I, L, O, U).
CREATE TABLE family_invites (
  code       text PRIMARY KEY CHECK (code ~ '^[0-9A-HJKMNP-TV-Z]{8}$'),
  family_id  uuid NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  created_by uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  max_uses   integer NOT NULL CHECK (max_uses > 0),
  uses       integer NOT NULL DEFAULT 0 CHECK (uses >= 0 AND uses <= max_uses)
);
CREATE INDEX family_invites_family_idx ON family_invites (family_id);
CREATE INDEX family_invites_expires_idx ON family_invites (expires_at);

CREATE TABLE family_members (
  family_id uuid NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  user_id   uuid NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
  role      text NOT NULL CHECK (role IN ('admin','member')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (family_id, user_id)
);
CREATE INDEX family_members_user_idx ON family_members (user_id);

CREATE TABLE refresh_tokens (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash bytea NOT NULL UNIQUE CHECK (length(token_hash) = 32),   -- SHA-256 of the opaque token
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at);

-- ===================== RLS-protected =====================
CREATE TABLE share_grants (
  sharer_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  viewer_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  level      text NOT NULL CHECK (level IN ('live','emergency','off')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (sharer_id, viewer_id),
  CHECK (sharer_id <> viewer_id)
);
CREATE INDEX share_grants_viewer_idx ON share_grants (viewer_id);

CREATE TABLE user_settings (
  user_id                  uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  sharing_state            text NOT NULL DEFAULT 'on' CHECK (sharing_state IN ('on','paused','off')),
  paused_until             timestamptz,
  always_alert_on_accident boolean NOT NULL DEFAULT true,
  CHECK (sharing_state = 'paused' OR paused_until IS NULL)
);

-- Only the latest point per user; no history (SPEC).
CREATE TABLE locations_latest (
  user_id     uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  geom        geography(Point,4326) NOT NULL,
  accuracy_m  real     CHECK (accuracy_m  IS NULL OR accuracy_m >= 0),
  speed_mps   real     CHECK (speed_mps   IS NULL OR speed_mps >= 0),
  bearing_deg real     CHECK (bearing_deg IS NULL OR (bearing_deg >= 0 AND bearing_deg < 360)),
  activity    text     CHECK (activity IS NULL OR activity IN ('still','walking','running','cycling','vehicle','unknown')),
  battery_pct smallint CHECK (battery_pct IS NULL OR battery_pct BETWEEN 0 AND 100),
  recorded_at timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE incidents (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind             text NOT NULL CHECK (kind IN ('vehicle_crash','pedestrian_impact','fall','sos')),
  status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active','resolved','cancelled')),
  detected_at      timestamptz NOT NULL,
  geom             geography(Point,4326),
  details          jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object'),
  share_token_hash bytea NOT NULL UNIQUE CHECK (length(share_token_hash) = 32),  -- SHA-256 only
  share_expires_at timestamptz NOT NULL,
  resolved_at      timestamptz,
  resolved_by      uuid REFERENCES users(id) ON DELETE SET NULL,
  resolution       text CHECK (resolution IN ('user_ok','false_alarm','handled')),
  client_key       text CHECK (client_key IS NULL OR length(client_key) BETWEEN 1 AND 64),  -- Idempotency-Key
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, client_key),
  CHECK ((status = 'active' AND resolved_at IS NULL AND resolution IS NULL)
      OR (status <> 'active' AND resolved_at IS NOT NULL AND resolution IS NOT NULL))
);
CREATE INDEX incidents_user_status_idx ON incidents (user_id, status);
CREATE INDEX incidents_status_idx ON incidents (status, share_expires_at);

CREATE TABLE incident_trail (
  incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  recorded_at timestamptz NOT NULL,
  geom        geography(Point,4326) NOT NULL,
  accuracy_m  real CHECK (accuracy_m IS NULL OR accuracy_m >= 0),
  landmark    text CHECK (landmark IS NULL OR length(landmark) BETWEEN 1 AND 200),
  PRIMARY KEY (incident_id, recorded_at)
);

CREATE TABLE incident_acks (
  incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  state       text NOT NULL CHECK (state IN ('seen','on_my_way')),
  at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (incident_id, user_id)
);
CREATE INDEX incident_acks_user_idx ON incident_acks (user_id);

-- ===================== privileges (least privilege; never TRUNCATE/DDL for the app) =====================
GRANT SELECT, INSERT ON users TO eldering_app;
GRANT UPDATE (password_hash, display_name) ON users TO eldering_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON families, family_members, family_invites, refresh_tokens,
  share_grants, locations_latest TO eldering_app;
GRANT SELECT, INSERT, UPDATE ON user_settings, incident_acks TO eldering_app;
GRANT SELECT, INSERT ON incidents, incident_trail TO eldering_app;
-- Incidents are append-only for the app except the resolution and the share token (idempotent retry).
GRANT UPDATE (status, resolved_at, resolved_by, resolution, share_token_hash, share_expires_at) ON incidents TO eldering_app;

-- What the SECURITY DEFINER helpers read; DELETE only for retention and grant pruning.
GRANT SELECT ON family_members, share_grants, user_settings, locations_latest, incidents, incident_trail TO eldering_definer;
GRANT DELETE ON incidents, share_grants TO eldering_definer;

-- ===================== RLS helpers (owned by eldering_definer: NOLOGIN, BYPASSRLS) =====================
SET LOCAL ROLE eldering_definer;

-- Caller identity (SECURITY INVOKER). Fail-closed: unset or '' -> NULL -> rows hidden.
-- '' matters: after set_config(..., true) the placeholder GUC survives on the pooled
-- session with value '' (not NULL), and ''::uuid would raise instead of hiding rows.
CREATE FUNCTION rls.uid() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

-- Internal: do a and b (distinct) share at least one family?
CREATE FUNCTION rls.co_members(a uuid, b uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT a IS NOT NULL AND b IS NOT NULL AND a <> b AND EXISTS (
      SELECT 1 FROM public.family_members x JOIN public.family_members y USING (family_id)
      WHERE x.user_id = a AND y.user_id = b)
  $$;

-- Caller-relative form exposed to the app: only reveals the caller's own co-membership.
CREATE FUNCTION rls.co_member(other uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$ SELECT rls.co_members(rls.uid(), other) $$;

-- Internal: grant level sharer -> viewer. No row = 'emergency' (SPEC default for co-members),
-- computed rather than inserted so only the sharer ever writes their own rows.
CREATE FUNCTION rls.grant_level(sharer uuid, viewer uuid) RETURNS text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT coalesce((SELECT g.level FROM public.share_grants g
                     WHERE g.sharer_id = sharer AND g.viewer_id = viewer), 'emergency')
  $$;

-- Internal: sharing_state = 'on', or a pause that has already ended.
CREATE FUNCTION rls.sharing_on(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT coalesce((SELECT s.sharing_state = 'on'
                            OR (s.sharing_state = 'paused' AND s.paused_until IS NOT NULL AND s.paused_until <= now())
                     FROM public.user_settings s WHERE s.user_id = sharer), false)
  $$;

-- SPEC: V sees S's live location iff V<>S share a family AND grant(S->V)='live' AND sharing on.
CREATE FUNCTION rls.can_see_location(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT rls.co_members(sharer, rls.uid())
       AND rls.grant_level(sharer, rls.uid()) = 'live'
       AND rls.sharing_on(sharer)
  $$;

-- SPEC: V sees S's incidents/trail iff co-members AND grant(S->V) in (live, emergency)
-- AND (sharing on OR always_alert_on_accident). Grant 'off' => never.
CREATE FUNCTION rls.can_see_incidents(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT rls.co_members(sharer, rls.uid())
       AND rls.grant_level(sharer, rls.uid()) IN ('live','emergency')
       AND (rls.sharing_on(sharer)
            OR coalesce((SELECT s.always_alert_on_accident FROM public.user_settings s
                         WHERE s.user_id = sharer), true))
  $$;

-- Map status of S as seen by the caller: 'live' | 'paused' | 'hidden'. 'paused' is only
-- revealed to viewers who would otherwise see the live location.
CREATE FUNCTION rls.location_status(sharer uuid) RETURNS text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT CASE
      WHEN NOT rls.co_members(sharer, rls.uid()) OR rls.grant_level(sharer, rls.uid()) <> 'live' THEN 'hidden'
      WHEN rls.sharing_on(sharer) THEN 'live'
      WHEN EXISTS (SELECT 1 FROM public.user_settings s
                   WHERE s.user_id = sharer AND s.sharing_state = 'paused') THEN 'paused'
      ELSE 'hidden'
    END
  $$;

-- Public share link: token hash -> incident while unexpired. Location only while active.
CREATE FUNCTION rls.shared_incident(token_hash bytea)
  RETURNS TABLE (id uuid, kind text, status text, detected_at timestamptz,
                 lat double precision, lng double precision, share_expires_at timestamptz,
                 resolved_at timestamptz, resolution text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT i.id, i.kind, i.status, i.detected_at,
           CASE WHEN i.status = 'active' THEN public.st_y(i.geom::public.geometry) END,
           CASE WHEN i.status = 'active' THEN public.st_x(i.geom::public.geometry) END,
           i.share_expires_at, i.resolved_at, i.resolution
    FROM public.incidents i
    WHERE i.share_token_hash = token_hash AND i.share_expires_at > now()
  $$;

CREATE FUNCTION rls.shared_trail(token_hash bytea)
  RETURNS TABLE (recorded_at timestamptz, lat double precision, lng double precision,
                 accuracy_m real, landmark text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT t.recorded_at, public.st_y(t.geom::public.geometry), public.st_x(t.geom::public.geometry),
           t.accuracy_m, t.landmark
    FROM public.incidents i JOIN public.incident_trail t ON t.incident_id = i.id
    WHERE i.share_token_hash = token_hash AND i.share_expires_at > now() AND i.status = 'active'
    ORDER BY t.recorded_at
  $$;

-- Retention (hourly): hard-delete incidents 72 h after resolution, or 72 h after
-- share expiry for incidents never resolved. Trail + acks cascade.
CREATE FUNCTION rls.purge_expired_incidents() RETURNS bigint
  LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    WITH d AS (
      DELETE FROM public.incidents
      WHERE coalesce(resolved_at, share_expires_at) < now() - interval '72 hours'
      RETURNING 1)
    SELECT count(*) FROM d
  $$;

-- When a membership ends, drop grants between pairs who no longer share any family,
-- so a later re-join starts again from the SPEC default ('emergency').
CREATE FUNCTION rls.prune_grants_on_leave() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
  BEGIN
    DELETE FROM public.share_grants g
    WHERE (g.sharer_id = OLD.user_id OR g.viewer_id = OLD.user_id)
      AND NOT rls.co_members(g.sharer_id, g.viewer_id);
    RETURN NULL;
  END
  $$;

-- Defense in depth for the inc_upd policy: a viewer (not the owner) may only move an
-- active incident to resolved/'handled' as themselves; nobody may rewrite identity columns.
CREATE FUNCTION rls.guard_incident_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
  AS $$
  DECLARE
    me uuid := rls.uid();
  BEGIN
    IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.kind <> OLD.kind
       OR NEW.detected_at <> OLD.detected_at OR NEW.client_key IS DISTINCT FROM OLD.client_key THEN
      RAISE EXCEPTION 'incident identity columns are immutable' USING ERRCODE = '42501';
    END IF;
    IF me IS NOT NULL AND me <> OLD.user_id THEN
      IF NOT (OLD.status = 'active' AND NEW.status = 'resolved' AND NEW.resolution = 'handled'
              AND NEW.resolved_by = me
              AND NEW.share_token_hash = OLD.share_token_hash
              AND NEW.share_expires_at = OLD.share_expires_at
              AND NEW.geom IS NOT DISTINCT FROM OLD.geom
              AND NEW.details = OLD.details) THEN
        RAISE EXCEPTION 'viewers may only resolve an active incident as handled' USING ERRCODE = '42501';
      END IF;
    END IF;
    RETURN NEW;
  END
  $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA rls FROM PUBLIC;
-- The app only gets caller-relative helpers (they reveal nothing beyond what the caller may see),
-- the anonymous share-link lookups, and the retention purge.
GRANT EXECUTE ON FUNCTION rls.uid(), rls.co_member(uuid), rls.can_see_location(uuid),
  rls.can_see_incidents(uuid), rls.location_status(uuid), rls.shared_incident(bytea),
  rls.shared_trail(bytea), rls.purge_expired_incidents() TO eldering_app;
-- The owner is subject to FORCEd RLS, so it needs the policy helpers too, plus
-- EXECUTE on the trigger functions to attach them below.
GRANT EXECUTE ON FUNCTION rls.uid(), rls.co_member(uuid), rls.can_see_location(uuid), rls.can_see_incidents(uuid),
  rls.prune_grants_on_leave(), rls.guard_incident_update() TO eldering_owner;
RESET ROLE;

CREATE TRIGGER family_members_prune_grants
  AFTER DELETE ON family_members
  FOR EACH ROW EXECUTE FUNCTION rls.prune_grants_on_leave();

CREATE TRIGGER incidents_guard_update
  BEFORE UPDATE ON incidents
  FOR EACH ROW EXECUTE FUNCTION rls.guard_incident_update();

-- ===================== enable + FORCE (owner is filtered too) =====================
ALTER TABLE share_grants     ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE user_settings    ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE locations_latest ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incidents        ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_trail   ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_acks    ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;

-- ===================== policies ((SELECT rls.uid()) => one InitPlan per statement) =====================
-- share_grants: both parties read; only the sharer writes, and only towards a co-member.
CREATE POLICY sg_read ON share_grants FOR SELECT
  USING (sharer_id = (SELECT rls.uid()) OR viewer_id = (SELECT rls.uid()));
CREATE POLICY sg_ins ON share_grants FOR INSERT
  WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_member(viewer_id));
CREATE POLICY sg_upd ON share_grants FOR UPDATE
  USING (sharer_id = (SELECT rls.uid()))
  WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_member(viewer_id));
CREATE POLICY sg_del ON share_grants FOR DELETE
  USING (sharer_id = (SELECT rls.uid()));

-- user_settings: self only.
CREATE POLICY us_self ON user_settings FOR ALL
  USING (user_id = (SELECT rls.uid())) WITH CHECK (user_id = (SELECT rls.uid()));

-- locations_latest: self writes; self or a permitted viewer reads.
CREATE POLICY ll_read ON locations_latest FOR SELECT
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_location(user_id));
CREATE POLICY ll_ins ON locations_latest FOR INSERT WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY ll_upd ON locations_latest FOR UPDATE
  USING (user_id = (SELECT rls.uid())) WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY ll_del ON locations_latest FOR DELETE USING (user_id = (SELECT rls.uid()));

-- incidents: owner inserts; owner or permitted viewer reads and updates
-- (viewer: resolve as 'handled' only, enforced by column grants + guard trigger + handler).
CREATE POLICY inc_read ON incidents FOR SELECT
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id));
CREATE POLICY inc_ins ON incidents FOR INSERT WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY inc_upd ON incidents FOR UPDATE
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id))
  WITH CHECK (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id));

-- incident_trail: visible iff the parent incident is visible (the sub-select is itself
-- RLS-filtered); only the owner appends.
CREATE POLICY tr_read ON incident_trail FOR SELECT
  USING (EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));
CREATE POLICY tr_ins ON incident_trail FOR INSERT
  WITH CHECK (EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));

-- incident_acks: a viewer acks a visible incident that is not their own; the incident
-- owner sees all acks on it.
CREATE POLICY ack_read ON incident_acks FOR SELECT
  USING (user_id = (SELECT rls.uid())
         OR EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));
CREATE POLICY ack_ins ON incident_acks FOR INSERT
  WITH CHECK (user_id = (SELECT rls.uid())
              AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id <> (SELECT rls.uid())));
CREATE POLICY ack_upd ON incident_acks FOR UPDATE
  USING (user_id = (SELECT rls.uid()))
  WITH CHECK (user_id = (SELECT rls.uid())
              AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id <> (SELECT rls.uid())));
