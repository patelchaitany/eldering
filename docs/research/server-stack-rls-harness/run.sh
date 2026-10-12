#!/bin/sh
# Throwaway verification of the RLS design (roles, FORCE RLS, definer helpers, pooled set_config).
# Uses the exact production image (amd64; runs under Rosetta on Apple Silicon). Never touches the server.
set -eu
cd "$(dirname "$0")"
IMG=postgis/postgis:16-3.5-alpine@sha256:47e961a569fd52ff31f0fe205ed91eeab17d9f5fff6722e6d7ea6b588748b293
docker rm -f eldering-rlstest >/dev/null 2>&1 || true
docker run -d --name eldering-rlstest --platform linux/amd64 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=supertest -e POSTGRES_DB=eldering \
  -e ELDERING_OWNER_PASSWORD=ownertest -e ELDERING_APP_PASSWORD=apptest \
  -v "$PWD/initdb/20-eldering-roles.sh:/docker-entrypoint-initdb.d/20-eldering-roles.sh:ro" \
  -p 127.0.0.1:55432:5432 --memory 256m "$IMG" \
  postgres -c shared_buffers=64MB -c max_connections=30 -c jit=off >/dev/null
trap 'docker rm -f eldering-rlstest >/dev/null 2>&1 || true' EXIT
i=0
until docker logs eldering-rlstest 2>&1 | grep -q "PostgreSQL init process complete" &&
      docker exec eldering-rlstest pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; do
  i=$((i+1)); [ "$i" -gt 90 ] && { docker logs eldering-rlstest; exit 1; }; sleep 2
done
ELDERING_RLS_DB=127.0.0.1:55432 go test -count=1 -v ./...
