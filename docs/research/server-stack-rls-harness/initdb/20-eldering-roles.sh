#!/bin/sh
# Runs ONCE, as the bootstrap superuser, on an empty data dir (docker-entrypoint-initdb.d).
# Named 20-* so it sorts after the image's own 10_postgis.sh.
set -eu
: "${ELDERING_OWNER_PASSWORD:?}" "${ELDERING_APP_PASSWORD:?}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_pw="$ELDERING_OWNER_PASSWORD" -v app_pw="$ELDERING_APP_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
-- 1. owner: owns tables, runs migrations. Subject to RLS on FORCEd tables.
CREATE ROLE eldering_owner LOGIN PASSWORD :'owner_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
-- 2. app: what the API connects as. Never owner, never BYPASSRLS.
CREATE ROLE eldering_app LOGIN PASSWORD :'app_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20;
-- 3. definer: owns the few SECURITY DEFINER helpers that must read across users.
CREATE ROLE eldering_definer NOLOGIN NOSUPERUSER BYPASSRLS;

GRANT eldering_definer TO eldering_owner;            -- lets migrations SET ROLE to create helpers

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO eldering_owner, eldering_app;
GRANT USAGE, CREATE ON SCHEMA public TO eldering_owner;
GRANT USAGE ON SCHEMA public TO eldering_app, eldering_definer;

CREATE SCHEMA rls AUTHORIZATION eldering_definer;     -- helpers live here
GRANT USAGE ON SCHEMA rls TO eldering_app, eldering_owner;

CREATE EXTENSION IF NOT EXISTS citext;                 -- postgis is created by the image's own init script

ALTER ROLE eldering_app SET statement_timeout = '5s';
ALTER ROLE eldering_app SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE eldering_app SET lock_timeout = '2s';
SQL
