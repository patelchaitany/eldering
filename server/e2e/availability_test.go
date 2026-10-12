package e2e

import (
	"net/http"
	"sync"
	"testing"
	"time"
)

// TestAvailability runs before the parallel tests: a burst of concurrent
// sign-ups (argon2id, 19 MiB each) must queue on the hashing semaphore, not
// take the server down. Before the fix in internal/auth/password.go, ~14
// concurrent sign-ups OOM-killed the API in its 128 MiB container.
func TestAvailability(t *testing.T) {
	check(t, "burst_of_concurrent_signups_is_absorbed", func(t *testing.T) {
		const n = 40
		var wg sync.WaitGroup
		results := make(chan string, n)
		for range n {
			wg.Add(1)
			go func() {
				defer wg.Done()
				r, err := doRequest(request{method: http.MethodPost, path: "/v1/auth/register", ip: newIP(),
					body: registerBody(randomEmail(), "pw-"+randHex(10), "e2e burst")})
				switch {
				case err != nil:
					results <- "transport error: " + err.Error()
				case r.status == http.StatusCreated:
					results <- ""
				case r.status == http.StatusTooManyRequests && !cfg.trustedIP:
					results <- "" // behind a proxy the whole burst shares one client IP
				default:
					results <- r.String()
				}
			}()
		}
		wg.Wait()
		close(results)
		failed := 0
		for res := range results {
			if res != "" {
				failed++
				if failed <= 3 {
					t.Error(res)
				}
			}
		}
		if failed > 0 {
			t.Errorf("%d of %d concurrent sign-ups failed", failed, n)
		}
		// Whatever happened, give a restarted server a moment before the next tests.
		waitFor(t, 60*time.Second, "healthz", func() bool {
			r, err := doRequest(request{method: http.MethodGet, path: "/healthz"})
			return err == nil && r.status == http.StatusOK
		})
	})
}
