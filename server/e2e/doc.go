// Package e2e is a black-box RBAC and security suite for a running Eldering API.
//
// It talks to the server over HTTP only, creating throwaway accounts
// (e2e-sec-<random>@example.test), and checks every SPEC RBAC rule with positive
// and negative cases, plus authentication, token handling, rate limits, input
// hardening and existence leaks. One optional test also runs
// rls_app_role.sql through psql, which checks row-level security directly in
// PostgreSQL as the app role.
//
// Without E2E_BASE_URL the package does nothing, so `go test ./...` stays
// hermetic. Configuration (environment):
//
//	E2E_BASE_URL           required, e.g. http://127.0.0.1:8095 or https://safety.example
//	E2E_TRUSTED_IP_HEADER  "1" when the API is reached directly and trusts X-Real-IP
//	                       (local compose stack). Each actor then gets its own
//	                       synthetic client IP, so per-IP limits do not slow the
//	                       suite, and the exact limiter thresholds are asserted.
//	                       Leave unset behind nginx: the suite then checks that
//	                       spoofed X-Real-IP / X-Forwarded-For do not bypass limits
//	                       and waits out 429s during setup.
//	E2E_JWT_SECRET         optional hex JWT_SECRET: enables checks of correctly
//	                       signed tokens with bad claims (exp, nbf, iss, aud, sub).
//	E2E_PSQL               optional command that runs psql as the database
//	                       superuser and reads SQL from stdin. Enables the direct
//	                       RLS checks and the share-link expiry check, e.g.
//	                       docker compose -f server/docker-compose.yml exec -T db psql -X -q -U postgres -d eldering
//	E2E_MATRIX_FILE        optional path: also write the result matrix as Markdown.
//
// Run against the local stack with `make e2e` (see the Makefile), or e.g.
//
//	E2E_BASE_URL=https://safety.example.com go test -count=1 -v -timeout 30m ./e2e
//
// Remove the throwaway accounts afterwards with cleanup.sql (`make e2e-clean`).
package e2e
