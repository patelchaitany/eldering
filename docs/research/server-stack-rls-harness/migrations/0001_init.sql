-- Runs as eldering_owner (member of eldering_definer). One transaction.

-- ---------- identity / families (handler-enforced, no RLS per SPEC) ----------
CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         citext NOT NULL UNIQUE,
  password_hash text   NOT NULL,
  display_name  text   NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE families (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name       text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  created_by uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE family_members (
  family_id uuid NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  user_id   uuid NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
  role      text NOT NULL CHECK (role IN ('admin','member')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (family_id, user_id)
);
CREATE INDEX family_members_user_idx ON family_members (user_id);

-- ---------- RLS-protected ----------
CREATE TABLE share_grants (
  sharer_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  viewer_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  level      text NOT NULL CHECK (level IN ('live','emergency','off')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (sharer_id, viewer_id),
  CHECK (sharer_id <> viewer_id)
);
CREATE TABLE user_settings (
  user_id                  uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  sharing_state            text NOT NULL DEFAULT 'on' CHECK (sharing_state IN ('on','paused','off')),
  paused_until             timestamptz,
  always_alert_on_accident boolean NOT NULL DEFAULT true
);
CREATE TABLE locations_latest (
  user_id     uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  geom        geography(Point,4326) NOT NULL,
  accuracy_m  real, speed_mps real, bearing_deg real, activity text, battery_pct smallint,
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
  details          jsonb NOT NULL DEFAULT '{}',
  share_token_hash bytea UNIQUE CHECK (share_token_hash IS NULL OR length(share_token_hash) = 32),
  share_expires_at timestamptz,
  resolved_at      timestamptz,
  resolved_by      uuid REFERENCES users(id) ON DELETE SET NULL,
  resolution       text CHECK (resolution IN ('user_ok','false_alarm','handled'))
);
CREATE INDEX incidents_user_status_idx ON incidents (user_id, status);
CREATE TABLE incident_trail (
  incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  recorded_at timestamptz NOT NULL,
  geom        geography(Point,4326) NOT NULL,
  accuracy_m  real,
  landmark    text,
  PRIMARY KEY (incident_id, recorded_at)
);
CREATE TABLE incident_acks (
  incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  state       text NOT NULL CHECK (state IN ('seen','on_my_way')),
  at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (incident_id, user_id)
);

-- ---------- privileges (no DDL, no TRUNCATE for the app) ----------
GRANT SELECT, INSERT, UPDATE, DELETE ON users, families, family_members,
  share_grants, user_settings, locations_latest, incidents, incident_trail, incident_acks
  TO eldering_app;
-- definer reads what policy helpers need; DELETE only for the retention purge
GRANT SELECT ON family_members, share_grants, user_settings, locations_latest, incident_trail TO eldering_definer;
GRANT SELECT, DELETE ON incidents TO eldering_definer;

-- ---------- RLS helpers (schema rls is owned by eldering_definer, a NOLOGIN BYPASSRLS role) ----------
SET LOCAL ROLE eldering_definer;

-- Caller identity (SECURITY INVOKER). Fail-closed: unset or '' -> NULL -> comparisons NULL -> row hidden.
-- '' matters: after any SET LOCAL/set_config(...,true) the placeholder GUC survives on the pooled
-- session with value '' (not NULL), and ''::uuid would raise instead of hiding rows.
CREATE FUNCTION rls.uid() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION rls.co_members(a uuid, b uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT a IS NOT NULL AND b IS NOT NULL AND a <> b AND EXISTS (
      SELECT 1 FROM public.family_members x JOIN public.family_members y USING (family_id)
      WHERE x.user_id = a AND y.user_id = b)
  $$;

-- grant level S->V; absent row = 'emergency' (SPEC default for co-members, avoids cross-user writes)
CREATE FUNCTION rls.grant_level(sharer uuid, viewer uuid) RETURNS text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT coalesce((SELECT g.level FROM public.share_grants g
                     WHERE g.sharer_id = sharer AND g.viewer_id = viewer), 'emergency')
  $$;

CREATE FUNCTION rls.sharing_on(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT coalesce((SELECT s.sharing_state = 'on'
                            OR (s.sharing_state = 'paused' AND s.paused_until IS NOT NULL AND s.paused_until <= now())
                     FROM public.user_settings s WHERE s.user_id = sharer), false)
  $$;

-- V (= rls.uid()) may see S's live location
CREATE FUNCTION rls.can_see_location(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT rls.co_members(sharer, rls.uid())
       AND rls.grant_level(sharer, rls.uid()) = 'live'
       AND rls.sharing_on(sharer)
  $$;

-- V (= rls.uid()) may see S's incidents + trail
CREATE FUNCTION rls.can_see_incidents(sharer uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT rls.co_members(sharer, rls.uid())
       AND rls.grant_level(sharer, rls.uid()) IN ('live','emergency')
       AND (rls.sharing_on(sharer)
            OR coalesce((SELECT s.always_alert_on_accident FROM public.user_settings s
                         WHERE s.user_id = sharer), true))
  $$;

-- Public share link: token hash -> incident (only while unexpired). Anonymous, so no uid involved.
CREATE FUNCTION rls.shared_incident(token_hash bytea)
  RETURNS TABLE (id uuid, user_id uuid, kind text, status text, detected_at timestamptz,
                 lat double precision, lng double precision, share_expires_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    SELECT i.id, i.user_id, i.kind, i.status, i.detected_at,
           public.st_y(i.geom::public.geometry), public.st_x(i.geom::public.geometry), i.share_expires_at
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
    WHERE i.share_token_hash = token_hash AND i.share_expires_at > now()
    ORDER BY t.recorded_at
  $$;

-- Retention (hourly): hard-delete incidents 72 h after resolution / share expiry. Trail+acks cascade.
CREATE FUNCTION rls.purge_expired_incidents() RETURNS bigint
  LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
  AS $$
    WITH d AS (
      DELETE FROM public.incidents
      WHERE coalesce(resolved_at, share_expires_at) < now() - interval '72 hours'
      RETURNING 1)
    SELECT count(*) FROM d
  $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA rls FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rls TO eldering_app;
RESET ROLE;

-- ---------- enable + FORCE ----------
ALTER TABLE share_grants     ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE user_settings    ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE locations_latest ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incidents        ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_trail   ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_acks    ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;

-- ---------- policies ((SELECT rls.uid()) => evaluated once per statement as an InitPlan) ----------
-- share_grants: both parties read; only the sharer writes.
CREATE POLICY sg_read  ON share_grants FOR SELECT
  USING (sharer_id = (SELECT rls.uid()) OR viewer_id = (SELECT rls.uid()));
CREATE POLICY sg_ins   ON share_grants FOR INSERT
  WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_members(sharer_id, viewer_id));
CREATE POLICY sg_upd   ON share_grants FOR UPDATE
  USING (sharer_id = (SELECT rls.uid()))
  WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_members(sharer_id, viewer_id));
CREATE POLICY sg_del   ON share_grants FOR DELETE USING (sharer_id = (SELECT rls.uid()));

-- user_settings: self only.
CREATE POLICY us_self ON user_settings FOR ALL
  USING (user_id = (SELECT rls.uid())) WITH CHECK (user_id = (SELECT rls.uid()));

-- locations_latest: self writes; self or permitted viewer reads.
CREATE POLICY ll_read ON locations_latest FOR SELECT
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_location(user_id));
CREATE POLICY ll_ins  ON locations_latest FOR INSERT WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY ll_upd  ON locations_latest FOR UPDATE
  USING (user_id = (SELECT rls.uid())) WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY ll_del  ON locations_latest FOR DELETE USING (user_id = (SELECT rls.uid()));

-- incidents: owner full; permitted viewers read + update (resolve 'handled'; handler limits columns).
CREATE POLICY inc_read ON incidents FOR SELECT
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id));
CREATE POLICY inc_ins  ON incidents FOR INSERT WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY inc_upd  ON incidents FOR UPDATE
  USING (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id))
  WITH CHECK (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id));

-- incident_trail: visible iff parent incident visible (sub-select is itself RLS-filtered); owner appends.
CREATE POLICY tr_read ON incident_trail FOR SELECT
  USING (EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));
CREATE POLICY tr_ins  ON incident_trail FOR INSERT
  WITH CHECK (EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));

-- incident_acks: own ack on a visible incident; incident owner sees all acks on theirs.
CREATE POLICY ack_read ON incident_acks FOR SELECT
  USING (user_id = (SELECT rls.uid())
         OR EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));
CREATE POLICY ack_write ON incident_acks FOR INSERT
  WITH CHECK (user_id = (SELECT rls.uid()) AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));
CREATE POLICY ack_upd ON incident_acks FOR UPDATE
  USING (user_id = (SELECT rls.uid()))
  WITH CHECK (user_id = (SELECT rls.uid()) AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));
