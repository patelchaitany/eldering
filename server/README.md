# Eldering backend (`server/`)

Go API + PostgreSQL 16/PostGIS for the Eldering safety app. It implements `../SPEC.md` (data
model, RBAC, HTTP API). The research behind the choices is in `../docs/research/server-stack.md`.

- **Go 1.26**, chi v5.3.2, pgx v5.11.0, golang-jwt v5.3.1, x/crypto (argon2id), x/time (rate limits).
- **Consent-based RBAC enforced twice**: row-level security in Postgres (ENABLED + FORCED) and
  checks in the handlers. The API connects as `eldering_app`, which does not own the tables and
  has no `BYPASSRLS`. Every request runs in a transaction with
  `set_config('app.user_id', <uuid>, true)`.
- **Public share page** `/s/{token}`: MapLibre GL JS 6.11.2 (vendored, served from the same origin) with
  OpenFreeMap tiles, a strict CSP, `noindex` and `no-store`. Without JavaScript or tiles, it still shows the text.
- Static, non-root, distroless image of about 7 MB (`linux/amd64`).

## Layout

```
cmd/api/                 main: serve | migrate | healthcheck | version
internal/config/         env configuration
internal/db/             pool, WithUser/Anon (RLS identity), role bootstrap, migrator
internal/db/migrations/  0001_init.sql: schema, grants, rls.* helpers, policies, triggers
internal/auth/           argon2id, HS256 access JWT, opaque tokens (SHA-256 stored), middleware
internal/rbac/           vocabulary + handler-level checks (member/admin/co-member)
internal/ratelimit/      keyed token buckets (per IP / per account) with eviction
internal/handlers/       HTTP API (/v1/...), JSON errors, request logging, security headers
internal/sharepage/      /s/{token}, /s/{token}/data, /static/* (embedded assets + template)
internal/retention/      hourly purge (incidents 72 h after resolution/expiry, tokens, invites)
internal/testdb/         integration-test database provisioning (real PostGIS)
e2e/                     black-box RBAC/security suite against a running server (+ SQL RLS checks)
deploy/                  deploy.sh (server), nginx/eldering.conf
docker-compose.yml       production stack (db + migrate + api)
docker-compose.test.yml  throwaway test database (127.0.0.1:55433, tmpfs)
```

## Run locally

Requires Docker Desktop and Go 1.26.

```sh
make up        # writes a local .env with random secrets, builds the image, starts db+migrate+api
curl http://127.0.0.1:8095/healthz
make logs
make down      # `docker compose down -v` also deletes the data volume
```

The API listens on `127.0.0.1:8095` only. Postgres is never published.

## Tests

```sh
make test       # gofmt is separate; this runs ALL tests and requires the DB (ELDERING_REQUIRE_DB=1)
make test-race  # same with -race
make test-unit  # -short: unit tests only, no Docker
make testdb-down
```

Integration tests use a real `postgis/postgis:16-3.5-alpine` server (the same image as production).
`internal/testdb` starts `docker-compose.test.yml` itself when needed, so a plain `go test ./...`
also runs them. Each package gets a fresh database that goes through the production path:
superuser bootstrap, then the migrations run as `eldering_owner`, then the tests connect as
`eldering_app`. To use another server, set `ELDERING_TEST_PG_URL=postgres://<superuser>@host/db`.

What is covered:
- **RLS (`internal/db`)**: live/emergency/off grants; pause and elapsed pause; sharing off;
  always-alert; non-member denial; viewer cannot write the sharer's grant, location or settings
  (42501 or 0 rows); grants only to co-members; removal revokes immediately and prunes grants (a
  re-join starts again at `emergency`); trail and acks follow incident visibility; viewer may only
  resolve as `handled` (guard trigger + column grants); share-token lookup (valid, unknown,
  expired, resolved hides location); anonymous sees nothing; pooled GUC residue; FORCE RLS on the
  owner; app role privileges (no TRUNCATE/DDL/DELETE incidents, no access to internal helpers);
  idempotent bootstrap and migrate.
- **API (`internal/handlers`)**: auth (register, login, refresh rotation, reuse detection, logout,
  expiry, validation), per-IP and per-account rate limits (429 + Retry-After), 1 MB body limit
  (413), families/invites/join/members/rename/remove/leave/last-admin promotion, grants,
  heartbeat + family locations visibility, incidents (create, trail, ack, resolve, active, detail,
  idempotent retry), and the share page (HTML, data, headers, escaping, static assets, expiry,
  resolved state).
- **Retention (`internal/retention`)**, plus unit tests for auth, the rate limiter, config, the
  validators and the share-page rendering.

### Black-box RBAC/security suite (`e2e/`)

`e2e/` tests a **running** server over HTTP only (any `E2E_BASE_URL`), with throwaway accounts
`e2e-sec-<random>@example.test`. Without `E2E_BASE_URL` it does nothing, so `go test ./...` stays
hermetic. It covers every SPEC RBAC rule with positive and negative cases (live / emergency / off
grants; pause, elapsed pause and off, with and without always-alert; non-co-members; admin gets no
extra visibility; only the sharer writes grants; removal and leave revoke at once; IDOR on every
incident action; share-token scope, expiry and garbage tokens), plus refresh rotation and reuse,
JWT tampering (`alg=none`, payload/header/signature swaps, bad claims), rate limits (per IP, per
account, per user; spoofed `X-Forwarded-For`/`X-Real-IP`; IPv6 /64 rotation), body limits,
injection-style input, HTTP hardening, uniform 404s for other users' resources, and a burst of
concurrent sign-ups. `TestRLSAppRole` runs `e2e/rls_app_role.sql` through psql: about 190 checks
made directly in PostgreSQL as `eldering_app` (no `app.user_id` means no rows, the full visibility
matrix, refused writes and privilege escalation, GUC residue). Each run prints a PASS/FAIL/SKIP
matrix.

```sh
make up && make e2e   # local stack; also writes dist/e2e-matrix.md
make e2e-rls          # only the SQL checks
make e2e-clean        # delete the e2e-sec-* accounts (and their families)
```

Against the server (behind nginx, so leave `E2E_TRUSTED_IP_HEADER` unset; setup then waits out
the 10/min sign-up limit, so allow ~6 minutes):

```sh
. deploy/deploy.env   # ELDERING_HOST, ELDERING_SSH_KEY, ELDERING_DOMAIN (not committed)
E2E_BASE_URL=https://$ELDERING_DOMAIN \
E2E_PSQL="ssh -i $ELDERING_SSH_KEY $ELDERING_HOST 'cd ~/eldering-safety && docker compose -p eldering exec -T db psql -X -q -U postgres -d eldering'" \
  go test -count=1 -v -timeout 30m ./e2e
ssh -i "$ELDERING_SSH_KEY" "$ELDERING_HOST" 'cd ~/eldering-safety && docker compose -p eldering exec -T db psql -X -q -v ON_ERROR_STOP=1 -U postgres -d eldering' < e2e/cleanup.sql
```

## Configuration (env)

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | required | `eldering_app` DSN, e.g. `...?sslmode=disable&pool_max_conns=8` |
| `JWT_SECRET` | required | hex, ≥ 32 bytes |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | used to build `share_url` |
| `LISTEN_ADDR` | `:8080` | |
| `CLIENT_IP_HEADER` | `X-Real-IP` | header that nginx **overwrites**; `none` = use the TCP peer |
| `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL` | `15m` / `720h` | |
| `SHARE_TOKEN_TTL` / `INVITE_TTL` / `INVITE_MAX_USES` | `72h` / `48h` / `10` | |
| `RETENTION_INTERVAL` | `1h` | |
| `BODY_LIMIT_BYTES` | `1048576` | |
| `MAX_PASSWORD_HASH_JOBS` | `2` | caps concurrent argon2id (19 MiB each) |
| `RATE_AUTH_PER_MIN`/`_BURST` | `10`/`10` | per IP: register, login; per account: family join |
| `RATE_TOKEN_PER_MIN`/`_BURST` | `60`/`30` | per IP: refresh, logout (generous for carrier NAT) |
| `RATE_LOGIN_ACCOUNT_PER_MIN`/`_BURST` | `1`/`5` | per email |
| `RATE_SHARE_PER_MIN`/`_BURST` | `60`/`30` | per IP: `/s/...` |

`migrate` reads `DATABASE_OWNER_URL` (required), plus `DATABASE_ADMIN_URL`, `ELDERING_OWNER_PASSWORD`
and `ELDERING_APP_PASSWORD` for the idempotent role bootstrap.

## Deploy (shared VM: touch only our stack)

Copy `deploy/deploy.env.example` to `deploy/deploy.env` (not committed) and fill in the host, SSH key and domain.

```sh
./deploy/deploy.sh app      # buildx linux/amd64 → docker save | gzip | ssh docker load → compose up
./deploy/deploy.sh nginx    # first time: HTTP-only site file, `nginx -t` before reload (rolls back on failure)
./deploy/deploy.sh cert     # first time: certbot --nginx -d $ELDERING_DOMAIN --redirect
./deploy/deploy.sh status
```

- Stack directory `~/eldering-safety/`, compose project `eldering`. The API is published on
  `127.0.0.1:8095` only.
- Secrets are generated **on the server** into `~/eldering-safety/.env` (chmod 600) the first time
  and never printed. Do not run `docker compose config` there without `--quiet`.
- Every `up` re-runs `migrate`. It is idempotent: it converges roles and attributes, re-applies
  role passwords from `.env`, and applies new migrations.
- Backups must run as the superuser, because the owner errors on FORCEd tables:
  `docker compose -p eldering exec -T db pg_dump -U postgres -Fc eldering > backup.dump`.
- Additional domain: add it to `server_name`, then run
  `sudo certbot --nginx -d <current-domain> -d <new-domain> --expand`, and set `PUBLIC_BASE_URL`.

## HTTP API

JSON in both directions. Times are RFC 3339 and returned in UTC. Authenticated routes need
`Authorization: Bearer <access_token>`. Errors are `{"error":{"code","message"}}`. A resource you
may not see returns **404**, exactly like one that does not exist. **403** is used only when you
can see the resource but may not perform the action. Unknown JSON fields are ignored.

| Method & path | Body | Success |
|---|---|---|
| `POST /v1/auth/register` | `{email,password(8-128),display_name(1-80)}` | 201 `AuthResponse`; 409 `email_taken` |
| `POST /v1/auth/login` | `{email,password}` | 200 `AuthResponse`; 401 `invalid_credentials`; 429 |
| `POST /v1/auth/refresh` | `{refresh_token}` | 200 `AuthResponse` (rotated). Reusing an old token revokes all sessions; 401 `invalid_refresh_token` |
| `POST /v1/auth/logout` | `{refresh_token}` | 204 (idempotent) |
| `GET /v1/me` | | `{user, settings, families:[Family]}` |
| `PATCH /v1/me/settings` | `{sharing_state?:on\|paused\|off, paused_until?:time\|null, always_alert_on_accident?}` | `Settings` |
| `POST /v1/families` | `{name}` | 201 `Family` + `invite:{code,expires_at,max_uses}` |
| `PATCH /v1/families/{id}` | `{name}` | `Family` (admin) |
| `POST /v1/families/{id}/invites` | | 201 `{code,expires_at,max_uses}` (admin; 8 chars, 48 h) |
| `POST /v1/families/join` | `{code}` | `Family`; 404 `invite_invalid`. Case, dashes and spaces are ignored. Re-joining is idempotent. |
| `GET /v1/families/{id}/members` | | `[{user_id,display_name,role,joined_at,is_me,my_grant,their_grant}]` |
| `DELETE /v1/families/{id}/members/{userId}` | | 204 (admin, or self = leave). An admin removal also revokes the family's outstanding invite codes. |
| `GET /v1/grants` | | `[{viewer_id,display_name,level,is_default,updated_at}]` for every co-member |
| `PUT /v1/grants/{viewerId}` | `{level:live\|emergency\|off}` | `Grant` (viewer must be a co-member, else 404) |
| `POST /v1/location/heartbeat` | `{lat,lng,recorded_at, accuracy_m?,speed_mps?,bearing_deg?,activity?,battery_pct?}` | `{pending_alerts:[IncidentSummary], server_time}` |
| `GET /v1/family/locations` | | `[{user_id,display_name,status:live\|paused\|hidden, lat?,lng?,accuracy_m?,speed_mps?,bearing_deg?,activity?,battery_pct?,recorded_at?}]` |
| `POST /v1/incidents` | `{kind,detected_at,lat?,lng?,details?:{},trail:[{lat,lng,recorded_at,accuracy_m?,landmark?}]}` | 201 `{id,status,share_url,share_expires_at}`. With an `Idempotency-Key` header, a retry returns 200 with the same id and a **new** link. |
| `POST /v1/incidents/{id}/trail` | `{points:[TrailPoint]}` | `{accepted:n}` (owner; duplicate timestamps are ignored); 409 when not active |
| `POST /v1/incidents/{id}/resolve` | `{resolution:user_ok\|false_alarm\|handled}` | `IncidentSummary`. The owner may use any value, a viewer only `handled`. `false_alarm` sets status `cancelled`. |
| `POST /v1/incidents/{id}/ack` | `{state:seen\|on_my_way}` | `{incident_id,user_id,display_name,state,at}` (viewers only; `seen` never downgrades `on_my_way`) |
| `GET /v1/incidents/active` | | `[IncidentSummary]` that I can see, my own included |
| `GET /v1/incidents/{id}` | | `IncidentSummary` + `{details,resolved_by,trail:[...],acks:[...]}` |
| `GET /s/{token}` | | public HTML |
| `GET /s/{token}/data` | | `{kind,status,detected_at,share_expires_at,resolved_at,resolution,incident_location,latest,trail,server_time}` |
| `GET /healthz` | | `{status:"ok"}` (checks the DB) |

- `AuthResponse` = `{access_token, token_type:"Bearer", expires_in:900, access_expires_at, refresh_token, refresh_expires_at, user:{id,email,display_name,created_at}}`
- `Settings` = `{sharing_state, paused_until, always_alert_on_accident, effective_sharing_state}`
- `Family` = `{id,name,role,joined_at,member_count,created_at}`
- `IncidentSummary` = `{id,user_id,display_name,kind,status,detected_at,lat,lng,last_location:{lat,lng,recorded_at,accuracy_m}|null,share_expires_at,resolved_at,resolution,is_mine,my_ack}`
- `activity` is one of `still, walking, running, cycling, vehicle, unknown` (case-insensitive).
- Heartbeats with an older `recorded_at` never overwrite a newer stored point. With sharing `off`,
  the server stores no location and deletes the one it has.

## Security model in brief

- Roles: `eldering_owner` owns the tables, runs the migrations, and is subject to FORCE RLS.
  `eldering_app` is used by the API (NOBYPASSRLS, not the owner, no TRUNCATE or DDL, column-limited
  UPDATE on incidents and users). `eldering_definer` is NOLOGIN with BYPASSRLS and owns the
  `SECURITY DEFINER` helpers in schema `rls`. Those helpers have a pinned `search_path` and no
  PUBLIC execute.
- Visibility rules (SPEC) are implemented as `rls.can_see_location` and `rls.can_see_incidents`.
  When no grant row exists, the level is `emergency`, so only the sharer ever writes their own
  rows. The app can call only helpers that answer relative to the caller (`rls.co_member`,
  `rls.location_status`, ...), not the two-user internals.
- Share links carry 32 random bytes (base64url). Only the SHA-256 is stored. They are looked up
  anonymously through `rls.shared_incident` and `rls.shared_trail`. Unknown, expired and malformed
  links all return the same 404. A resolved incident keeps the page but hides its location and
  trail. The page shows no name, email or user id.
- Passwords use argon2id (m = 19 MiB, t = 2, p = 1), with at most 2 hashes in flight. A login for
  an unknown email still runs a dummy verify, so timing does not reveal accounts. Hashes are
  re-hashed when the parameters are upgraded. Each computation ends with a GC while it still holds
  its slot, so the 19 MiB scratch buffers never pile up as garbage. Before this, about 14
  concurrent sign-ups OOM-killed the API in its 128 MiB container. `GOMEMLIMIT` is 80MiB; it
  covers only Go-managed memory and leaves room for the binary and Go 1.26's 32 MiB
  `fips140/drbg` scratch buffer.
- Per-IP rate limits key IPv6 clients by their /64, so rotating addresses inside one
  subscriber prefix does not reset them. nginx overwrites `X-Real-IP`; `X-Forwarded-For` is never
  trusted.
- An admin removing a member revokes the family's outstanding invite codes, which the removed
  member may know, so they cannot re-join at once and regain the default `emergency` visibility.
  Leaving voluntarily keeps the codes.
- Logs record route patterns (`/s/{token}`), never raw paths or tokens. nginx also redacts `/s/`
  in its access log. nginx applies its coarse 10/min limit only to `login` and `register`, and
  answers its own 413/429 with the same JSON error envelope.

## Deviations from SPEC.md (deliberate)

1. **Three DB roles** (owner/app/definer) instead of one non-superuser role. The app role meets
   SPEC. The other two make FORCE RLS meaningful and avoid recursive policies.
2. **`set_config('app.user_id', $1, true)`** instead of a literal `SET LOCAL`. The semantics are
   the same, and it is parameterized.
3. **The default `emergency` grant is computed**: it is the absence of a row, not an inserted row.
   A trigger deletes grants between users who no longer share any family, so a re-join starts
   again from the default.
4. **Roles are bootstrapped by `eldering-api migrate`** (superuser, idempotent, SCRAM verifiers),
   not by a one-time initdb script. This also keeps role passwords in sync with `.env`.
5. **Additive API details**: `PATCH /v1/families/{id}` (rename, which SPEC's RBAC mentions but its
   API table omits); an optional `Idempotency-Key` on `POST /v1/incidents` (column
   `incidents.client_key`); extra response fields (`is_me`, `is_default`,
   `effective_sharing_state`, `last_location`, `is_mine`, `my_ack`); and `created_at` on several
   tables.
6. **Resolved incidents keep their share link**, which still answers until it expires and shows
   the resolution, **but location and trail are withheld**. This is data minimization.
7. **Sharing `off` discards the stored latest location.**
8. Unknown JSON fields are **ignored**, not rejected. A client adding a field must never break an
   emergency upload.
