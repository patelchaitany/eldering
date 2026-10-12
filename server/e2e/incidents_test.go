package e2e

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

type incidentOp struct {
	name, method, suffix string
	body                 any
}

func incidentOps() []incidentOp {
	pt := []any{trailPoint(10, 10, time.Now(), "")}
	return []incidentOp{
		{"detail", http.MethodGet, "", nil},
		{"append_trail", http.MethodPost, "/trail", map[string]any{"points": pt}},
		{"resolve_handled", http.MethodPost, "/resolve", map[string]string{"resolution": "handled"}},
		{"resolve_user_ok", http.MethodPost, "/resolve", map[string]string{"resolution": "user_ok"}},
		{"resolve_false_alarm", http.MethodPost, "/resolve", map[string]string{"resolution": "false_alarm"}},
		{"ack_seen", http.MethodPost, "/ack", map[string]string{"state": "seen"}},
		{"ack_on_my_way", http.MethodPost, "/ack", map[string]string{"state": "on_my_way"}},
	}
}

type ackJSON struct {
	UserID string `json:"user_id"`
	State  string `json:"state"`
}

type detailJSON struct {
	summaryJSON
	ResolvedBy *string          `json:"resolved_by"`
	Trail      []map[string]any `json:"trail"`
	Acks       []ackJSON        `json:"acks"`
}

// IDOR: every incident action by someone who may not see the incident is a
// 404 identical to a nonexistent id; 403 only when the incident is visible.
func TestIncidentIDOR(t *testing.T) {
	t.Parallel()
	admin, s, v, w, x := newActor(t, "idor-admin"), newActor(t, "idor-owner"), newActor(t, "idor-viewer"),
		newActor(t, "idor-blocked"), newActor(t, "idor-outsider")
	newFamily(t, admin, s, v, w)
	x.createFamily(t, "e2e outsider family")
	s.setGrant(t, w, "off")
	inc := s.newIncident(t, "fall", 35.6762, 139.6503, "Shibuya Crossing")
	missing := randUUID()

	denyAll := func(t *testing.T, who *actor) {
		t.Helper()
		for _, op := range incidentOps() {
			got := who.do(t, op.method, "/v1/incidents/"+inc.ID+op.suffix, op.body)
			ctrl := who.do(t, op.method, "/v1/incidents/"+missing+op.suffix, op.body)
			if got.status != http.StatusNotFound || got.errCode() != "not_found" {
				t.Errorf("%s %s: want 404 not_found, got %s", who.role, op.name, got)
				continue
			}
			if got.status != ctrl.status || string(got.body) != string(ctrl.body) {
				t.Errorf("%s %s leaks existence:\n  %s\n  %s", who.role, op.name, got, ctrl)
			}
		}
	}

	check(t, "outsider_gets_404_for_every_action", func(t *testing.T) { denyAll(t, x) })
	check(t, "co_member_with_grant_off_gets_404_for_every_action", func(t *testing.T) { denyAll(t, w) })

	check(t, "malformed_ids_are_404", func(t *testing.T) {
		for _, id := range []string{"not-a-uuid", url.PathEscape("1' OR '1'='1"), "00000000-0000-0000-0000-000000000000",
			strings.Repeat("9", 40), url.PathEscape(inc.ID + "' --"), strings.ToUpper(missing)} {
			for _, op := range incidentOps() {
				if r := v.do(t, op.method, "/v1/incidents/"+id+op.suffix, op.body); r.status != http.StatusNotFound {
					t.Errorf("id %q %s: want 404, got %s", id, op.name, r)
				}
			}
		}
	})

	check(t, "viewer_cannot_append_trail", func(t *testing.T) {
		r := v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/trail", map[string]any{"points": []any{trailPoint(1, 1, time.Now(), "fake")}})
		wantErr(t, r, http.StatusForbidden, "forbidden")
	})

	check(t, "viewer_cannot_use_owner_resolutions", func(t *testing.T) {
		for _, res := range []string{"user_ok", "false_alarm"} {
			wantErr(t, v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": res}), http.StatusForbidden, "forbidden")
		}
		var d detailJSON
		s.must(t, http.MethodGet, "/v1/incidents/"+inc.ID, nil, http.StatusOK).decode(t, &d)
		if d.Status != "active" {
			t.Fatalf("incident changed: %+v", d)
		}
	})

	check(t, "owner_cannot_ack_own_incident", func(t *testing.T) {
		wantErr(t, s.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "seen"}), http.StatusBadRequest, "cannot_ack_own")
	})

	check(t, "viewer_ack_never_downgrades", func(t *testing.T) {
		if hb := v.heartbeat(t, 35.6, 139.6); !containsIncident(hb.PendingAlerts, inc.ID) {
			t.Fatalf("unacknowledged incident missing from pending alerts")
		}
		for _, step := range [][2]string{{"seen", "seen"}, {"on_my_way", "on_my_way"}, {"seen", "on_my_way"}} {
			var a ackJSON
			v.must(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": step[0]}, http.StatusOK).decode(t, &a)
			if a.State != step[1] || a.UserID != v.id {
				t.Fatalf("ack %s -> %+v, want %s", step[0], a, step[1])
			}
		}
		if hb := v.heartbeat(t, 35.6, 139.6); containsIncident(hb.PendingAlerts, inc.ID) {
			t.Fatalf("acknowledged incident still pending")
		}
	})

	check(t, "acks_visible_to_owner_but_not_to_other_viewers", func(t *testing.T) {
		admin.must(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "seen"}, http.StatusOK)
		ackers := func(a *actor) map[string]bool {
			var d detailJSON
			a.must(t, http.MethodGet, "/v1/incidents/"+inc.ID, nil, http.StatusOK).decode(t, &d)
			m := map[string]bool{}
			for _, k := range d.Acks {
				m[k.UserID] = true
			}
			return m
		}
		if got := ackers(s); len(got) != 2 || !got[v.id] || !got[admin.id] {
			t.Fatalf("owner should see both acks, got %v", got)
		}
		if got := ackers(v); len(got) != 1 || !got[v.id] {
			t.Fatalf("viewer should see only their own ack, got %v", got)
		}
		if got := ackers(admin); len(got) != 1 || !got[admin.id] {
			t.Fatalf("admin should see only their own ack, got %v", got)
		}
	})

	check(t, "viewer_with_access_resolves_as_handled", func(t *testing.T) {
		var d summaryJSON
		v.must(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": "handled"}, http.StatusOK).decode(t, &d)
		if d.Status != "resolved" || d.Resolution == nil || *d.Resolution != "handled" {
			t.Fatalf("unexpected resolution: %+v", d)
		}
		var full detailJSON
		s.must(t, http.MethodGet, "/v1/incidents/"+inc.ID, nil, http.StatusOK).decode(t, &full)
		if full.ResolvedBy == nil || *full.ResolvedBy != v.id {
			t.Fatalf("resolved_by should be the viewer: %+v", full.ResolvedBy)
		}
	})

	check(t, "closed_incident_rejects_changes", func(t *testing.T) {
		wantErr(t, s.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/trail", map[string]any{"points": []any{trailPoint(1, 1, time.Now(), "")}}), http.StatusConflict, "incident_closed")
		wantErr(t, v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/ack", map[string]string{"state": "seen"}), http.StatusConflict, "incident_closed")
		wantErr(t, v.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": "handled"}), http.StatusConflict, "incident_closed")
		wantErr(t, s.do(t, http.MethodPost, "/v1/incidents/"+inc.ID+"/resolve", map[string]string{"resolution": "user_ok"}), http.StatusConflict, "incident_closed")
		denyAll(t, x)
	})

	check(t, "idempotency_key_is_per_user_and_rotates_the_link", func(t *testing.T) {
		key := map[string]string{"Idempotency-Key": "e2e-" + randHex(8)}
		body := incidentBody("sos", 1.3521, 103.8198, "")
		var first, retry, other createdIncident
		s.doHeader(t, http.MethodPost, "/v1/incidents", body, key).decode(t, &first)
		r := s.doHeader(t, http.MethodPost, "/v1/incidents", body, key)
		want(t, r, http.StatusOK)
		r.decode(t, &retry)
		if retry.ID != first.ID || retry.ShareURL == first.ShareURL {
			t.Fatalf("retry should return the same incident with a new link: %+v vs %+v", first, retry)
		}
		wantErr(t, shareGet(t, s.ip, first.token(t), "/data"), http.StatusNotFound, "not_found")
		want(t, shareGet(t, s.ip, retry.token(t), "/data"), http.StatusOK)
		// Another user replaying the same key gets their own new incident, never s's.
		r = x.doHeader(t, http.MethodPost, "/v1/incidents", body, key)
		want(t, r, http.StatusCreated)
		r.decode(t, &other)
		if other.ID == first.ID {
			t.Fatalf("idempotency key crossed users")
		}
		wantErr(t, x.do(t, http.MethodGet, "/v1/incidents/"+first.ID, nil), http.StatusNotFound, "not_found")
	})
}

var shareDataKeys = map[string]bool{"kind": true, "status": true, "detected_at": true, "share_expires_at": true,
	"resolved_at": true, "resolution": true, "incident_location": true, "latest": true, "trail": true, "server_time": true}

func shareGet(t testing.TB, ip, token, suffix string) *response {
	t.Helper()
	return sendRetry(t, request{method: http.MethodGet, path: "/s/" + token + suffix, ip: ip})
}

// SPEC: a share token (32 random bytes, base64url, SHA-256 stored) shows that
// incident's latest location + trail until expiry (72 h), nothing else.
func TestShareToken(t *testing.T) {
	t.Parallel()
	s, v := newActor(t, "share-owner"), newActor(t, "share-viewer")
	newFamily(t, s, v)
	const xss = `<script>alert("e2e")</script><img src=x onerror=alert(1)>`
	i1 := s.newIncident(t, "fall", 48.8584, 2.2945, xss)
	i2 := s.newIncident(t, "sos", -33.8568, 151.2153, "Opera House")
	t1, t2 := i1.token(t), i2.token(t)
	ip := newIP()

	check(t, "token_is_32_random_bytes_base64url", func(t *testing.T) {
		for _, tok := range []string{t1, t2} {
			raw, err := base64.RawURLEncoding.DecodeString(tok)
			if err != nil || len(raw) != 32 {
				t.Fatalf("token %q is not 32 bytes of base64url: %v", tok, err)
			}
		}
		if t1 == t2 {
			t.Fatal("tokens repeat")
		}
		if d := time.Until(i1.ShareExpiresAt); d < 71*time.Hour || d > 73*time.Hour {
			t.Fatalf("link should expire in 72 h, got %v", d)
		}
	})

	check(t, "token_reveals_only_its_own_incident", func(t *testing.T) {
		var d1, d2 struct {
			Kind  string `json:"kind"`
			Trail []struct {
				Lat float64 `json:"lat"`
				Lng float64 `json:"lng"`
			} `json:"trail"`
		}
		shareGet(t, ip, t1, "/data").decode(t, &d1)
		shareGet(t, ip, t2, "/data").decode(t, &d2)
		if d1.Kind != "fall" || d2.Kind != "sos" || len(d1.Trail) != 3 || len(d2.Trail) != 3 {
			t.Fatalf("wrong incident behind a token: %+v / %+v", d1, d2)
		}
		for _, p := range d1.Trail {
			if p.Lat < 48 || p.Lat > 49 {
				t.Fatalf("token 1 shows a point of another incident: %+v", p)
			}
		}
	})

	check(t, "share_data_carries_no_identity", func(t *testing.T) {
		r := shareGet(t, ip, t1, "/data")
		want(t, r, http.StatusOK)
		var top map[string]json.RawMessage
		r.decode(t, &top)
		for k := range top {
			if !shareDataKeys[k] {
				t.Errorf("unexpected field %q in public share data", k)
			}
		}
		notContains(t, r, s.id, s.email, s.displayName, v.id, i1.ID, i2.ID, "user_id", "display_name", "email")
	})

	check(t, "share_page_headers_noindex_no_store", func(t *testing.T) {
		r := shareGet(t, ip, t1, "")
		want(t, r, http.StatusOK)
		h := r.header
		if !strings.HasPrefix(h.Get("Content-Type"), "text/html") || h.Get("Cache-Control") != "no-store" ||
			!strings.Contains(h.Get("X-Robots-Tag"), "noindex") || h.Get("Referrer-Policy") != "no-referrer" ||
			!strings.Contains(h.Get("Content-Security-Policy"), "default-src 'none'") ||
			!strings.Contains(h.Get("Content-Security-Policy"), "frame-ancestors 'none'") {
			t.Fatalf("missing privacy headers: %v", h)
		}
		if !strings.Contains(string(r.body), `name="robots" content="noindex`) {
			t.Fatal("page lacks the robots noindex meta tag")
		}
		notContains(t, r, s.displayName, s.email, s.id)
		d := shareGet(t, ip, t1, "/data")
		if d.header.Get("Cache-Control") != "no-store" || !strings.Contains(d.header.Get("X-Robots-Tag"), "noindex") {
			t.Fatalf("data endpoint lacks no-store/noindex: %v", d.header)
		}
	})

	check(t, "landmarks_are_escaped", func(t *testing.T) {
		page := shareGet(t, ip, t1, "")
		notContains(t, page, `<script>alert("e2e")`, `<img src=x onerror`)
		if !strings.Contains(string(page.body), "&lt;script&gt;") {
			t.Fatal("landmark text missing or not HTML-escaped")
		}
		notContains(t, shareGet(t, ip, t1, "/data"), "<script>", "<img")
		var d detailJSON
		s.must(t, http.MethodGet, "/v1/incidents/"+i1.ID, nil, http.StatusOK).decode(t, &d)
		if len(d.Trail) == 0 || d.Trail[0]["landmark"] != xss {
			t.Fatalf("landmark must round-trip verbatim through the API: %+v", d.Trail)
		}
	})

	check(t, "invalid_tokens_give_one_uniform_404", func(t *testing.T) {
		ip := newIP() // ~20 requests: keep them out of the shared bucket
		base := shareGet(t, ip, randToken(), "/data")
		wantErr(t, base, http.StatusNotFound, "not_found")
		basePage := shareGet(t, ip, randToken(), "")
		want(t, basePage, http.StatusNotFound)
		for _, tok := range []string{"x", t1[:42], t1 + "A", strings.ToUpper(t1), url.PathEscape("' OR '1'='1"),
			url.PathEscape(strings.Repeat("'", 43)), strings.Repeat("A", 43), url.PathEscape("é") + t1[2:]} {
			sameResponse(t, base, shareGet(t, ip, tok, "/data"))
			sameResponse(t, basePage, shareGet(t, ip, tok, ""))
		}
		// An encoded NUL is refused by nginx (400) before the API sees it, and by
		// the API with the usual 404 when reached directly. Either way: no data.
		for _, suffix := range []string{"/data", ""} {
			r := shareGet(t, ip, "%00"+t1[3:], suffix)
			if (r.status != http.StatusNotFound && r.status != http.StatusBadRequest) || strings.Contains(string(r.body), "trail") {
				t.Fatalf("NUL in token: %s", r)
			}
		}
	})

	check(t, "token_is_not_an_api_credential", func(t *testing.T) {
		for _, path := range []string{"/v1/me", "/v1/incidents/" + i1.ID, "/v1/incidents/active"} {
			wantErr(t, send(t, request{method: http.MethodGet, path: path, token: t1, ip: ip}), http.StatusUnauthorized, "unauthorized")
		}
		wantErr(t, refresh(t, ip, t1), http.StatusUnauthorized, "invalid_refresh_token")
	})

	check(t, "share_endpoints_are_read_only", func(t *testing.T) {
		for _, m := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
			for _, suffix := range []string{"", "/data"} {
				if r := send(t, request{method: m, path: "/s/" + t1 + suffix, ip: ip, body: "{}"}); r.status != http.StatusMethodNotAllowed {
					t.Errorf("%s /s/{token}%s: want 405, got %s", m, suffix, r)
				}
			}
		}
	})

	check(t, "resolved_incident_link_hides_location", func(t *testing.T) {
		annotate(t, "deviation 6: the link keeps answering until expiry but withholds location and trail")
		s.must(t, http.MethodPost, "/v1/incidents/"+i2.ID+"/resolve", map[string]string{"resolution": "user_ok"}, http.StatusOK)
		var d struct {
			Status           string          `json:"status"`
			IncidentLocation json.RawMessage `json:"incident_location"`
			Latest           json.RawMessage `json:"latest"`
			Trail            []any           `json:"trail"`
		}
		shareGet(t, ip, t2, "/data").decode(t, &d)
		if d.Status != "resolved" || string(d.IncidentLocation) != "null" || string(d.Latest) != "null" || len(d.Trail) != 0 {
			t.Fatalf("resolved incident still exposes location: %+v", d)
		}
		notContains(t, shareGet(t, ip, t2, ""), "-33.85", "151.21", "Opera House")
	})

	check(t, "expired_token_is_404", func(t *testing.T) {
		if cfg.psql == "" {
			skip(t, "E2E_PSQL not set (expiry needs the clock moved in the database)")
		}
		if !uuidRE.MatchString(i1.ID) {
			t.Fatalf("bad id %q", i1.ID)
		}
		v.expectIncident(t, i1.ID, true)
		mustPSQL(t, fmt.Sprintf("UPDATE incidents SET share_expires_at = now() - interval '1 second' WHERE id = '%s';\n", i1.ID))
		base := shareGet(t, ip, randToken(), "/data")
		sameResponse(t, base, shareGet(t, ip, t1, "/data"))
		sameResponse(t, shareGet(t, ip, randToken(), ""), shareGet(t, ip, t1, ""))
		// An expired alert is no longer active or pending for viewers.
		var active []summaryJSON
		v.must(t, http.MethodGet, "/v1/incidents/active", nil, http.StatusOK).decode(t, &active)
		if containsIncident(active, i1.ID) || containsIncident(v.heartbeat(t, 1, 1).PendingAlerts, i1.ID) {
			t.Fatalf("expired incident still active/pending")
		}
	})
}
