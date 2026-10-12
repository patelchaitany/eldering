# Eldering backend. Run `make help` for targets.
SHELL := /bin/sh
GO    ?= go
TAG   ?= dev
TEST_DB_PORT ?= 55433

.PHONY: help fmt fmt-check vet test test-unit test-race testdb-up testdb-down build image env up down logs smoke e2e e2e-rls e2e-clean deploy deploy-nginx deploy-cert deploy-status vendor-maplibre

help: ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  %-16s %s\n", $$1, $$2}'

fmt: ## gofmt all sources
	gofmt -w cmd internal

fmt-check: ## fail if any file needs gofmt
	@test -z "$$(gofmt -l cmd internal)" || (gofmt -l cmd internal; exit 1)

vet: ## go vet
	$(GO) vet ./...

test: testdb-up ## all tests; integration tests are REQUIRED to run (real PostGIS via docker compose)
	ELDERING_REQUIRE_DB=1 ELDERING_TEST_DB_PORT=$(TEST_DB_PORT) $(GO) test -count=1 ./...

test-race: testdb-up ## all tests with the race detector
	ELDERING_REQUIRE_DB=1 ELDERING_TEST_DB_PORT=$(TEST_DB_PORT) $(GO) test -race -count=1 ./...

test-unit: ## unit tests only (no database)
	$(GO) test -short -count=1 ./...

testdb-up: ## start the throwaway test database (127.0.0.1:$(TEST_DB_PORT), tmpfs)
	ELDERING_TEST_DB_PORT=$(TEST_DB_PORT) docker compose -f docker-compose.test.yml up -d --wait

testdb-down: ## remove the test database
	docker compose -f docker-compose.test.yml down -v

build: ## static linux/amd64 binary in dist/
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -trimpath -ldflags="-s -w -X main.version=$(TAG)" -o dist/eldering-api ./cmd/api

image: ## container image eldering-api:$(TAG) for linux/amd64
	docker buildx build --platform linux/amd64 --build-arg VERSION=$(TAG) -t eldering-api:$(TAG) --load .

env: ## create a local .env with random secrets (never overwrites)
	@if [ -f .env ]; then echo ".env exists, leaving it alone"; else \
	  umask 077; printf 'POSTGRES_PASSWORD=%s\nELDERING_OWNER_PASSWORD=%s\nELDERING_APP_PASSWORD=%s\nJWT_SECRET=%s\nAPI_TAG=$(TAG)\nPUBLIC_BASE_URL=http://127.0.0.1:8095\n' \
	    "$$(openssl rand -hex 32)" "$$(openssl rand -hex 32)" "$$(openssl rand -hex 32)" "$$(openssl rand -hex 32)" > .env; \
	  echo "wrote .env"; fi

up: env image ## run the full stack locally on 127.0.0.1:8095
	API_TAG=$(TAG) docker compose up -d
	@$(MAKE) --no-print-directory smoke

down: ## stop the local stack (keeps the volume)
	docker compose down

logs: ## follow local stack logs
	docker compose logs -f --tail 100

smoke: ## wait for /healthz on 127.0.0.1:8095
	@i=0; until curl -fsS http://127.0.0.1:8095/healthz; do i=$$((i+1)); [ $$i -gt 40 ] && exit 1; sleep 2; done; echo

# Black-box RBAC/security suite (server/e2e). Defaults target the local stack from `make up`:
# the API is reached directly, so X-Real-IP is trusted and every test actor gets its own IP.
E2E_BASE_URL ?= http://127.0.0.1:8095
E2E_PSQL     ?= docker compose -f $(CURDIR)/docker-compose.yml exec -T db psql -X -q -U postgres -d eldering

e2e: ## black-box RBAC/security suite against $(E2E_BASE_URL) (local stack; matrix in dist/e2e-matrix.md)
	@mkdir -p dist
	E2E_BASE_URL=$(E2E_BASE_URL) E2E_TRUSTED_IP_HEADER=1 E2E_PSQL='$(E2E_PSQL)' \
	  E2E_JWT_SECRET="$$(sed -n 's/^JWT_SECRET=//p' .env)" E2E_MATRIX_FILE=$(CURDIR)/dist/e2e-matrix.md \
	  $(GO) test -count=1 -v -timeout 20m ./e2e

e2e-rls: ## only the direct SQL row-level-security checks (as eldering_app) against the local db
	@out=$$($(E2E_PSQL) < e2e/rls_app_role.sql 2>&1); \
	  echo "$$out" | grep -E '(PASS|FAIL|SKIP):|RLS-CHECKS|ERROR'; \
	  echo "$$out" | grep -q RLS-CHECKS-COMPLETE && ! echo "$$out" | grep -q 'FAIL:'

e2e-clean: ## delete the e2e-*@example.test accounts (and their families) from the local db
	$(E2E_PSQL) -v ON_ERROR_STOP=1 < e2e/cleanup.sql

deploy: ## build + ship + restart on the server (see deploy/deploy.sh)
	./deploy/deploy.sh app

deploy-nginx: ## install the HTTP-only nginx site on the server
	./deploy/deploy.sh nginx

deploy-cert: ## obtain the TLS certificate (certbot --nginx, this site only)
	./deploy/deploy.sh cert

deploy-status: ## show server containers + health
	./deploy/deploy.sh status

MAPLIBRE_VERSION   := 6.11.2
MAPLIBRE_INTEGRITY := sha512-Xh06pxoipjX/Ad1sUPGhiNh9go9naCNGZZ1IJm3sIeqK1kYGuMaMDSTIGDQ2igOLfVyukJU7kvEOA089VO7Z7g==
vendor-maplibre: ## re-vendor MapLibre GL JS from the npm tarball (integrity-checked)
	@tmp=$$(mktemp -d); trap 'rm -rf "$$tmp"' EXIT; \
	curl -fsSL -o "$$tmp/m.tgz" https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-$(MAPLIBRE_VERSION).tgz; \
	got="sha512-$$(openssl dgst -sha512 -binary "$$tmp/m.tgz" | base64)"; \
	[ "$$got" = "$(MAPLIBRE_INTEGRITY)" ] || { echo "integrity mismatch: $$got"; exit 1; }; \
	tar -xzf "$$tmp/m.tgz" -C "$$tmp" package/dist package/LICENSE.txt; \
	out=internal/sharepage/static/maplibre-gl/$(MAPLIBRE_VERSION); mkdir -p "$$out"; \
	cp "$$tmp"/package/dist/maplibre-gl.mjs "$$tmp"/package/dist/maplibre-gl-shared.mjs \
	   "$$tmp"/package/dist/maplibre-gl-worker.mjs "$$tmp"/package/dist/maplibre-gl.css "$$tmp"/package/LICENSE.txt "$$out"/; \
	echo "vendored MapLibre GL JS $(MAPLIBRE_VERSION) into $$out"
