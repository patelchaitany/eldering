package e2e

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestBodyLimits(t *testing.T) {
	t.Parallel()
	a := newActor(t, "body")
	pad := func(n int) string { return strings.Repeat("a", n) }

	check(t, "oversized_body_with_content_length_is_413", func(t *testing.T) {
		body := `{"email":"e2e-sec-big@example.test","password":"` + pad(2<<20) + `","display_name":"x"}`
		r := send(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: newIP(), body: body})
		wantErr(t, r, http.StatusRequestEntityTooLarge, "body_too_large")
	})

	check(t, "oversized_chunked_body_is_413", func(t *testing.T) {
		b := incidentBody("sos", 1, 1, "")
		b["details"] = map[string]string{"padding": pad(2 << 20)}
		r := send(t, request{method: http.MethodPost, path: "/v1/incidents", token: a.access, ip: a.ip, body: b, chunked: true})
		wantErr(t, r, http.StatusRequestEntityTooLarge, "body_too_large")
	})

	check(t, "oversized_heartbeat_is_413", func(t *testing.T) {
		r := a.do(t, http.MethodPost, "/v1/location/heartbeat", map[string]any{"lat": 1, "lng": 1, "activity": pad(3 << 20)})
		wantErr(t, r, http.StatusRequestEntityTooLarge, "body_too_large")
	})

	check(t, "large_valid_upload_under_the_limit_is_accepted", func(t *testing.T) {
		now := time.Now()
		pts := make([]any, 5000)
		for i := range pts {
			pts[i] = trailPoint(52+float64(i)*1e-5, 4, now.Add(-time.Duration(i)*500*time.Millisecond), "")
		}
		b := incidentBody("vehicle_crash", 52, 4, "")
		b["trail"] = pts
		var c createdIncident
		a.must(t, http.MethodPost, "/v1/incidents", b, http.StatusCreated).decode(t, &c)
		var d detailJSON
		a.must(t, http.MethodGet, "/v1/incidents/"+c.ID, nil, http.StatusOK).decode(t, &d)
		if len(d.Trail) != 5000 {
			t.Fatalf("stored %d of 5000 points", len(d.Trail))
		}
		pts = append(pts, trailPoint(53, 4, now.Add(-time.Hour), ""))
		b["trail"] = pts
		wantErr(t, a.do(t, http.MethodPost, "/v1/incidents", b), http.StatusBadRequest, "invalid_trail")
	})
}

func TestInputValidation(t *testing.T) {
	t.Parallel()
	a := newActor(t, "input")
	fid, _ := a.createFamily(t, "e2e input")
	inc := a.newIncident(t, "fall", 10, 10, "")
	bad := func(t *testing.T, r *response) {
		t.Helper()
		if r.status != http.StatusBadRequest || r.errCode() == "" {
			t.Errorf("want 400 with an error code, got %s", r)
		}
	}

	check(t, "malformed_json_is_400", func(t *testing.T) {
		for _, body := range []string{"{", "{}{}", "[]", "null", `{"lat":"x","lng":1}`, "", `{"lat":1e400,"lng":1}`,
			`{"lat":NaN,"lng":1}`, "\x00", `{"lat":1,"lng":1,"recorded_at":"yesterday"}`} {
			bad(t, a.do(t, http.MethodPost, "/v1/location/heartbeat", body))
		}
	})

	check(t, "out_of_range_values_rejected", func(t *testing.T) {
		now := time.Now().UTC()
		base := func() map[string]any {
			return map[string]any{"lat": 1.0, "lng": 1.0, "recorded_at": now.Format(time.RFC3339)}
		}
		for field, v := range map[string]any{"lat": 91, "lng": -181, "battery_pct": 101, "speed_mps": -1, "bearing_deg": 400,
			"accuracy_m": -5, "activity": "flying", "recorded_at": now.Add(time.Hour).Format(time.RFC3339)} {
			b := base()
			b[field] = v
			bad(t, a.do(t, http.MethodPost, "/v1/location/heartbeat", b))
		}
		b := base()
		b["recorded_at"] = now.Add(-8 * 24 * time.Hour).Format(time.RFC3339)
		bad(t, a.do(t, http.MethodPost, "/v1/location/heartbeat", b))
	})

	check(t, "incident_details_limits", func(t *testing.T) {
		for _, details := range []any{[]int{1}, "text", 7, map[string]string{"p": strings.Repeat("x", 17<<10)}} {
			b := incidentBody("sos", 1, 1, "")
			b["details"] = details
			bad(t, a.do(t, http.MethodPost, "/v1/incidents", b))
		}
	})

	check(t, "deeply_nested_json_fails_cleanly", func(t *testing.T) {
		for _, depth := range []int{2000, 7000} {
			nested := `{"kind":"sos","detected_at":"` + time.Now().UTC().Format(time.RFC3339) + `","details":{"x":` +
				strings.Repeat("[", depth) + strings.Repeat("]", depth) + `}}`
			r := a.do(t, http.MethodPost, "/v1/incidents", nested)
			if r.status >= 500 {
				t.Errorf("depth %d: server error %s", depth, r)
			}
		}
	})

	check(t, "control_characters_in_names_rejected", func(t *testing.T) {
		for _, name := range []string{"a\x00b", "bell\a", "line\nbreak", "  ", strings.Repeat("n", 81)} {
			bad(t, sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: newIP(),
				body: registerBody(randomEmail(), "pw-"+randHex(8), name)}))
			bad(t, a.do(t, http.MethodPatch, "/v1/families/"+fid, map[string]string{"name": name}))
		}
	})

	check(t, "invalid_enum_values_rejected", func(t *testing.T) {
		bad(t, a.do(t, http.MethodPost, "/v1/incidents", incidentBody("meteor", 1, 1, "")))
		bad(t, a.do(t, http.MethodPatch, "/v1/me/settings", map[string]any{"sharing_state": "maybe"}))
		bad(t, a.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": "fixed"}))
		bad(t, a.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "ignored"}))
	})

	check(t, "settings_patch_validation", func(t *testing.T) {
		now := time.Now().UTC()
		for _, p := range []map[string]any{
			{"sharing_state": nil},
			{"always_alert_on_accident": nil},
			{"always_alert_on_accident": "yes"},
			{"sharing_state": "paused", "paused_until": now.Add(-time.Minute).Format(time.RFC3339)},
			{"sharing_state": "paused", "paused_until": now.Add(31 * 24 * time.Hour).Format(time.RFC3339)},
			{"sharing_state": "on", "paused_until": now.Add(time.Hour).Format(time.RFC3339)},
		} {
			bad(t, a.do(t, http.MethodPatch, "/v1/me/settings", p))
		}
	})

	check(t, "unknown_fields_ignored", func(t *testing.T) {
		annotate(t, "deviation 8: unknown JSON fields are ignored so a newer client never breaks an emergency upload")
		b := incidentBody("sos", 1, 1, "")
		b["user_id"] = randUUID() // must not let a client choose the owner
		b["status"] = "resolved"
		var c createdIncident
		a.must(t, http.MethodPost, "/v1/incidents", b, http.StatusCreated).decode(t, &c)
		var d summaryJSON
		a.must(t, http.MethodGet, "/v1/incidents/"+c.ID, nil, http.StatusOK).decode(t, &d)
		if d.UserID != a.id || d.Status != "active" {
			t.Fatalf("client-supplied owner/status was honoured: %+v", d)
		}
	})
}

func TestInjectionInputs(t *testing.T) {
	t.Parallel()
	const sqlName = `Robert'); DROP TABLE users;--`
	const sqlFamily = `x'; DELETE FROM family_members WHERE '1'='1`
	a := newActor(t, "inject")
	b := newActor(t, "inject-peer")
	a.must(t, http.MethodPost, "/v1/families", map[string]string{"name": "warmup"}, http.StatusCreated)

	check(t, "sql_metacharacters_in_email_are_literal", func(t *testing.T) {
		email := emailPrefix + randHex(6) + "'or'1'='1@example.test"
		ip := newIP()
		r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: ip, body: registerBody(email, "pw-secret-1", "quote")})
		want(t, r, http.StatusCreated)
		want(t, sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/login", ip: ip,
			body: map[string]string{"email": email, "password": "pw-secret-1"}}), http.StatusOK)
		for _, e := range []string{"' OR '1'='1", "' OR 1=1 --", `" OR ""="`, emailPrefix + "%@example.test", "*@example.test", email + "' --"} {
			r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/login", ip: newIP(),
				body: map[string]string{"email": e, "password": "' OR '1'='1"}})
			wantErr(t, r, http.StatusUnauthorized, "invalid_credentials")
		}
	})

	check(t, "sql_in_names_round_trips_verbatim", func(t *testing.T) {
		ip := newIP()
		email := randomEmail()
		r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: ip, body: registerBody(email, "pw-secret-2", sqlName)})
		want(t, r, http.StatusCreated)
		var ar authResp
		r.decode(t, &ar)
		n := &actor{role: "sql-name", id: ar.User.ID, access: ar.AccessToken, ip: ip}
		fid, code := n.createFamily(t, sqlFamily)
		b.mustJoin(t, code)
		if m := n.me(t); m.User.DisplayName != sqlName || len(m.Families) != 1 || m.Families[0].Name != sqlFamily {
			t.Fatalf("names not stored verbatim: %+v", m)
		}
		found := false
		for _, m := range b.members(t, fid) {
			found = found || (m.UserID == n.id && m.DisplayName == sqlName)
		}
		if !found {
			t.Fatal("peer does not see the verbatim display name")
		}
	})

	check(t, "sql_in_invite_codes", func(t *testing.T) {
		for _, c := range []string{"' OR 1=1 --", "%", "________", "%%%%%%%%", "ABCD' --", "') OR ('1'='1", "*"} {
			wantErr(t, a.join(t, c), http.StatusNotFound, "invite_invalid")
		}
	})

	check(t, "sql_in_path_parameters", func(t *testing.T) {
		for _, raw := range []string{"' OR '1'='1", "1; DROP TABLE incidents", "00000000-0000-0000-0000-000000000000' OR ''='", "%", "*"} {
			id := url.PathEscape(raw)
			for _, rq := range []struct{ method, path string }{
				{http.MethodGet, "/v1/families/" + id + "/members"},
				{http.MethodPost, "/v1/families/" + id + "/invites"},
				{http.MethodDelete, "/v1/families/" + id + "/members/" + id},
				{http.MethodPut, "/v1/grants/" + id},
				{http.MethodGet, "/v1/incidents/" + id},
				{http.MethodPost, "/v1/incidents/" + id + "/ack"},
			} {
				r := a.do(t, rq.method, rq.path, map[string]string{"level": "live", "state": "seen"})
				if r.status != http.StatusNotFound {
					t.Errorf("%s %s: want 404, got %s", rq.method, rq.path, r)
				}
			}
		}
	})

	check(t, "sql_in_enumerations_rejected", func(t *testing.T) {
		inj := "' OR '1'='1"
		hb := map[string]any{"lat": 1, "lng": 1, "recorded_at": time.Now().UTC(), "activity": "walking'; DROP TABLE locations_latest; --"}
		wantErr(t, a.do(t, http.MethodPost, "/v1/location/heartbeat", hb), http.StatusBadRequest, "invalid_activity")
		wantErr(t, a.do(t, http.MethodPatch, "/v1/me/settings", map[string]any{"sharing_state": "on" + inj}), http.StatusBadRequest, "invalid_settings")
		wantErr(t, a.do(t, http.MethodPut, "/v1/grants/"+b.id, map[string]string{"level": "live" + inj}), http.StatusBadRequest, "invalid_level")
		wantErr(t, a.do(t, http.MethodPost, "/v1/incidents", incidentBody("sos"+inj, 1, 1, "")), http.StatusBadRequest, "invalid_kind")
		r := a.doHeader(t, http.MethodPost, "/v1/incidents", incidentBody("sos", 1, 1, ""), map[string]string{"Idempotency-Key": "k'; DROP TABLE incidents; --"})
		wantErr(t, r, http.StatusBadRequest, "invalid_idempotency_key")
	})

	check(t, "sql_and_markup_in_free_text_round_trip", func(t *testing.T) {
		landmark := `'); DELETE FROM incident_trail; -- <b>x</b>`
		body := incidentBody("fall", 20, 20, landmark)
		details := map[string]any{"note'; DROP TABLE incidents; --": "1' OR '1'='1", "$where": "this.a == 1", "nested": map[string]any{"q": `"\`}}
		body["details"] = details
		var c createdIncident
		a.must(t, http.MethodPost, "/v1/incidents", body, http.StatusCreated).decode(t, &c)
		var d struct {
			Details map[string]any   `json:"details"`
			Trail   []map[string]any `json:"trail"`
		}
		a.must(t, http.MethodGet, "/v1/incidents/"+c.ID, nil, http.StatusOK).decode(t, &d)
		want, _ := json.Marshal(details)
		got, _ := json.Marshal(d.Details)
		if string(want) != string(got) || len(d.Trail) == 0 || d.Trail[0]["landmark"] != landmark {
			t.Fatalf("free text not stored verbatim:\n want %s\n got  %s\n trail %+v", want, got, d.Trail)
		}
	})

	check(t, "database_intact_after_injection_attempts", func(t *testing.T) {
		want(t, send(t, request{method: http.MethodGet, path: "/healthz"}), http.StatusOK)
		if m := a.me(t); m.User.ID != a.id || len(m.Families) != 1 {
			t.Fatalf("account state changed: %+v", m)
		}
	})
}

func TestHTTPHardening(t *testing.T) {
	t.Parallel()
	a := newActor(t, "http")

	check(t, "api_security_headers_on_success_and_errors", func(t *testing.T) {
		for _, r := range []*response{
			a.must(t, http.MethodGet, "/v1/me", nil, http.StatusOK),
			send(t, request{method: http.MethodGet, path: "/v1/me"}),
			a.do(t, http.MethodGet, "/v1/nope", nil),
		} {
			h := r.header
			if h.Get("X-Content-Type-Options") != "nosniff" || h.Get("X-Frame-Options") != "DENY" ||
				h.Get("Referrer-Policy") != "no-referrer" || h.Get("Cache-Control") != "no-store" ||
				!strings.HasPrefix(h.Get("Content-Security-Policy"), "default-src 'none'") {
				t.Errorf("missing security headers on %s: %v", r, h)
			}
			if !strings.HasPrefix(h.Get("Content-Type"), "application/json") {
				t.Errorf("non-JSON API response %s: %v", r, h.Get("Content-Type"))
			}
		}
	})

	check(t, "no_cross_origin_access_granted", func(t *testing.T) {
		evil := map[string]string{"Origin": "https://evil.example"}
		r := a.doHeader(t, http.MethodGet, "/v1/me", nil, evil)
		pre := send(t, request{method: http.MethodOptions, path: "/v1/me", header: map[string]string{
			"Origin": "https://evil.example", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"}})
		for _, x := range []*response{r, pre} {
			if x.header.Get("Access-Control-Allow-Origin") != "" || x.header.Get("Access-Control-Allow-Credentials") != "" {
				t.Errorf("CORS granted: %s %v", x, x.header)
			}
		}
		if pre.status < 400 {
			t.Errorf("preflight should not succeed: %s", pre)
		}
	})

	check(t, "static_assets_cannot_traverse", func(t *testing.T) {
		for _, p := range []string{"/static/../../etc/passwd", "/static/..%2f..%2fetc%2fpasswd", "/static/%2e%2e/%2e%2e/go.mod",
			"/static/../internal/sharepage/sharepage.go", "/static/maplibre-gl/../../templates/share.html", "/static/", "/static/.env"} {
			r := send(t, request{method: http.MethodGet, path: p})
			if r.status == http.StatusOK || strings.Contains(string(r.body), "root:") || strings.Contains(string(r.body), "module ") ||
				strings.Contains(string(r.body), "package ") || strings.Contains(string(r.body), "{{") {
				t.Errorf("traversal %s: %s", p, r)
			}
		}
		want(t, send(t, request{method: http.MethodGet, path: "/static/share.js"}), http.StatusOK)
	})

	check(t, "unknown_routes_and_methods_use_json_errors", func(t *testing.T) {
		wantErr(t, a.do(t, http.MethodGet, "/v1/does-not-exist", nil), http.StatusNotFound, "not_found")
		wantErr(t, a.do(t, http.MethodPut, "/v1/me", nil), http.StatusMethodNotAllowed, "method_not_allowed")
		wantErr(t, a.do(t, http.MethodDelete, "/v1/grants", nil), http.StatusMethodNotAllowed, "method_not_allowed")
		r := a.doHeader(t, "TRACE", "/v1/me", nil, map[string]string{"X-Echo-Probe": "e2e-probe-value"})
		if r.status < 400 || strings.Contains(string(r.body), "e2e-probe-value") || strings.Contains(string(r.body), a.access) {
			t.Errorf("TRACE must not echo the request: %s", r)
		}
	})

	check(t, "errors_do_not_leak_internals", func(t *testing.T) {
		leaky := []string{"SQLSTATE", "pgx", "pq:", "panic", "goroutine", ".go:", "runtime error", "duplicate key",
			"row-level security", "foreign key", "syntax error", `relation \"`, "/Users/", "/src/"}
		fid, _ := a.createFamily(t, "e2e errors")
		for _, r := range []*response{
			a.do(t, http.MethodPost, "/v1/location/heartbeat", `{"lat":`),
			a.do(t, http.MethodPost, "/v1/incidents", map[string]any{"kind": "sos", "detected_at": "nope"}),
			a.do(t, http.MethodPut, "/v1/grants/"+randUUID(), map[string]string{"level": "live"}),
			a.do(t, http.MethodPost, "/v1/families/join", map[string]string{"code": "ZZZZZZZZ"}),
			a.do(t, http.MethodPatch, "/v1/families/"+fid, map[string]string{"name": ""}),
			a.do(t, http.MethodDelete, "/v1/families/"+fid+"/members/"+randUUID(), nil),
			send(t, request{method: http.MethodGet, path: "/s/" + randToken() + "/data"}),
		} {
			notContains(t, r, leaky...)
		}
	})

	check(t, "healthz_reveals_nothing", func(t *testing.T) {
		r := send(t, request{method: http.MethodGet, path: "/healthz"})
		want(t, r, http.StatusOK)
		if strings.TrimSpace(string(r.body)) != `{"status":"ok"}` || r.header.Get("Server") != "" && strings.ContainsAny(r.header.Get("Server"), "0123456789") {
			t.Fatalf("healthz leaks details: %s %v", r, r.header.Get("Server"))
		}
	})
}

// Responses must not reveal whether a resource of another user exists.
func TestExistenceLeaks(t *testing.T) {
	t.Parallel()
	a, b := newActor(t, "leak-a"), newActor(t, "leak-b")
	fa, _ := a.createFamily(t, "e2e leak a")
	fb, _ := b.createFamily(t, "e2e leak b")
	incB := b.newIncident(t, "sos", 5, 5, "")
	missing := randUUID()

	pair := func(t *testing.T, method, real, fake string, body any) {
		t.Helper()
		r1, r2 := a.do(t, method, real, body), a.do(t, method, fake, body)
		wantErr(t, r1, http.StatusNotFound, "not_found")
		sameResponse(t, r1, r2)
	}

	check(t, "foreign_family_looks_nonexistent", func(t *testing.T) {
		pair(t, http.MethodGet, "/v1/families/"+fb+"/members", "/v1/families/"+missing+"/members", nil)
		pair(t, http.MethodPost, "/v1/families/"+fb+"/invites", "/v1/families/"+missing+"/invites", nil)
		pair(t, http.MethodPatch, "/v1/families/"+fb, "/v1/families/"+missing, map[string]string{"name": "pwned"})
		pair(t, http.MethodDelete, "/v1/families/"+fb+"/members/"+b.id, "/v1/families/"+missing+"/members/"+b.id, nil)
	})

	check(t, "foreign_incident_looks_nonexistent", func(t *testing.T) {
		for _, op := range incidentOps() {
			pair(t, op.method, "/v1/incidents/"+incB.ID+op.suffix, "/v1/incidents/"+missing+op.suffix, op.body)
		}
	})

	check(t, "other_users_look_nonexistent", func(t *testing.T) {
		pair(t, http.MethodPut, "/v1/grants/"+b.id, "/v1/grants/"+missing, map[string]string{"level": "live"})
		pair(t, http.MethodDelete, "/v1/families/"+fa+"/members/"+b.id, "/v1/families/"+fa+"/members/"+missing, nil)
	})

	check(t, "lists_never_include_strangers", func(t *testing.T) {
		for _, path := range []string{"/v1/family/locations", "/v1/grants", "/v1/incidents/active", "/v1/me"} {
			notContains(t, a.must(t, http.MethodGet, path, nil, http.StatusOK), b.id, b.email, b.displayName, incB.ID, fb)
		}
		notContains(t, a.must(t, http.MethodGet, "/v1/families/"+fa+"/members", nil, http.StatusOK), b.id, b.displayName)
	})
}
