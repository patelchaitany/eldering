package e2e

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestAuthSessions(t *testing.T) {
	t.Parallel()
	a := newActor(t, "auth")

	check(t, "register_normalises_email_and_returns_no_secrets", func(t *testing.T) {
		email := "E2E-SEC-" + strings.ToUpper(randHex(6)) + "@Example.TEST"
		r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: newIP(),
			body: registerBody(email, "pw-"+randHex(10), "e2e case")})
		want(t, r, http.StatusCreated)
		var ar authResp
		r.decode(t, &ar)
		if ar.User.Email != strings.ToLower(email) {
			t.Fatalf("email not normalised: %q", ar.User.Email)
		}
		if ar.TokenType != "Bearer" || ar.ExpiresIn != 900 {
			t.Fatalf("want a 15 min Bearer access token, got %q/%d", ar.TokenType, ar.ExpiresIn)
		}
		notContains(t, r, "password", "argon2", "hash")
	})

	check(t, "duplicate_email_conflict", func(t *testing.T) {
		annotate(t, "documented: self-service sign-up reveals that an email is registered (409); bounded by the per-IP limit")
		r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: newIP(),
			body: registerBody(strings.ToUpper(a.email), "pw-"+randHex(10), "dup")})
		wantErr(t, r, http.StatusConflict, "email_taken")
	})

	check(t, "login_unknown_email_indistinguishable_from_wrong_password", func(t *testing.T) {
		wrong := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/login", ip: newIP(),
			body: map[string]string{"email": a.email, "password": "wrong-" + randHex(4)}})
		unknown := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/login", ip: newIP(),
			body: map[string]string{"email": randomEmail(), "password": "wrong-" + randHex(4)}})
		wantErr(t, wrong, http.StatusUnauthorized, "invalid_credentials")
		sameResponse(t, wrong, unknown)
	})

	check(t, "login_with_correct_password", func(t *testing.T) {
		if ar := a.login(t); ar.User.ID != a.id || ar.RefreshToken == "" {
			t.Fatalf("unexpected login: %+v", ar)
		}
	})

	var r1, r2 string
	check(t, "refresh_rotates_token", func(t *testing.T) {
		r1 = a.refresh
		r := refresh(t, a.ip, r1)
		want(t, r, http.StatusOK)
		var ar authResp
		r.decode(t, &ar)
		r2 = ar.RefreshToken
		if r2 == "" || r2 == r1 || ar.User.ID != a.id {
			t.Fatalf("refresh did not rotate: %+v", ar)
		}
		if me := send(t, request{method: http.MethodGet, path: "/v1/me", token: ar.AccessToken, ip: a.ip}); me.status != http.StatusOK {
			t.Fatalf("new access token rejected: %s", me)
		}
	})

	check(t, "refresh_reuse_revokes_every_session", func(t *testing.T) {
		if r1 == "" {
			t.Skip("needs refresh_rotates_token")
		}
		other := a.login(t) // an unrelated session (another device)
		wantErr(t, refresh(t, a.ip, r1), http.StatusUnauthorized, "invalid_refresh_token")
		wantErr(t, refresh(t, a.ip, r2), http.StatusUnauthorized, "invalid_refresh_token")
		wantErr(t, refresh(t, a.ip, other.RefreshToken), http.StatusUnauthorized, "invalid_refresh_token")
	})

	check(t, "logout_revokes_token_and_is_idempotent", func(t *testing.T) {
		s := a.login(t)
		logout := func(tok string) *response {
			return sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/logout", ip: a.ip,
				body: map[string]string{"refresh_token": tok}})
		}
		want(t, logout(s.RefreshToken), http.StatusNoContent)
		wantErr(t, refresh(t, a.ip, s.RefreshToken), http.StatusUnauthorized, "invalid_refresh_token")
		want(t, logout(s.RefreshToken), http.StatusNoContent)
		want(t, logout(randToken()), http.StatusNoContent)
		want(t, logout("garbage"), http.StatusNoContent)
		annotate(t, "documented: an access JWT stays valid until it expires (<=15 min) after logout")
	})

	check(t, "malformed_refresh_tokens_rejected_uniformly", func(t *testing.T) {
		base := refresh(t, a.ip, randToken())
		wantErr(t, base, http.StatusUnauthorized, "invalid_refresh_token")
		for _, tok := range []string{"", "x", randToken()[:42], randToken() + "A", "' OR '1'='1", a.access,
			strings.Repeat("A", 43), "../../../../etc/passwd" + strings.Repeat("A", 21)} {
			sameResponse(t, base, refresh(t, a.ip, tok))
		}
	})

	check(t, "access_and_refresh_tokens_not_interchangeable", func(t *testing.T) {
		s := a.login(t)
		r := send(t, request{method: http.MethodGet, path: "/v1/me", token: s.RefreshToken, ip: a.ip})
		wantErr(t, r, http.StatusUnauthorized, "unauthorized")
		wantErr(t, refresh(t, a.ip, s.AccessToken), http.StatusUnauthorized, "invalid_refresh_token")
	})
}

// ---------------------------------------------------------------------------
// JWT tampering

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func jwtSegment(v any) string {
	j, _ := json.Marshal(v)
	return b64url(j)
}

func signHS256(key []byte, header, claims map[string]any) string {
	signing := jwtSegment(header) + "." + jwtSegment(claims)
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(signing))
	return signing + "." + b64url(mac.Sum(nil))
}

func splitJWT(t testing.TB, tok string) (header, payload, sig string) {
	t.Helper()
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatalf("not a JWT: %q", tok)
	}
	return parts[0], parts[1], parts[2]
}

func claimsFor(sub string, now time.Time) map[string]any {
	return map[string]any{
		"iss": "eldering", "aud": []string{"eldering-api"}, "sub": sub,
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(10 * time.Minute).Unix(),
	}
}

func TestJWTTampering(t *testing.T) {
	t.Parallel()
	a, b := newActor(t, "jwt-a"), newActor(t, "jwt-b")
	me := func(t *testing.T, authorization string) *response {
		t.Helper()
		return send(t, request{method: http.MethodGet, path: "/v1/me", ip: a.ip,
			header: map[string]string{"Authorization": authorization}})
	}
	rejected := func(t *testing.T, authorization string) {
		t.Helper()
		r := me(t, authorization)
		wantErr(t, r, http.StatusUnauthorized, "unauthorized")
		if !strings.HasPrefix(r.header.Get("WWW-Authenticate"), "Bearer") {
			t.Fatalf("401 without a Bearer challenge: %v", r.header)
		}
	}

	check(t, "valid_token_control", func(t *testing.T) {
		var m meJSON
		r := me(t, "Bearer "+a.access)
		want(t, r, http.StatusOK)
		r.decode(t, &m)
		if m.User.ID != a.id {
			t.Fatalf("token resolved to %s, want %s", m.User.ID, a.id)
		}
	})

	check(t, "missing_or_malformed_authorization_header", func(t *testing.T) {
		for _, h := range []string{"", "Bearer", "Bearer ", "Bearer    ", a.access, "Basic " + b64url([]byte("a:b")),
			"Token " + a.access, "Bearer " + a.access + "x", "Bearer " + a.access + " " + b.access} {
			rejected(t, h)
		}
	})

	check(t, "alg_none_rejected", func(t *testing.T) {
		claims := claimsFor(b.id, time.Now())
		for _, alg := range []string{"none", "None", "NONE", "nOnE"} {
			h := jwtSegment(map[string]any{"alg": alg, "typ": "JWT"})
			p := jwtSegment(claims)
			rejected(t, "Bearer "+h+"."+p+".")
			rejected(t, "Bearer "+h+"."+p)
		}
	})

	check(t, "payload_tampering_rejected", func(t *testing.T) {
		h, p, s := splitJWT(t, a.access)
		raw, err := base64.RawURLEncoding.DecodeString(p)
		if err != nil {
			t.Fatal(err)
		}
		var claims map[string]any
		if err := json.Unmarshal(raw, &claims); err != nil {
			t.Fatal(err)
		}
		claims["sub"] = b.id // impersonate b with a's signature
		rejected(t, "Bearer "+h+"."+jwtSegment(claims)+"."+s)
		claims["sub"] = a.id
		claims["exp"] = time.Now().Add(365 * 24 * time.Hour).Unix() // extend lifetime
		rejected(t, "Bearer "+h+"."+jwtSegment(claims)+"."+s)
	})

	check(t, "header_alg_swap_rejected", func(t *testing.T) {
		_, p, s := splitJWT(t, a.access)
		for _, alg := range []string{"HS384", "HS512", "RS256", "ES256", "PS256", "EdDSA"} {
			rejected(t, "Bearer "+jwtSegment(map[string]any{"alg": alg, "typ": "JWT"})+"."+p+"."+s)
		}
	})

	check(t, "signature_transplant_and_truncation_rejected", func(t *testing.T) {
		ha, pa, sa := splitJWT(t, a.access)
		_, _, sb := splitJWT(t, b.access)
		rejected(t, "Bearer "+ha+"."+pa+"."+sb)
		rejected(t, "Bearer "+ha+"."+pa+".")
		rejected(t, "Bearer "+ha+"."+pa+"."+sa[:len(sa)-4])
	})

	check(t, "garbage_tokens_rejected", func(t *testing.T) {
		for _, tok := range []string{"abc", "a.b.c", "....", "e30.e30.e30", strings.Repeat("A", 8000),
			"éé.é.é", "' OR '1'='1"} {
			rejected(t, "Bearer "+tok)
		}
	})

	check(t, "token_signed_with_another_key_rejected", func(t *testing.T) {
		rejected(t, "Bearer "+signHS256(randBytes(32), map[string]any{"alg": "HS256", "typ": "JWT"}, claimsFor(a.id, time.Now())))
	})

	check(t, "correctly_signed_tokens_with_bad_claims_rejected", func(t *testing.T) {
		if cfg.jwtSecret == nil {
			skip(t, "E2E_JWT_SECRET not set")
		}
		hdr := map[string]any{"alg": "HS256", "typ": "JWT"}
		now := time.Now()
		ok := claimsFor(a.id, now)
		if r := me(t, "Bearer "+signHS256(cfg.jwtSecret, hdr, ok)); r.status != http.StatusOK {
			t.Fatalf("control token with the real key rejected: %s", r)
		}
		mutate := func(f func(c map[string]any)) map[string]any {
			c := claimsFor(a.id, now)
			f(c)
			return c
		}
		cases := map[string]map[string]any{
			"expired":         mutate(func(c map[string]any) { c["exp"] = now.Add(-2 * time.Minute).Unix() }),
			"no_exp":          mutate(func(c map[string]any) { delete(c, "exp") }),
			"nbf_future":      mutate(func(c map[string]any) { c["nbf"] = now.Add(5 * time.Minute).Unix() }),
			"iat_future":      mutate(func(c map[string]any) { c["iat"] = now.Add(5 * time.Minute).Unix() }),
			"wrong_issuer":    mutate(func(c map[string]any) { c["iss"] = "attacker" }),
			"no_issuer":       mutate(func(c map[string]any) { delete(c, "iss") }),
			"wrong_audience":  mutate(func(c map[string]any) { c["aud"] = []string{"other-api"} }),
			"no_audience":     mutate(func(c map[string]any) { delete(c, "aud") }),
			"sub_not_uuid":    mutate(func(c map[string]any) { c["sub"] = "' OR '1'='1" }),
			"sub_nil_uuid":    mutate(func(c map[string]any) { c["sub"] = "00000000-0000-0000-0000-000000000000" }),
			"sub_missing":     mutate(func(c map[string]any) { delete(c, "sub") }),
			"sub_wrong_type":  mutate(func(c map[string]any) { c["sub"] = 12345 }),
			"exp_wrong_type":  mutate(func(c map[string]any) { c["exp"] = "tomorrow" }),
			"aud_wrong_type":  mutate(func(c map[string]any) { c["aud"] = 7 }),
			"iss_case_change": mutate(func(c map[string]any) { c["iss"] = "Eldering" }),
		}
		for name, c := range cases {
			if r := me(t, "Bearer "+signHS256(cfg.jwtSecret, hdr, c)); r.status != http.StatusUnauthorized {
				t.Errorf("%s: want 401, got %s", name, r)
			}
		}
	})

	check(t, "signed_token_for_unknown_user_is_harmless", func(t *testing.T) {
		if cfg.jwtSecret == nil {
			skip(t, "E2E_JWT_SECRET not set")
		}
		ghost := signHS256(cfg.jwtSecret, map[string]any{"alg": "HS256", "typ": "JWT"}, claimsFor(randUUID(), time.Now()))
		g := &actor{role: "ghost", access: ghost, ip: newIP()}
		wantErr(t, g.do(t, http.MethodGet, "/v1/me", nil), http.StatusUnauthorized, "unauthorized")
		if ls := g.locations(t); len(ls) != 0 {
			t.Fatalf("ghost sees locations: %+v", ls)
		}
		for _, r := range []*response{
			g.do(t, http.MethodPost, "/v1/families", map[string]string{"name": "ghost"}),
			g.do(t, http.MethodPost, "/v1/location/heartbeat", map[string]any{"lat": 1, "lng": 1, "recorded_at": time.Now().UTC()}),
			g.do(t, http.MethodPost, "/v1/incidents", incidentBody("sos", 1, 1, "")),
			g.do(t, http.MethodPatch, "/v1/me/settings", map[string]any{"sharing_state": "off"}),
		} {
			if r.status >= 500 || r.status < 400 {
				t.Errorf("ghost request must fail with a 4xx: %s", r)
			}
		}
	})
}
