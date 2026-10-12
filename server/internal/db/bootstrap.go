package db

import (
	"context"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// Role names. The API connects as RoleApp only.
const (
	RoleOwner   = "eldering_owner"   // owns tables, runs migrations; subject to FORCEd RLS
	RoleApp     = "eldering_app"     // the API: not owner, NOBYPASSRLS
	RoleDefiner = "eldering_definer" // NOLOGIN BYPASSRLS; owns the SECURITY DEFINER helpers in schema rls
)

// bootstrapSQL is idempotent and runs as a superuser inside one transaction.
// Every statement either checks for existence first or is a GRANT/ALTER that
// converges to the same state, so it is safe to run on every deploy.
const bootstrapSQL = `
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eldering_owner') THEN CREATE ROLE eldering_owner; END IF;
EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eldering_app') THEN CREATE ROLE eldering_app; END IF;
EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eldering_definer') THEN CREATE ROLE eldering_definer; END IF;
EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
END $$;

-- Converge attributes even if a role pre-existed with different ones.
ALTER ROLE eldering_owner   WITH LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT;
ALTER ROLE eldering_app     WITH LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20;
ALTER ROLE eldering_definer WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;

-- Migrations SET ROLE to the definer to create helpers; attributes such as
-- BYPASSRLS are never inherited, and INHERIT FALSE keeps privileges separate too.
GRANT eldering_definer TO eldering_owner WITH INHERIT FALSE, SET TRUE;

ALTER ROLE eldering_app SET statement_timeout = '5s';
ALTER ROLE eldering_app SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE eldering_app SET lock_timeout = '2s';

DO $$ BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO eldering_owner, eldering_app', current_database());
END $$;

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS citext  WITH SCHEMA public;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO eldering_owner;
GRANT USAGE ON SCHEMA public TO eldering_app, eldering_definer;

CREATE SCHEMA IF NOT EXISTS rls AUTHORIZATION eldering_definer;
ALTER SCHEMA rls OWNER TO eldering_definer;
REVOKE ALL ON SCHEMA rls FROM PUBLIC;
GRANT USAGE ON SCHEMA rls TO eldering_app, eldering_owner;
`

// Bootstrap idempotently creates the three roles, sets their passwords, and
// applies database/schema privileges. It must run as a superuser connected to
// the target database. Passwords are sent as SCRAM-SHA-256 verifiers, so the
// plaintext never appears in a SQL statement (or in a server log line).
func Bootstrap(ctx context.Context, conn *pgx.Conn, ownerPassword, appPassword string) error {
	return pgx.BeginFunc(ctx, conn, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, bootstrapSQL, pgx.QueryExecModeSimpleProtocol); err != nil {
			return fmt.Errorf("bootstrap roles: %w", err)
		}
		for role, pw := range map[string]string{RoleOwner: ownerPassword, RoleApp: appPassword} {
			verifier, err := ScramVerifier(pw)
			if err != nil {
				return err
			}
			var stmt string
			// format(%I, %L) lets the server do the quoting.
			if err := tx.QueryRow(ctx, `SELECT format('ALTER ROLE %I PASSWORD %L', $1::text, $2::text)`, role, verifier).Scan(&stmt); err != nil {
				return fmt.Errorf("build password statement: %w", err)
			}
			if _, err := tx.Exec(ctx, stmt, pgx.QueryExecModeSimpleProtocol); err != nil {
				return fmt.Errorf("set %s password: %w", role, err)
			}
		}
		return nil
	})
}

// ScramVerifier returns the PostgreSQL SCRAM-SHA-256 verifier for password
// (RFC 5802/7677 as stored in pg_authid.rolpassword). Callers pass ASCII
// secrets (hex), for which SASLprep is the identity.
func ScramVerifier(password string) (string, error) {
	if password == "" {
		return "", fmt.Errorf("empty role password")
	}
	const iterations = 4096
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	salted, err := pbkdf2.Key(sha256.New, password, salt, iterations, sha256.Size)
	if err != nil {
		return "", err
	}
	mac := func(key []byte, msg string) []byte {
		h := hmac.New(sha256.New, key)
		h.Write([]byte(msg))
		return h.Sum(nil)
	}
	clientKey := mac(salted, "Client Key")
	storedKey := sha256.Sum256(clientKey)
	serverKey := mac(salted, "Server Key")
	b64 := base64.StdEncoding.EncodeToString
	return fmt.Sprintf("SCRAM-SHA-256$%d:%s$%s:%s", iterations, b64(salt), b64(storedKey[:]), b64(serverKey)), nil
}
