# Deploying the Eldering backend to a shared VM

Runbook for `deploy/deploy.sh`. It assumes a small Ubuntu VM (about 1 GB RAM) that **already
runs other people's sites and containers**. Every step touches only our stack:

| What | Where |
|---|---|
| Compose project | `eldering` in `~/eldering-safety/` (`docker-compose.yml` + `.env`) |
| Containers | `eldering-db-1` (PostGIS, 256 MiB), `eldering-api-1` (128 MiB), one-shot `eldering-migrate-1` (64 MiB) |
| Images | `eldering-api:<UTC timestamp>` (the 3 newest are kept), `postgis/postgis` (pinned digest) |
| Port | API on `127.0.0.1:8095` only; Postgres is not published |
| nginx | `/etc/nginx/sites-available/eldering` + symlink in `sites-enabled/` |
| TLS | one certificate for `<ELDERING_DOMAIN>` from `certbot --nginx -d <ELDERING_DOMAIN>` |
| Secrets | `~/eldering-safety/.env` (chmod 600), generated on the server, never printed |

Nothing else is touched. Do not run `docker compose down`, `docker system prune`, `certbot renew
--force-renewal`, or edit other `sites-*` files on the server.

## 1. Prerequisites

Local machine: Docker with `buildx` (it cross-builds `linux/amd64`), `ssh`, `curl`, Go (for the
e2e suite).

Server: Docker + compose v2, nginx, certbot with its nginx plugin, SSH key login with
passwordless `sudo`, and ports 80/443 open. `<ELDERING_DOMAIN>` must already resolve to the
server (an `sslip.io` name such as `safety.<server-ip>.sslip.io` works without any DNS setup).

```sh
cp deploy/deploy.env.example deploy/deploy.env   # not committed
# ELDERING_HOST=<user>@<server>
# ELDERING_SSH_KEY=$HOME/.ssh/<key>
# ELDERING_DOMAIN=<ELDERING_DOMAIN>
```

## 2. Pre-flight

```sh
./deploy/deploy.sh sites > /tmp/eldering-before.txt   # read-only
cat /tmp/eldering-before.txt
```

`sites` asks every `server_name` nginx serves (over loopback, with the right SNI) for its status,
with the certificate verified (`verify=0` means valid), plus the default servers, every
container's state and the free memory. Check that:

- `mem ... available=` is at least **~400 MiB**. Our stack is capped at 256 + 128 MiB (+64 MiB
  briefly for `migrate`) and needs about 150 MiB in practice.
- No other process already listens on `127.0.0.1:8095` (`ssh <user>@<server> 'sudo ss -ltnp'`).

## 3. Deploy

```sh
./deploy/deploy.sh app      # build eldering-api:<tag> for linux/amd64, ship it (docker save | ssh docker load),
                            # upload docker-compose.yml, create .env on first run, `compose up -d`, wait for /healthz
./deploy/deploy.sh nginx    # first time only: HTTP-only site file; `nginx -t` before reload, removes the
                            # symlink again if the test fails. Refuses to overwrite a site certbot has edited.
./deploy/deploy.sh cert     # first time only: certbot --nginx --redirect -d <ELDERING_DOMAIN>, then https /healthz
./deploy/deploy.sh status   # our containers + loopback /healthz
```

Later code updates need only `./deploy/deploy.sh app`. Each `up` re-runs `migrate`, which is
idempotent (it converges roles, re-applies role passwords from `.env` and applies new
migrations).

`deploy/nginx/eldering.conf` is written for nginx 1.18 (Ubuntu 20.04): no `http2`, no
`ipv6only=` (another site may already own that option on `[::]:443`), and `map`/`log_format`/
`limit_req_zone` names prefixed with `eldering_` because they are global.

## 4. Verify

```sh
curl -fsS https://<ELDERING_DOMAIN>/healthz            # {"status":"ok"} over a valid certificate
curl -sSI http://<ELDERING_DOMAIN>/healthz | head -3   # 301 to https
./deploy/deploy.sh status                              # eldering-db-1 and eldering-api-1 (healthy)
./deploy/deploy.sh sites > /tmp/eldering-after.txt
diff /tmp/eldering-before.txt /tmp/eldering-after.txt
```

The only expected differences are the two new `<ELDERING_DOMAIN>` lines, the three `eldering-*`
containers and the memory figures. Any other site whose status or `verify=` changed is a
regression: disable our site (see Rollback) and investigate.

Memory after the deploy: `ssh <user>@<server> 'free -m; docker stats --no-stream'`. Expect the
API at about 10-30 MiB and Postgres at about 40-90 MiB.

## 5. Black-box suite against production

The suite in `e2e/` uses throwaway accounts `e2e-sec-<random>@example.test`. Behind nginx leave
`E2E_TRUSTED_IP_HEADER` unset; setup then waits out the 10/min sign-up limit (allow about
10 minutes). Skip the two tests that deliberately hammer the rate limits and the password
hasher on the shared box:

```sh
. deploy/deploy.env
E2E_BASE_URL=https://$ELDERING_DOMAIN \
E2E_PSQL="ssh -i $ELDERING_SSH_KEY $ELDERING_HOST 'cd ~/eldering-safety && docker compose -p eldering exec -T db psql -X -q -U postgres -d eldering'" \
  go test -count=1 -v -timeout 40m -skip 'TestAvailability|TestRateLimits' ./e2e
```

`E2E_PSQL` enables the direct row-level-security checks (`e2e/rls_app_role.sql`; its fixtures
are rolled back) and the share-link expiry check. Do not pass `E2E_JWT_SECRET` for production:
it would mean copying the server's secret off the box.

Then delete the throwaway accounts and their families, and confirm none are left. `cleanup.sql`
removes every `e2e-*@example.test` account, so it also covers the Android
`LiveServerContractTest` accounts (`e2e-android-*`) and any ad-hoc test accounts named that way:

```sh
ssh -i "$ELDERING_SSH_KEY" "$ELDERING_HOST" \
  'cd ~/eldering-safety && docker compose -p eldering exec -T db psql -X -q -v ON_ERROR_STOP=1 -U postgres -d eldering' < e2e/cleanup.sql
ssh -i "$ELDERING_SSH_KEY" "$ELDERING_HOST" \
  "cd ~/eldering-safety && docker compose -p eldering exec -T db psql -X -tA -U postgres -d eldering -c \"select count(*) from users where email like 'e2e-%@example.test'\""
```

## 6. Rollback

- Previous API version: on the server, `cd ~/eldering-safety`, set `API_TAG=<older tag>` in
  `.env` (`docker images eldering-api` lists the kept tags), then `docker compose -p eldering up -d`.
- Take our site off nginx (other sites are unaffected):
  `sudo rm /etc/nginx/sites-enabled/eldering && sudo nginx -t && sudo systemctl reload nginx`.
- Stop our stack, keeping the data: `cd ~/eldering-safety && docker compose -p eldering stop`.
  `down -v` would also delete the database volume.

## 7. Operations

- Logs: `docker compose -p eldering logs --tail 100 api` (route patterns only, never tokens);
  nginx: `/var/log/nginx/eldering.access.log` (share paths are redacted).
- Backup (must run as the superuser, because the owner role errors on FORCEd RLS tables):
  `docker compose -p eldering exec -T db pg_dump -U postgres -Fc eldering > backup.dump`.
- Certificate renewal is handled by the existing certbot timer; check with `sudo certbot certificates`.
- Add a second domain: add it to `server_name` in `sites-available/eldering`, then
  `sudo certbot --nginx -d <ELDERING_DOMAIN> -d <NEW_DOMAIN> --expand`, set
  `PUBLIC_BASE_URL=https://<NEW_DOMAIN>` in `~/eldering-safety/.env`, run
  `docker compose -p eldering up -d`, and update `eldering.baseUrl` in `android/local.properties`.
- Never print `.env`, and never run `docker compose config` on the server without `--quiet`.
