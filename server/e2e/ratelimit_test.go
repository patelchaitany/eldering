package e2e

import (
	"fmt"
	"net/http"
	"strconv"
	"testing"
)

const inviteAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

func randInviteCode() string {
	b := randBytes(8)
	for i := range b {
		b[i] = inviteAlphabet[b[i]&31]
	}
	return string(b)
}

// firstLimited sends up to attempts requests and returns the index of the first
// 429 (-1 if none). Every other response must have status ok.
func firstLimited(t *testing.T, attempts, ok int, mk func(i int) request) (int, *response) {
	t.Helper()
	for i := range attempts {
		r := send(t, mk(i))
		if r.status == http.StatusTooManyRequests {
			return i, r
		}
		if r.status != ok {
			t.Fatalf("request %d: want %d (or 429), got %s", i+1, ok, r)
		}
	}
	return -1, nil
}

// expectLimited asserts the limiter kicked in. With exact=true (the suite owns
// the bucket) the 429 must come right after the burst and carry the JSON
// envelope and a Retry-After header.
func expectLimited(t *testing.T, idx int, r *response, burst int, exact bool) {
	t.Helper()
	if idx < 0 {
		t.Fatalf("no 429 was returned")
	}
	if !exact {
		return
	}
	if idx < burst || idx > burst+1 { // +1 tolerates one token refilling meanwhile
		t.Fatalf("want the 429 after a burst of %d, got it on request %d", burst, idx+1)
	}
	wantErr(t, r, http.StatusTooManyRequests, "rate_limited")
	if s, err := strconv.Atoi(r.header.Get("Retry-After")); err != nil || s < 1 {
		t.Fatalf("429 without a usable Retry-After: %v", r.header)
	}
}

func loginRequest(ip, email, password string, header map[string]string) request {
	return request{method: http.MethodPost, path: "/v1/auth/login", ip: ip, header: header,
		body: map[string]string{"email": email, "password": password}}
}

func TestRateLimits(t *testing.T) {
	t.Parallel()
	exact := cfg.trustedIP // otherwise other traffic from this IP shares the buckets
	attempts := 10 + 2
	if !exact {
		attempts = 40
	}

	check(t, "auth_login_limited_per_ip", func(t *testing.T) {
		ip := newIP()
		idx, r := firstLimited(t, attempts, http.StatusUnauthorized, func(int) request { return loginRequest(ip, randomEmail(), "x-wrong", nil) })
		expectLimited(t, idx, r, 10, exact)
	})

	check(t, "auth_register_limited_per_ip", func(t *testing.T) {
		ip := newIP()
		idx, r := firstLimited(t, attempts, http.StatusBadRequest, func(int) request {
			return request{method: http.MethodPost, path: "/v1/auth/register", ip: ip, body: registerBody("not-an-email", "x", "x")}
		})
		expectLimited(t, idx, r, 10, exact)
	})

	check(t, "x_forwarded_for_rotation_does_not_bypass", func(t *testing.T) {
		ip := newIP()
		idx, r := firstLimited(t, attempts, http.StatusUnauthorized, func(i int) request {
			return loginRequest(ip, randomEmail(), "x-wrong", map[string]string{"X-Forwarded-For": newIP()})
		})
		expectLimited(t, idx, r, 10, exact)
	})

	check(t, "x_real_ip_rotation_does_not_bypass_the_proxy", func(t *testing.T) {
		if cfg.trustedIP {
			skip(t, "API reached directly: X-Real-IP is trusted by design (port is loopback-only; nginx overwrites it)")
		}
		idx, r := firstLimited(t, attempts, http.StatusUnauthorized, func(i int) request {
			return loginRequest("", randomEmail(), "x-wrong", map[string]string{"X-Real-IP": newIP(), "True-Client-IP": newIP()})
		})
		expectLimited(t, idx, r, 10, false)
	})

	check(t, "ipv6_rotation_within_one_64_does_not_bypass", func(t *testing.T) {
		if !cfg.trustedIP {
			skip(t, "needs E2E_TRUSTED_IP_HEADER=1 to present IPv6 client addresses")
		}
		b := randBytes(4)
		prefix := fmt.Sprintf("2001:db8:%x:%x", int(b[0])<<8|int(b[1]), int(b[2])<<8|int(b[3]))
		idx, r := firstLimited(t, attempts, http.StatusUnauthorized, func(i int) request {
			return loginRequest("", randomEmail(), "x-wrong", map[string]string{"X-Real-IP": fmt.Sprintf("%s::%x:%x", prefix, i+1, randBytes(1)[0])})
		})
		expectLimited(t, idx, r, 10, true)
	})

	check(t, "login_limited_per_account_across_ips", func(t *testing.T) {
		if !cfg.trustedIP {
			skip(t, "needs E2E_TRUSTED_IP_HEADER=1 to vary the client IP")
		}
		a := newActor(t, "ratelimit-account")
		idx, r := firstLimited(t, 8, http.StatusUnauthorized, func(int) request { return loginRequest(newIP(), a.email, "x-wrong", nil) })
		expectLimited(t, idx, r, 5, true)
		annotate(t, "documented: after 5 failures/account the correct password is also refused for ~1 min (refresh tokens keep existing sessions alive)")
		wantErr(t, send(t, loginRequest(newIP(), a.email, a.password, nil)), http.StatusTooManyRequests, "rate_limited")
	})

	check(t, "unknown_account_limited_identically", func(t *testing.T) {
		if !cfg.trustedIP {
			skip(t, "needs E2E_TRUSTED_IP_HEADER=1 to vary the client IP")
		}
		email := randomEmail()
		idx, r := firstLimited(t, 8, http.StatusUnauthorized, func(int) request { return loginRequest(newIP(), email, "x-wrong", nil) })
		expectLimited(t, idx, r, 5, true)
	})

	check(t, "refresh_limited_per_ip", func(t *testing.T) {
		ip := newIP()
		m := 30 + 2
		if !exact {
			m = 90
		}
		idx, r := firstLimited(t, m, http.StatusUnauthorized, func(int) request {
			return request{method: http.MethodPost, path: "/v1/auth/refresh", ip: ip, body: map[string]string{"refresh_token": randToken()}}
		})
		expectLimited(t, idx, r, 30, exact)
	})

	check(t, "share_page_limited_per_ip", func(t *testing.T) {
		ip := newIP()
		m := 30 + 2
		if !exact {
			m = 90
		}
		idx, r := firstLimited(t, m, http.StatusNotFound, func(int) request {
			return request{method: http.MethodGet, path: "/s/" + randToken() + "/data", ip: ip}
		})
		expectLimited(t, idx, r, 30, exact)
	})

	check(t, "family_join_limited_per_user", func(t *testing.T) {
		a := newActor(t, "ratelimit-join")
		idx, r := firstLimited(t, 12, http.StatusNotFound, func(int) request {
			return request{method: http.MethodPost, path: "/v1/families/join", token: a.access, ip: newIP(),
				body: map[string]string{"code": randInviteCode()}}
		})
		expectLimited(t, idx, r, 10, true) // keyed by account, so exact even behind a proxy
	})
}
