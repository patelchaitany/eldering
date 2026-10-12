# Server stack: Go API, Postgres RLS, deployment

Research for `server/` per SPEC.md. Checked 2026-10-12/13 against primary sources: proxy.golang.org, go.dev/dl, the Docker Hub API with `docker buildx imagetools inspect`, the npm registry and the cdnjs API, postgresql.org docs, OWASP, docs.docker.com, openfreemap.org, sslip.io, and the GitHub release API. I also inspected the target server over SSH (read-only).

Status legend: **[V]** means I ran it or read it on the primary source. **[D]** means it comes from the primary docs but I did not test it here. **[U]** means unverified or inferred.

Executable proof of the RLS design: `docs/research/server-stack-rls-harness/` (`./run.sh`). It starts a throwaway container from the exact production PostGIS image, applies the role and migration SQL from this doc, and runs 4 Go tests with pgxpool and `MaxConns=1`. **All pass [V].** It never touches the server.

---

## 0. Decisions (TL;DR)

| Topic | Choice |
|---|---|
| Go | `go 1.26.0` in go.mod (local toolchain is 1.26.0). Build release binaries with **go1.26.9** (golang:1.26.9-alpine3.24), the latest 1.26 patch, so stdlib security fixes ship. |
| Router | `github.com/go-chi/chi/v5` **v5.3.2**. Use `middleware.ClientIPFromHeader("X-Real-IP")`; `RealIP` is now **deprecated** (spoofable). |
| DB driver | `github.com/jackc/pgx/v5` **v5.11.0** + `pgxpool`. Identity is set per request with `SELECT set_config('app.user_id', $1, true)` inside `pgx.BeginTxFunc`. |
| JWT | `github.com/golang-jwt/jwt/v5` **v5.3.1**, HS256, pinned alg, iss/aud/exp required. |
| Passwords | `golang.org/x/crypto/argon2` (x/crypto **v0.58.0**), argon2id **m=19456 KiB, t=2, p=1**, about 114 ms on the target VM. Cap concurrent hashes at 2. |
| Rate limit | `golang.org/x/time/rate` **v0.16.0**, per-IP token buckets with idle eviction (code in §5). |
| Migrations | `embed.FS` with a ~50-line in-house migrator (advisory lock, one tx per file), run as the **owner** role through an `api migrate` one-shot service. goose v3.28.0 is an alternative (§7). |
| DB roles | `eldering_owner` (owns tables, runs migrations, subject to FORCE RLS). `eldering_app` (API: not the owner, NOBYPASSRLS). `eldering_definer` (NOLOGIN **BYPASSRLS**, owns the SECURITY DEFINER helpers in schema `rls`). |
| DB image | **`postgis/postgis:16-3.5-alpine`** (PG **16.15**, PostGIS **3.5.7**, Alpine 3.24, amd64-only). **Not** `16-3.5`: that Debian/bullseye tag is stuck on PG **16.9** and PostGIS 3.5.2 [V]. |
| Runtime image | `gcr.io/distroless/static-debian13:nonroot` (uid 65532), static `CGO_ENABLED=0` binary, about 3.4 MB image [V]. |
| Ship | `docker buildx build --platform linux/amd64 --load`, then `docker save \| gzip \| ssh … 'gunzip \| docker load'`. No registry. |
| Proxy/TLS | New nginx site file plus `certbot --nginx` (certbot with its nginx plugin is already installed on the box). **Do not add `http2`** (§10). |
| Share page | **MapLibre GL JS 6.11.2**, vendored and served same-origin. v6 is **ESM-only**, so use `<script type="module">`. Style is `https://tiles.openfreemap.org/styles/liberty`. A strict CSP is verified rendering in a browser [V]. |

---

## 1. Versions (verified 2026-10-12)

| Artifact | Pin | Released | Notes |
|---|---|---|---|
| Go toolchain | **go1.26.9** | 2026-10 (golang image 10-10) | Latest stable overall is go1.27.2; SPEC pins 1.26 [V go.dev/dl JSON]. |
| chi | **v5.3.2** | 2026-08-20 | v5.4.0 shipped 2026-10-12 (same day); wait ≥2 weeks before adopting. v5.3.0 added `ClientIPFrom*` and deprecated `RealIP`. go directive 1.24 [V proxy, GH releases]. |
| pgx | **v5.11.0** | 2026-09-07 | go 1.25.0 min. `BeforeAcquire` is deprecated in favor of `PrepareConn` [V go doc]. |
| golang-jwt | **v5.3.1** | 2026-01-28 | [V] |
| x/crypto | **v0.58.0** | 2026-10-09 | **requires go ≥ 1.26.0** (v0.57.0 2026-09-08 if needed) [V .mod]. |
| x/time | **v0.16.0** | 2026-08-19 | [V] |
| google/uuid | v1.6.0 | 2024-01 | Works with pgx v5 (scan/encode) [V harness]. |
| goose (optional) | v3.28.0 | 2026-09-02 | v3.29.0 is 2026-10-11 (too fresh) [V]. |
| go-chi/httprate (alt.) | v0.16.0 | 2026-06-29 | v0.16.1 is 2026-10-08 [V]. |
| PostgreSQL 16 | **16.15** current minor | EOL 2028-11-09 | [V postgresql.org/support/versioning] |
| PostGIS image | `postgis/postgis:16-3.5-alpine@sha256:47e961a569fd52ff31f0fe205ed91eeab17d9f5fff6722e6d7ea6b588748b293` | 2026-08-31 | amd64 manifest `sha256:682bb307fed2165594aa60883dc142c757fa70894b1802c0ef0814e02a8c1844`. **amd64 only**: no arm64, so it runs under Rosetta on the Mac (fine for tests) [V]. |
| Go build image | `golang:1.26.9-alpine3.24@sha256:3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade` | 2026-10-10 | multi-arch [V]. |
| Runtime image | `gcr.io/distroless/static-debian13:nonroot@sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3` | | [V] |
| MapLibre GL JS | **6.11.2** | 2026-09-24 | Latest is 6.14.0 (2026-10-11). v6.0.0 (2026-07-22) went **ESM-only**, dropped UMD and the CSP bundle, and **requires WebGL2** [V npm, CHANGELOG]. |

```go
// server/go.mod
module github.com/eldering/safety/server   // any path; not published

go 1.26.0

require (
	github.com/go-chi/chi/v5 v5.3.2
	github.com/golang-jwt/jwt/v5 v5.3.1
	github.com/google/uuid v1.6.0
	github.com/jackc/pgx/v5 v5.11.0
	golang.org/x/crypto v0.58.0
	golang.org/x/time v0.16.0
)
```
Run `go mod tidy` after adding imports. Optionally run `go run golang.org/x/vuln/cmd/govulncheck@latest ./...` before a deploy.

Alternative router: Go ≥1.22 `http.ServeMux` patterns (`mux.HandleFunc("GET /v1/incidents/{id}", h)`, `r.PathValue("id")`) cover routing fully. I chose chi only for `Route`/`Group` middleware scoping and the safe ClientIP middleware. Both are fine.

---

## 2. Target server facts (read-only SSH, 2026-10-12) [V]

- Small x86_64 VM: 2 vCPU, **~1 GiB RAM** (roughly 0.5 GiB available), swap enabled. Exact host details are kept privately (see `SPEC.local.md`, not committed).
- Docker + Compose v2 present; other workloads on the box are small.
- nginx (pre-1.25) with certbot and its `nginx` plugin; `certbot.timer` handles renewal, so the `certbot --nginx` pattern works on this box.
- Let's Encrypt's `options-ssl-nginx.conf` sets TLSv1.2/1.3 with Mozilla-intermediate ciphers. No site uses `http2`.
- `~/eldering-safety` does not exist yet.

---

## 3. HTTP layer

```go
r := chi.NewRouter()
r.Use(middleware.ClientIPFromHeader("X-Real-IP")) // nginx overwrites X-Real-IP; API binds 127.0.0.1 only
r.Use(middleware.RequestID)
r.Use(middleware.Recoverer)
r.Use(middleware.RequestSize(1 << 20))            // SPEC 1 MB; wraps http.MaxBytesReader
r.Use(securityHeaders)                            // nosniff, Referrer-Policy no-referrer, X-Frame-Options DENY,
                                                  // Cache-Control no-store, CSP "default-src 'none'; frame-ancestors 'none'"
r.Get("/healthz", ...)
r.Route("/v1", func(r chi.Router) {
	r.Group(func(r chi.Router) { r.Use(authLimit.Middleware(clientIP)); r.Post("/auth/login", ...) /* register, refresh */ })
	r.Group(func(r chi.Router) { r.Use(requireAccessJWT); r.Get("/incidents/{id}", ...) /* chi.URLParam(r,"id") */ })
})
r.Get("/s/{token}", sharePage); r.Get("/s/{token}/data", shareData)   // own CSP, see §11
// clientIP := func(r *http.Request) string { return middleware.GetClientIP(r.Context()) }
```
- **Why not `RealIP`**: it trusts `True-Client-IP`/`X-Real-IP`/leftmost XFF from anyone and mutates `RemoteAddr`. It is deprecated in chi v5.3 (GHSA-3fxj-6jh8-hvhx etc.) [V source]. `ClientIPFromHeader` is safe only because nginx **overwrites** the header (§10) and the API port is published on 127.0.0.1 only.
- `RequestSize` errors only when the body is read. Decode with `json.Decoder` + `DisallowUnknownFields`, map `*http.MaxBytesError` to **413**, and reject trailing data.
- `http.Server{ReadHeaderTimeout: 5s, ReadTimeout: 15s, WriteTimeout: 30s, IdleTimeout: 90s, MaxHeaderBytes: 16<<10}`. On `signal.NotifyContext(SIGINT, SIGTERM)`, call `Shutdown` with a 15 s timeout, then `pool.Close()`.
- Healthcheck: distroless has no curl, so the binary needs a subcommand `eldering-api healthcheck` that GETs `http://127.0.0.1:8080/healthz` and exits 0 or 1.
- DB error to API error mapping (no cross-user existence leaks). Log the original error server-side:

| pg / pgx | HTTP |
|---|---|
| `pgx.ErrNoRows`, `42501` (RLS `WITH CHECK` failure: "new row violates row-level security policy") | 404 `not_found` |
| `23505` unique (email taken) | 409 |
| `23503` FK, `23514` check, `22P02` bad uuid text | 400 |
| `57014` statement_timeout | 503 |

  An RLS-filtered `UPDATE`/`DELETE` does **not** error. It affects **0 rows**, so check `CommandTag.RowsAffected()` and return 404.

The snippets in §3 and §5 compile and pass `go vet`. Their tests pass with `-race` (argon2 round-trip/rehash/malformed, JWT incl. `alg:none` and wrong aud rejection, limiter eviction, router per-IP 429) [V, scratch module].

---

## 4. Postgres identity with a pooled connection

```go
cfg, _ := pgxpool.ParseConfig(os.Getenv("DATABASE_URL")) // ...?sslmode=disable&pool_max_conns=8
cfg.MinConns = 1
cfg.MaxConnLifetime = 30 * time.Minute
cfg.MaxConnIdleTime = 5 * time.Minute
cfg.HealthCheckPeriod = time.Minute
cfg.ConnConfig.RuntimeParams["application_name"] = "eldering-api"
pool, err := pgxpool.NewWithConfig(ctx, cfg)

// Every authenticated DB access goes through this.
func WithUser(ctx context.Context, pool *pgxpool.Pool, uid uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.user_id', $1, true)`, uid.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}
// Anonymous (register/login/share page): pgx.BeginTxFunc without set_config, so rls.uid() IS NULL and RLS tables show nothing.
```
- `set_config(name, value, is_local=true)` is the parameterizable form of `SET LOCAL` [D docs]. `SET LOCAL app.user_id = $1` is **not** allowed because utility statements take no bind params. This matches SPEC's `SET LOCAL` semantics.
- `is_local=true` reverts at COMMIT/ROLLBACK, so identity never leaks to the next borrower of the connection [V harness, pool of 1]. Outside an explicit tx it lasts only for that one statement. That is why **every** request uses `BeginTxFunc`.
- **Pitfall [V]:** after the tx, the placeholder GUC **still exists with value `''`** on that session (not NULL). `current_setting('app.user_id', true)::uuid` then **raises** `invalid input syntax for type uuid`. Always use `nullif(current_setting('app.user_id', true), '')::uuid`; `rls.uid()` below does this.
- pgx `BeginTx`: "the context only affects the begin command … no auto-rollback on context cancellation". `BeginTxFunc` rolls back when `fn` errors [D go doc].
- Pool sizing: `pool_max_conns=8` against `max_connections=30`, leaving 3 superuser slots plus the migrate, psql and backup sessions. Also set `CONNECTION LIMIT 20` on the role.
- PostGIS without extra Go deps: write `ST_SetSRID(ST_MakePoint($lng,$lat),4326)::geography` and read back `ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lng`, with plain `float64` [V harness].

---

## 5. Auth primitives (tested code)

**argon2id**. OWASP minimums are "m=47104 (46 MiB), t=1, p=1" or **"m=19456 (19 MiB), t=2, p=1"** (both equal strength) [V OWASP]. The x/crypto doc's "second recommended option" (64 MiB, t=3, p=4) is too heavy for this VM.

Measured on the target VM (static linux/amd64 binary, run then deleted from /tmp) [V]:

| params | server (x86_64 VM) | M-series Mac |
|---|---|---|
| **m=19456,t=2,p=1** | **113.5 ms** | 25.8 ms |
| m=47104,t=1,p=1 | 163 ms | 32.8 ms |
| m=12288,t=3,p=1 | 80.5 ms | 22.0 ms |
| m=65536,t=3,p=4 | 604 ms | 38.4 ms |

```go
var DefaultArgon2 = Argon2Params{MemoryKiB: 19456, Time: 2, Threads: 1, SaltLen: 16, KeyLen: 32}
// Hash -> PHC string "$argon2id$v=19$m=19456,t=2,p=1$<b64 salt>$<b64 key>" (base64.RawStdEncoding)
key := argon2.IDKey([]byte(pw), salt, p.Time, p.MemoryKiB, p.Threads, p.KeyLen)
// Verify: parse PHC, REJECT absurd params (m>256 MiB, t>10) before hashing, subtle.ConstantTimeCompare,
// return needsRehash when stored params < current. Unknown email: still run a dummy Verify (timing).
// Wrap Hash/Verify in a semaphore (cap 2): each call allocates m KiB, so 2 x 19 MiB fits a 128 MiB container.
```

**JWT (access, 15 min)**:
```go
c := jwt.RegisteredClaims{Issuer: "eldering", Audience: jwt.ClaimStrings{"eldering-api"}, Subject: userID,
	IssuedAt: jwt.NewNumericDate(now), NotBefore: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(15*time.Minute))}
tok, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(secret) // secret = hex-decoded JWT_SECRET (32 bytes)

_, err := jwt.ParseWithClaims(tok, &c, func(*jwt.Token) (any, error) { return secret, nil },
	jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer("eldering"), jwt.WithAudience("eldering-api"),
	jwt.WithExpirationRequired(), jwt.WithIssuedAt(), jwt.WithLeeway(30*time.Second))
```

**Opaque tokens** (refresh tokens and incident share tokens): 32 bytes from `crypto/rand`, sent as `base64.RawURLEncoding` (43 chars). Store `sha256.Sum256([]byte(token))` (bytea, 32 bytes). Look up by the hash of the presented string, so malformed input never matches. Refresh rotation in one tx: `UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=$1 AND revoked_at IS NULL AND expires_at>now() RETURNING user_id`. If that returns no row but the hash exists and is revoked, treat it as reuse: revoke all of that user's tokens.

**Per-IP limiter** (x/time/rate):
```go
type IPLimiter struct { mu sync.Mutex; every rate.Limit; burst int; ttl time.Duration; max int; buckets map[string]*bucket }
type bucket struct { lim *rate.Limiter; seen time.Time }
// Allow(key): get-or-create bucket (if len>=max: evict idle>ttl; still full -> deny), seen=now, lim.AllowN(now,1)
// Janitor: ticker(1m) evicts idle buckets. Middleware: 429 + Retry-After + {"error":{"code":"rate_limited",...}}
authLimit := NewIPLimiter(rate.Every(6*time.Second), 5, 15*time.Minute, 10_000) // 10/min, burst 5 per IP
```
Also limit by account on `/auth/login`, e.g. a second limiter keyed `"login:"+lower(email)` at 5/15 min, so one IP pool can't brute-force one user.

---

## 6. Row-Level Security design (tested)

### 6.1 Rules from the docs that drive the design [V postgresql.org/docs/16]
- "Superusers and roles with the BYPASSRLS attribute always bypass the row security system." Table owners normally bypass it too, unless the table has `FORCE ROW LEVEL SECURITY`. **So the API role must not be the owner, superuser or BYPASSRLS.** FORCE additionally constrains the owner (migrations) [V harness: owner sees 0 rows].
- With RLS enabled and no policy for a command, the result is **default deny**. `TRUNCATE` and `REFERENCES` are **not** subject to RLS, so never grant TRUNCATE to the app.
- "Referential integrity checks, such as unique or primary key constraints and foreign key references, always bypass row security." This is a covert channel: an FK or unique error can reveal that a row exists. Validate co-membership in the handler first, and map 23503/23505 to generic errors.
- Policies that query another RLS table whose policy queries back cause **"infinite recursion detected in policy"**. Fix: SECURITY DEFINER helper functions.
- SECURITY DEFINER safety: set `search_path` to trusted schemas with `pg_temp` **last**. "By default, execute privilege is granted to PUBLIC for newly created functions", so REVOKE FROM PUBLIC in the **same transaction** as the CREATE.
- `row_security = off` "does not in itself bypass row security; what it does is throw an error". pg_dump sets it, so **pg_dump as `eldering_owner` fails** on FORCEd tables [V: `query would be affected by row-level security policy … (SQLSTATE 42501)`]. **Back up as the `postgres` superuser.**
- Views run with the view owner's rights. Use `CREATE VIEW … WITH (security_invoker = true)` (PG15+) or avoid views over RLS tables [D].
- `INSERT … ON CONFLICT DO UPDATE` must pass the SELECT and UPDATE policies on the existing row as well as the INSERT `WITH CHECK` [D]. The heartbeat upsert works [V].
- **Data back-fills inside migrations** run as the FORCEd owner and silently affect 0 rows. Run them as superuser, or under `SET LOCAL ROLE eldering_definer` after granting the definer the needed privileges.

### 6.2 Roles (bootstrap, runs once as superuser via `/docker-entrypoint-initdb.d`)
`server/deploy/initdb/20-eldering-roles.sh` (must be **chmod +x**: the entrypoint executes `*.sh` if executable and sources it otherwise; `20-` sorts after the image's `10_postgis.sh`). Full file: `server-stack-rls-harness/initdb/`.
```sql
CREATE ROLE eldering_owner   LOGIN PASSWORD :'owner_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE eldering_app     LOGIN PASSWORD :'app_pw'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20;
CREATE ROLE eldering_definer NOLOGIN NOSUPERUSER BYPASSRLS;   -- owns helpers only; attributes are never inherited
GRANT eldering_definer TO eldering_owner;                     -- lets migrations SET ROLE to create helpers
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO eldering_owner, eldering_app;
GRANT USAGE, CREATE ON SCHEMA public TO eldering_owner;
GRANT USAGE ON SCHEMA public TO eldering_app, eldering_definer;
CREATE SCHEMA rls AUTHORIZATION eldering_definer;
GRANT USAGE ON SCHEMA rls TO eldering_app, eldering_owner;
CREATE EXTENSION IF NOT EXISTS citext;            -- postgis is already created by the image in POSTGRES_DB
ALTER ROLE eldering_app SET statement_timeout = '5s';
ALTER ROLE eldering_app SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE eldering_app SET lock_timeout = '2s';
```
Passwords are passed with `psql -v owner_pw="$ELDERING_OWNER_PASSWORD"` from container env. Init scripts run **only on an empty data dir**: changing `.env` later does not change role passwords (use `ALTER ROLE … PASSWORD`).

### 6.3 Migration `0001_init.sql` (runs as `eldering_owner`): key parts
The full tested file is `server-stack-rls-harness/migrations/0001_init.sql`. It contains all SPEC tables with CHECK constraints, grants, helpers and policies. `refresh_tokens` and `family_invites` are not in the harness yet; they need no RLS per SPEC.
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON users, families, family_members, share_grants, user_settings,
  locations_latest, incidents, incident_trail, incident_acks TO eldering_app;          -- no TRUNCATE/DDL
GRANT SELECT ON family_members, share_grants, user_settings, locations_latest, incident_trail TO eldering_definer;
GRANT SELECT, DELETE ON incidents TO eldering_definer;                                  -- DELETE: retention only

SET LOCAL ROLE eldering_definer;        -- everything in schema rls is owned by the BYPASSRLS role
CREATE FUNCTION rls.uid() RETURNS uuid LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;             -- fail-closed
CREATE FUNCTION rls.co_members(a uuid, b uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT a IS NOT NULL AND b IS NOT NULL AND a <> b AND EXISTS (
      SELECT 1 FROM public.family_members x JOIN public.family_members y USING (family_id)
      WHERE x.user_id = a AND y.user_id = b) $$;
CREATE FUNCTION rls.grant_level(sharer uuid, viewer uuid) RETURNS text ... AS $$     -- absent row = 'emergency'
    SELECT coalesce((SELECT g.level FROM public.share_grants g
                     WHERE g.sharer_id = sharer AND g.viewer_id = viewer), 'emergency') $$;
CREATE FUNCTION rls.sharing_on(sharer uuid) RETURNS boolean ... AS $$                 -- 'on' or pause expired
    SELECT coalesce((SELECT s.sharing_state = 'on' OR (s.sharing_state = 'paused'
            AND s.paused_until IS NOT NULL AND s.paused_until <= now())
           FROM public.user_settings s WHERE s.user_id = sharer), false) $$;
CREATE FUNCTION rls.can_see_location(sharer uuid) RETURNS boolean ... AS $$
    SELECT rls.co_members(sharer, rls.uid()) AND rls.grant_level(sharer, rls.uid()) = 'live'
       AND rls.sharing_on(sharer) $$;
CREATE FUNCTION rls.can_see_incidents(sharer uuid) RETURNS boolean ... AS $$
    SELECT rls.co_members(sharer, rls.uid()) AND rls.grant_level(sharer, rls.uid()) IN ('live','emergency')
       AND (rls.sharing_on(sharer) OR coalesce((SELECT s.always_alert_on_accident
             FROM public.user_settings s WHERE s.user_id = sharer), true)) $$;
CREATE FUNCTION rls.shared_incident(token_hash bytea) RETURNS TABLE (...) ...  -- WHERE share_token_hash=$1 AND share_expires_at>now()
CREATE FUNCTION rls.shared_trail(token_hash bytea)    RETURNS TABLE (...) ...  -- same gate, trail ORDER BY recorded_at
CREATE FUNCTION rls.purge_expired_incidents() RETURNS bigint LANGUAGE sql VOLATILE SECURITY DEFINER ... AS $$
    WITH d AS (DELETE FROM public.incidents
               WHERE coalesce(resolved_at, share_expires_at) < now() - interval '72 hours' RETURNING 1)
    SELECT count(*) FROM d $$;                                                          -- trail/acks cascade
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA rls FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rls TO eldering_app;
RESET ROLE;

ALTER TABLE share_grants     ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;  -- same for user_settings,
-- locations_latest, incidents, incident_trail, incident_acks (SPEC list)

-- (SELECT rls.uid()) => InitPlan, evaluated once per statement instead of per row
CREATE POLICY sg_read ON share_grants FOR SELECT USING (sharer_id = (SELECT rls.uid()) OR viewer_id = (SELECT rls.uid()));
CREATE POLICY sg_ins  ON share_grants FOR INSERT WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_members(sharer_id, viewer_id));
CREATE POLICY sg_upd  ON share_grants FOR UPDATE USING (sharer_id = (SELECT rls.uid()))
  WITH CHECK (sharer_id = (SELECT rls.uid()) AND rls.co_members(sharer_id, viewer_id));
CREATE POLICY sg_del  ON share_grants FOR DELETE USING (sharer_id = (SELECT rls.uid()));
CREATE POLICY us_self ON user_settings FOR ALL USING (user_id = (SELECT rls.uid())) WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY ll_read ON locations_latest FOR SELECT USING (user_id = (SELECT rls.uid()) OR rls.can_see_location(user_id));
CREATE POLICY ll_ins/ll_upd/ll_del ...  -- self only
CREATE POLICY inc_read ON incidents FOR SELECT USING (user_id = (SELECT rls.uid()) OR rls.can_see_incidents(user_id));
CREATE POLICY inc_ins  ON incidents FOR INSERT WITH CHECK (user_id = (SELECT rls.uid()));
CREATE POLICY inc_upd  ON incidents FOR UPDATE USING (<same as read>) WITH CHECK (<same as read>);  -- viewer 'handled'
CREATE POLICY tr_read ON incident_trail FOR SELECT USING (EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));
CREATE POLICY tr_ins  ON incident_trail FOR INSERT WITH CHECK (EXISTS (SELECT 1 FROM incidents i
                       WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));
CREATE POLICY ack_read  ON incident_acks FOR SELECT USING (user_id = (SELECT rls.uid()) OR EXISTS (SELECT 1 FROM incidents i
                         WHERE i.id = incident_id AND i.user_id = (SELECT rls.uid())));
CREATE POLICY ack_write ON incident_acks FOR INSERT WITH CHECK (user_id = (SELECT rls.uid())
                         AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = incident_id));   -- + ack_upd (self)
```
Design notes:
- **Default `emergency` = absent grant row.** SPEC says only the sharer writes their rows. Inserting `(member→joiner)` rows at join time would be a cross-user write, so the default is computed instead. `GET /families/{id}/members` returns `coalesce(level,'emergency')` for `my_grant`/`their_grant`. Grant rows that survive leaving a family are harmless, because every check also requires `co_members`. That makes removal revoke visibility **immediately** [V test].
- The **BYPASSRLS on `eldering_definer` is load-bearing**. With `NOBYPASSRLS` the helpers can't read other users' `user_settings`, and 2 tests fail [V]. Attributes are not inherited through membership, so `eldering_owner` stays subject to FORCE.
- `incident_trail`/`incident_acks` policies use a plain sub-select on `incidents`, which is itself RLS-filtered. Visibility follows the parent with no definer needed and no recursion.
- The `family_members`, `families`, `family_invites`, `users` and `refresh_tokens` tables have no RLS, per SPEC (handler-enforced). Admin rights (invites, removal, rename) are checked in handlers. Admins get no extra visibility because the helpers ignore `role`.
- Public share: the handler hashes the token and calls `rls.shared_incident($1)` / `rls.shared_trail($1)` anonymously. Unknown and expired tokens give the same 404. "Latest location" for the share page = the newest `incident_trail` point (the live updates POST to `/trail`), so `locations_latest` is never exposed via a token.
- Retention: an hourly goroutine calls `SELECT rls.purge_expired_incidents()`.

Tests (all **PASS** on postgis 16-3.5-alpine, PG 16.15): live/emergency/off grants, non-member denial, viewer cannot write sharer's grant (42501), grant to non-member rejected, viewer UPDATE affects 0 rows, pause and expired pause, removal revokes, incident visibility under sharing off ± always_alert, trail follows incident, ack rules, share token valid/invalid/expired, anonymous sees 0 incidents, purge, pooled GUC residue `''`, FORCE on owner, superuser bypass, pg_dump-style `row_security=off` error.

---

## 7. Embedded migrations

```go
//go:embed migrations/*.sql
var migrationsFS embed.FS

// For each file in lexical order: pgx.BeginFunc(conn) {
//   SELECT pg_advisory_xact_lock(hashtext('eldering.migrate'));
//   skip if version in schema_migrations;
//   tx.Exec(ctx, string(body), pgx.QueryExecModeSimpleProtocol)  // multi-statement file
//   INSERT INTO schema_migrations(version) VALUES ($1) }
```
The full tested version is in `server-stack-rls-harness/migrate.go` (idempotent re-run tested). Run it **as `eldering_owner`** (`DATABASE_OWNER_URL`) via `eldering-api migrate`. Compose runs that as a one-shot service that `api` waits on (`service_completed_successfully`). The API itself only ever has the `eldering_app` DSN.

Alternative: `goose.NewProvider(goose.DialectPostgres, db, fsys)` + `provider.Up(ctx)` (goose v3.28.0). It needs `database/sql` via `github.com/jackc/pgx/v5/stdlib` and pulls in more deps. Not needed for one service.

---

## 8. Postgres small-memory tuning + compose

Settings for a ~1 GiB host with about 0.5 GiB free, with Postgres capped at 256 MiB. Values are SPEC plus my choices; idle RSS measured **~44 MiB** [V]:

| setting | value | why |
|---|---|---|
| shared_buffers | **64MB** | SPEC |
| max_connections | **30** | SPEC; pool 8 + slack |
| work_mem | 2MB | tiny queries; 30×2 MB worst case |
| maintenance_work_mem | 32MB | vacuum/index builds |
| effective_cache_size | 192MB | planner hint only |
| wal_buffers | 4MB | |
| max_wal_size / min_wal_size | 256MB / 64MB | fewer checkpoints, bounded disk |
| jit | off | saves LLVM memory (alpine image ships llvm21) |
| max_worker_processes / max_parallel_workers(_per_gather) | 4 / 0 / 0 | no parallel query |
| autovacuum_max_workers | 2 | |
| huge_pages | off | |
| random_page_cost | 1.1 | block volume |
| log_min_duration_statement | 500ms | |

`/dev/shm` stays at the 64 MB default: `shared_memory_type=mmap`, and with parallel query off, DSM use is tiny.

`~/eldering-safety/compose.yaml` (validated with `docker compose config --quiet` [V]; `mem_limit`/`memswap_limit`/`cpus` verified applied by Compose v2 [V]):
```yaml
name: eldering
x-logging: &logging { driver: json-file, options: { max-size: "10m", max-file: "3" } }
services:
  db:
    image: postgis/postgis:16-3.5-alpine@sha256:47e961a569fd52ff31f0fe205ed91eeab17d9f5fff6722e6d7ea6b588748b293
    restart: unless-stopped
    environment:
      POSTGRES_USER: postgres
      POSTGRES_DB: eldering
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
      POSTGRES_INITDB_ARGS: --data-checksums
      ELDERING_OWNER_PASSWORD: ${ELDERING_OWNER_PASSWORD:?}
      ELDERING_APP_PASSWORD: ${ELDERING_APP_PASSWORD:?}
    command: [postgres, -c, shared_buffers=64MB, -c, max_connections=30, -c, work_mem=2MB,
              -c, maintenance_work_mem=32MB, -c, effective_cache_size=192MB, -c, wal_buffers=4MB,
              -c, max_wal_size=256MB, -c, min_wal_size=64MB, -c, jit=off, -c, max_worker_processes=4,
              -c, max_parallel_workers=0, -c, max_parallel_workers_per_gather=0, -c, autovacuum_max_workers=2,
              -c, huge_pages=off, -c, random_page_cost=1.1, -c, log_min_duration_statement=500ms, -c, log_lock_waits=on]
    volumes:
      - pgdata:/var/lib/postgresql/data          # PG16 path (PG18+ images changed it to /var/lib/postgresql)
      - ./initdb/20-eldering-roles.sh:/docker-entrypoint-initdb.d/20-eldering-roles.sh:ro
    networks: [backend]
    mem_limit: 256m
    memswap_limit: 512m        # may use 256m of the host's 2 GiB swap before OOM
    cpus: 1.0
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d eldering -h 127.0.0.1"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 60s
    stop_grace_period: 60s
    logging: *logging
  migrate:
    image: eldering-api:${API_TAG:?}
    command: ["migrate"]
    environment:
      DATABASE_OWNER_URL: postgres://eldering_owner:${ELDERING_OWNER_PASSWORD}@db:5432/eldering?sslmode=disable
    depends_on: { db: { condition: service_healthy } }
    networks: [backend]
    restart: "no"
    mem_limit: 64m
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    logging: *logging
  api:
    image: eldering-api:${API_TAG:?}
    command: ["serve"]
    restart: unless-stopped
    environment:
      LISTEN_ADDR: ":8080"
      DATABASE_URL: postgres://eldering_app:${ELDERING_APP_PASSWORD}@db:5432/eldering?sslmode=disable&pool_max_conns=8
      JWT_SECRET: ${JWT_SECRET:?}
      PUBLIC_BASE_URL: ${PUBLIC_BASE_URL:-https://safety.example.com}
      GOMEMLIMIT: 100MiB      # soft GC target under the 128m hard limit
      TZ: UTC
    ports: ["127.0.0.1:8095:8080"]
    depends_on:
      db: { condition: service_healthy }
      migrate: { condition: service_completed_successfully }
    networks: [backend, edge]
    mem_limit: 128m
    memswap_limit: 128m      # no swap: fail fast, restart
    cpus: 1.0
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    healthcheck:
      test: ["CMD", "/eldering-api", "healthcheck"]
      interval: 30s
      timeout: 3s
      retries: 3
      start_period: 10s
    stop_grace_period: 20s
    logging: *logging
networks:
  backend: { internal: true }   # db: no egress, no host ports
  edge: {}                      # REQUIRED for api: ports published on an internal-only network are unreachable
volumes:
  pgdata: {}
```
- **Pitfall [V]:** a service attached **only** to an `internal: true` network got its `127.0.0.1:port` published, but the port was **not reachable** (Docker Desktop 29.1.3 test). `api` must also join a normal network (`edge`). The same Docker semantics apply on Linux [D/U].
- Go ≥1.25 derives GOMAXPROCS from the cgroup CPU limit, **with a floor of 2** (`--cpus 1` gave GOMAXPROCS=2 [V]; runtime source comment "minimum of 2"). Memory is not auto-detected, so set `GOMEMLIMIT`.
- Budget: db ≤256m + api ≤128m + migrate (transient) ≤64m. That fits the ~0.5 GiB available, with swap as a backstop for db only.
- Never print secrets on the server: avoid `docker compose config` (prints interpolated env; use `--quiet`), `docker inspect` env sections, and `env`. Optional hardening: compose `secrets:` with `POSTGRES_PASSWORD_FILE` [D].

---

## 9. Build and ship (Apple Silicon to linux/amd64)

`server/Dockerfile`: the build stage runs natively on arm64 and Go cross-compiles, so no QEMU is used for the compile [V].
```dockerfile
# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM golang:1.26.9-alpine3.24@sha256:3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade AS build
ARG TARGETOS TARGETARCH VERSION=dev
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/eldering-api ./cmd/api

FROM gcr.io/distroless/static-debian13:nonroot@sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3
COPY --from=build /out/eldering-api /eldering-api
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/eldering-api"]
```
Test build [V]: image `linux/amd64`, **3.37 MB**; container printed `go1.26.9 linux amd64 uid 65532`; `docker save | gzip` = 3.36 MB. Docker Desktop must be running (`open -a Docker`; it can take about a minute).

The binary-only alternative also works [V]: `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/eldering-api ./cmd/api`. Note this uses the *local* go1.26.0 stdlib, so prefer the image build or `GOTOOLCHAIN=go1.26.9`.

`server/deploy/deploy.sh` (sketch):
```sh
set -eu
SSH="ssh -i ~/.ssh/<server-key> user@203.0.113.10"
TAG=$(date -u +%Y%m%d%H%M%S)
docker buildx build --platform linux/amd64 --build-arg VERSION=$TAG -t eldering-api:$TAG --load server/
docker save --platform linux/amd64 eldering-api:$TAG | gzip | $SSH 'gunzip | docker load'
$SSH 'mkdir -p ~/eldering-safety/initdb'
scp -i ~/.ssh/<server-key> server/deploy/compose.yaml user@203.0.113.10:eldering-safety/compose.yaml
scp -i ~/.ssh/<server-key> server/deploy/initdb/20-eldering-roles.sh user@203.0.113.10:eldering-safety/initdb/
$SSH "cd ~/eldering-safety && chmod 755 initdb/20-eldering-roles.sh \
  && sed -i 's/^API_TAG=.*/API_TAG=$TAG/' .env && docker compose up -d --remove-orphans \
  && docker compose ps && curl -fsS http://127.0.0.1:8095/healthz"
# prune only OUR old images:  docker images eldering-api --format '{{.Tag}}' | tail -n +4 | xargs -r -I{} docker rmi eldering-api:{}
```
Secrets are generated **on the server** once and never echoed. Hex keeps DSNs URL-safe:
```sh
$SSH 'umask 077; mkdir -p ~/eldering-safety && cd ~/eldering-safety && [ -f .env ] || {
  printf "POSTGRES_PASSWORD=%s\nELDERING_OWNER_PASSWORD=%s\nELDERING_APP_PASSWORD=%s\nJWT_SECRET=%s\nAPI_TAG=\n" \
    "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > .env
  chmod 600 .env; }'
```
Backup (superuser bypasses RLS; the owner would error): `docker compose exec -T db pg_dump -U postgres -Fc eldering > backup.dump`.

---

## 10. nginx + certbot

Step 1: write `/etc/nginx/sites-available/eldering` with only the `:80` block below (`listen 80; listen [::]:80;` + the `server{}` body), then `sudo ln -s /etc/nginx/sites-available/eldering /etc/nginx/sites-enabled/eldering`, `sudo nginx -t`, `sudo systemctl reload nginx`.

Step 2: `sudo certbot --nginx -d safety.example.com --redirect` (the account already exists; `--non-interactive` is OK). certbot rewrites **only this file**: it adds `listen 443 ssl` + cert lines (`# managed by Certbot`) and a `:80` redirect block, the same shape as any certbot-managed site. Then run `sudo nginx -t` and reload again. Renewal is handled by the existing `certbot.timer`.

The final file below passed `nginx -t` on an nginx 1.18 container [V, docker nginx alpine with a self-signed cert at the LE paths]:
```nginx
# http{}-context directives are legal here (Ubuntu nginx.conf includes sites-enabled/* inside http{}).
# Zone/map/log_format names are global -> prefix eldering_.
map $request_uri $eldering_log_uri {            # keep share tokens out of access logs
    ~^/s/   "/s/[redacted]";
    default $request_uri;
}
log_format eldering_privacy '$remote_addr - [$time_local] "$request_method $eldering_log_uri $server_protocol" '
                            '$status $body_bytes_sent $request_time "$http_user_agent"';
limit_req_zone $binary_remote_addr zone=eldering_auth:1m rate=10r/m;   # coarse guard; Go limiter is primary

upstream eldering_api { server 127.0.0.1:8095; keepalive 8; }

server {
    server_name safety.example.com;
    server_tokens off;
    access_log /var/log/nginx/eldering.access.log eldering_privacy;
    error_log  /var/log/nginx/eldering.error.log warn;
    client_max_body_size 1m;
    client_body_timeout 15s;

    # Only at server level: any add_header inside a location would drop ALL of these there.
    add_header Strict-Transport-Security "max-age=31536000" always;   # no includeSubDomains (sslip.io!)
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header X-Frame-Options "DENY" always;

    location /v1/auth/ {
        limit_req zone=eldering_auth burst=10 nodelay;
        limit_req_status 429;
        proxy_pass http://eldering_api;
        include /etc/nginx/eldering_proxy_params;   # or inline the block below
    }
    location / {
        proxy_pass http://eldering_api;
        include /etc/nginx/eldering_proxy_params;
    }

    listen 443 ssl; # managed by Certbot
    ssl_certificate /etc/letsencrypt/live/safety.example.com/fullchain.pem; # managed by Certbot
    ssl_certificate_key /etc/letsencrypt/live/safety.example.com/privkey.pem; # managed by Certbot
    include /etc/letsencrypt/options-ssl-nginx.conf; # managed by Certbot
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem; # managed by Certbot
}
server {
    if ($host = safety.example.com) { return 301 https://$host$request_uri; } # managed by Certbot
    listen 80;
    server_name safety.example.com;
    return 404; # managed by Certbot
}
```
`/etc/nginx/eldering_proxy_params` (our own new file; or inline it in both locations to keep everything in one file):
```nginx
proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;          # OVERWRITE (never $proxy_add_x_forwarded_for)
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_connect_timeout 5s; proxy_send_timeout 30s; proxy_read_timeout 30s;
proxy_hide_header X-Powered-By;
```
- **Don't add `http2`**: on nginx < 1.25.1, `listen 443 ssl http2` turns HTTP/2 on for **every** vhost sharing `*:443`, which would change every other site on the box. The per-server `http2 on;` directive only exists from 1.25.1. This is known nginx behavior; I did not test it here [D/U].
- Don't edit `nginx.conf` or `options-ssl-nginx.conf` (shared; certbot-managed).
- The 443 server block needs no IPv6 `listen [::]:443` unless the `:80` block had `listen [::]:80`; certbot mirrors whatever the 80 block has.
- sslip.io is **not** on the Public Suffix List [V], so the LE "per registered domain" limit is shared by all sslip.io users. LE raised sslip.io's limit to 250,000/week [V sslip.io]. Rate-limit failures are possible but rare. Fallback: `nip.io` or the future `safe.example.org`. LE IP-address certs (GA 2026-01) would need a newer certbot than 0.40 [U].
- Future domain: `sudo certbot --nginx -d safety.example.com -d safe.example.org --expand` after DNS points here, and add the name to `server_name` first.
- Go also sets per-response headers (CSP, `Cache-Control: no-store`, `X-Robots-Tag`). Duplicate `X-Content-Type-Options` from both layers is harmless. Use `proxy_hide_header` if you want exactly one.

---

## 11. Public share page: MapLibre GL JS + OpenFreeMap

**MapLibre GL JS 6.11.2** (BSD-3-Clause). Since v6.0.0 it is **ESM-only**: `maplibre-gl.mjs` statically imports `./maplibre-gl-shared.mjs` and starts `./maplibre-gl-worker.mjs` as a module worker resolved from `import.meta.url`. There is **no default export**; use named imports (`import { Map, Marker } …`). UMD `maplibre-gl.js` and `maplibre-gl-csp.js` no longer exist. `GeoJSONSource.setData` lost its 2nd arg and no longer returns `this`. WebGL2 is required [V CHANGELOG + dist inspection].

**Recommended: vendor the 4 dist files and serve them same-origin via `go:embed`** (`/static/maplibre-gl/6.11.2/…`, `Cache-Control: public, max-age=31536000, immutable`):
- Reason 1: SRI doesn't cover ESM static imports (`shared.mjs`) without import-map integrity.
- Reason 2: a cross-origin CDN load makes MapLibre wrap the worker in a `blob:` URL, which needs `worker-src blob:`.
- Reason 3: it removes a third party from an emergency page.

Source: npm tarball `https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-6.11.2.tgz`, integrity `sha512-Xh06pxoipjX/Ad1sUPGhiNh9go9naCNGZZ1IJm3sIeqK1kYGuMaMDSTIGDQ2igOLfVyukJU7kvEOA089VO7Z7g==` [V matched]. Extract `package/dist/{maplibre-gl.mjs (590,228 B), maplibre-gl-shared.mjs (515,924 B), maplibre-gl-worker.mjs (19,133 B), maplibre-gl.css (83,310 B)}` plus `LICENSE.txt`. These are byte-identical to the unpkg copies [V].

CDN URLs if ever needed (pin exact version):
- unpkg (200 [V]): `https://unpkg.com/maplibre-gl@6.11.2/dist/maplibre-gl.mjs` and `…/dist/maplibre-gl.css`
- jsDelivr (200 [V]): `https://cdn.jsdelivr.net/npm/maplibre-gl@6.11.2/dist/maplibre-gl.mjs`
- **cdnjs does not work for v6 JS**: its 6.11.2 entry has only `maplibre-gl.css`/`.min.css`, and `…/6.11.2/maplibre-gl.mjs` returns **404** [V cdnjs API + HEAD].
- SRI sha384 (entry/css only): mjs `sha384-KQzExYlfg1SnYNpLaXHTnaCTjr5wmiZ6X3sasvPb94caZfH+3+T1Sl4eJziXUtmN`, css `sha384-ntw3zEt6rcVML7jDK0ULmHa5hxLB23afsPqzqfY+gLgMfAkbFCnCgPpkZvV5mmZX`.
- A CDN load needs CSP `script-src https://unpkg.com; worker-src blob:` instead of `'self'`.

**Style**: `https://tiles.openfreemap.org/styles/liberty`. The style, TileJSON (`/planet` → `/planet/<build>/{z}/{x}/{y}.pbf`, maxzoom 14), sprites, glyphs and natural-earth raster all live on **`tiles.openfreemap.org`** only, served with `access-control-allow-origin: *` via Cloudflare [V].

**CSP for `GET /s/{token}`**, verified in a real browser: map rendered and **0 `securitypolicyviolation` events**. The console had only style-spec warnings from the liberty style [V]:
```
default-src 'none'; script-src 'self'; style-src 'self';
img-src 'self' data: blob: https://tiles.openfreemap.org;
connect-src 'self' https://tiles.openfreemap.org; worker-src 'self';
base-uri 'none'; form-action 'none'; frame-ancestors 'none'
```
Other headers: `Cache-Control: no-store`, `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer` (stops the token leaking to OpenFreeMap/Cloudflare via `Referer`), `Content-Type: text/html; charset=utf-8`. Put no inline script; that allows `script-src 'self'` without nonces.

```html
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><meta name="referrer" content="no-referrer">
<title>Eldering alert</title>
<link rel="stylesheet" href="/static/maplibre-gl/6.11.2/maplibre-gl.css">
<link rel="stylesheet" href="/static/share.css">
<script type="module" src="/static/share.js"></script>
</head><body><div id="map"></div><!-- + text fallback: coords, time, landmarks, maps link --></body></html>
```
```js
// /static/share.js
import { Map, Marker, NavigationControl } from '/static/maplibre-gl/6.11.2/maplibre-gl.mjs';
const token = location.pathname.split('/')[2];
const d = await (await fetch(`/s/${encodeURIComponent(token)}/data`, { cache: 'no-store' })).json();
const map = new Map({ container: 'map', style: 'https://tiles.openfreemap.org/styles/liberty',
                      center: [d.lng, d.lat], zoom: 15, attributionControl: { compact: true } });
map.addControl(new NavigationControl({ showCompass: false }));
map.on('load', () => {
  map.addSource('trail', { type: 'geojson', data: { type: 'Feature', properties: {},
    geometry: { type: 'LineString', coordinates: d.trail.map(p => [p.lng, p.lat]) } } });
  map.addLayer({ id: 'trail', type: 'line', source: 'trail', paint: { 'line-color': '#d33', 'line-width': 4 } });
  new Marker({ color: '#d33' }).setLngLat([d.lng, d.lat]).addTo(map);
});
// poll /data every 15 s while status=='active'; render landmarks list as plain DOM text (textContent, never innerHTML)
```

**OpenFreeMap terms** [V openfreemap.org, /tos updated 2026-09-09]:
- Public instance is free, with "no limits on the number of map views or requests", no registration, API keys or cookies. Commercial use is allowed.
- **Attribution required.** MapLibre adds it automatically from the style ("OpenFreeMap © OpenMapTiles Data from OpenStreetMap"); keep the attribution control (compact is fine).
- No SLA. They "may discontinue it at any time without notice". Requests go through **Cloudflare**, which sees viewer IP and viewport.
- ToS: integrators must be 18+ (end users any age). No "automated" data collection, so do **not** prefetch or cache tiles server-side.
- Implication: the share page must stay useful **without tiles**. Always render text lat/lng, last-update time, the landmark timeline, and `https://www.openstreetmap.org/?mlat=..&mlon=..#map=17/..` and `geo:` links. Self-hosting OFM is possible later (planet Btrfs) but far beyond a 1 GB VM.

---

## 12. Deviations from SPEC / open items

1. **Three DB roles** (`eldering_owner`, `eldering_app`, `eldering_definer`) instead of one "non-superuser role". The app role still matches SPEC (non-superuser, NOBYPASSRLS). The other two are needed so FORCE RLS is meaningful and policy helpers can't recurse.
2. **`set_config('app.user_id', $1, true)`** instead of literal `SET LOCAL app.user_id = '<uuid>'`. Same semantics, but parameterized, so no SQL string building.
3. **Default `emergency` grant = absence of a row** (computed in `rls.grant_level`), not inserted rows. This keeps "only the sharer writes their rows" true.
4. **PostGIS image `16-3.5-alpine`**, not the Debian `16-3.5`, which is stale at PG 16.9.
5. **MapLibre vendored, ESM** (v6 has no UMD build); CDN only as a fallback.
6. chi `RealIP` is deprecated, so use `ClientIPFromHeader("X-Real-IP")`.
7. Open: `refresh_tokens` and `family_invites` DDL are not in the harness (no RLS per SPEC; plain tables). Invite code: 8 chars from `crypto/rand` over an unambiguous alphabet (e.g. Crockford base32 without I/L/O/U), unique index, `uses < max_uses AND expires_at > now()` checked with `UPDATE … RETURNING` in the join tx.
8. Open [U]: the nginx `http2` scope claim and IP-cert support in certbot 0.40 were not tested here. The internal-network port behavior was tested on Docker Desktop, not on the Linux host.

## 13. Verification log (commands actually run)
- `curl proxy.golang.org/<mod>/@latest|@v/list|.info|.mod`; `curl go.dev/dl/?mode=json`. Versions and dates as above.
- `curl hub.docker.com/v2/repositories/postgis/postgis/tags?...`; `docker buildx imagetools inspect postgis/postgis:16-3.5{,-alpine}` with `--format '{{json .Image}}'`. Results: PG_VERSION 16.9 (Debian) vs 16.15 (alpine).
- SSH read-only: `uname -m; free -m; docker version; docker compose version; nginx -v; certbot --version; certbot plugins; certbot certificates; cat options-ssl-nginx.conf; grep listen sites-enabled`.
- `scp` of an argon2 benchmark to `/tmp`, run with `nice -n 10`, then `rm`. Timings as in §5.
- `docs/research/server-stack-rls-harness/run.sh`: **4/4 PASS**, and also with `ALTER ROLE eldering_definer NOBYPASSRLS` → 2 FAIL (proves BYPASSRLS is required).
- Scratch module: `go vet` + `go test -race` for argon2/JWT/limiter/router snippets: **PASS**.
- `docker buildx build --platform linux/amd64 … --load` + `docker run --cpus 1` + `docker save --platform linux/amd64 | gzip | wc -c`: **OK**.
- `docker compose config --quiet` on the compose above: **OK**. Compose internal-network port test: **unreachable** vs **reachable** with `edge`.
- `nginx -t` on an nginx 1.18 alpine container with the final site file: **syntax ok**.
- Local Go server with the §11 CSP, opened in the in-app browser: map rendered, 0 CSP violations, worker loaded from `'self'`.
