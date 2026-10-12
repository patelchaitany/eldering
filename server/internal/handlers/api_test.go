package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/config"
	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/testdb"
)

func TestMain(m *testing.M) { os.Exit(testdb.Main(m, "api")) }

// ---------- harness ----------

type testAPI struct {
	t    *testing.T
	d    *testdb.DB
	pool *pgxpool.Pool
	srv  *httptest.Server
}

func testConfig() config.Config {
	return config.Config{
		ListenAddr:      ":0",
		PublicBaseURL:   "https://safety.example.test",
		JWTSecret:       bytes.Repeat([]byte{7}, 32),
		ClientIPHeader:  "X-Real-IP",
		AccessTTL:       15 * time.Minute,
		RefreshTTL:      30 * 24 * time.Hour,
		ShareTTL:        72 * time.Hour,
		InviteTTL:       48 * time.Hour,
		InviteUses:      10,
		Retention:       time.Hour,
		BodyLimit:       1 << 20,
		MaxHashJobs:     4,
		AuthPerMinute:   6000,
		AuthBurst:       1000,
		TokenPerMinute:  6000,
		TokenBurst:      1000,
		LoginPerAccount: 6000,
		LoginAcctBurst:  1000,
		SharePerMinute:  6000,
		ShareBurst:      1000,
	}
}

func newTestAPI(t *testing.T, mutate func(*config.Config)) *testAPI {
	t.Helper()
	d := testdb.Get(t)
	cfg := testConfig()
	if mutate != nil {
		mutate(&cfg)
	}
	pool, err := db.Open(context.Background(), d.AppURL+"&pool_max_conns=4")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	if testing.Verbose() {
		log = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	}
	srv, err := New(Deps{Config: cfg, Pool: pool, Logger: log,
		Hasher: auth.NewHasher(auth.Argon2Params{MemoryKiB: 1024, Time: 1, Threads: 1, SaltLen: 16, KeyLen: 32}, 4)})
	if err != nil {
		t.Fatal(err)
	}
	hs := httptest.NewServer(srv.Handler())
	t.Cleanup(hs.Close)
	return &testAPI{t: t, d: d, pool: pool, srv: hs}
}

type resp struct {
	status int
	header http.Header
	body   []byte
}

func (r resp) decode(t *testing.T, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decode %q: %v", r.body, err)
	}
}

func (r resp) errCode(t *testing.T) string {
	t.Helper()
	var e struct {
		Error struct{ Code, Message string } `json:"error"`
	}
	r.decode(t, &e)
	return e.Error.Code
}

// call performs a request; body may be nil, a string (sent raw) or a value (JSON).
func (a *testAPI) call(method, path, token string, body any, hdr ...string) resp {
	a.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	case []byte:
		rd = bytes.NewReader(b)
	default:
		buf, err := json.Marshal(b)
		if err != nil {
			a.t.Fatal(err)
		}
		rd = bytes.NewReader(buf)
	}
	req, err := http.NewRequest(method, a.srv.URL+path, rd)
	if err != nil {
		a.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	res, err := a.srv.Client().Do(req)
	if err != nil {
		a.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return resp{status: res.StatusCode, header: res.Header, body: b}
}

func (a *testAPI) expect(r resp, status int) resp {
	a.t.Helper()
	if r.status != status {
		a.t.Fatalf("want %d, got %d: %s", status, r.status, r.body)
	}
	return r
}

type user struct {
	api     *testAPI
	id      uuid.UUID
	email   string
	name    string
	access  string
	refresh string
}

func (a *testAPI) register(name string) *user {
	a.t.Helper()
	email := strings.ReplaceAll(strings.ToLower(name), " ", ".") + "-" + uuid.NewString()[:8] + "@example.test"
	r := a.expect(a.call("POST", "/v1/auth/register", "", map[string]string{
		"email": email, "password": "correct horse battery", "display_name": name,
	}), http.StatusCreated)
	var ar authResponse
	r.decode(a.t, &ar)
	return &user{api: a, id: ar.User.ID, email: email, name: name, access: ar.AccessToken, refresh: ar.RefreshToken}
}

func (u *user) call(method, path string, body any, hdr ...string) resp {
	u.api.t.Helper()
	return u.api.call(method, path, u.access, body, hdr...)
}

func (u *user) must(method, path string, body any, status int, out any, hdr ...string) {
	u.api.t.Helper()
	r := u.api.expect(u.call(method, path, body, hdr...), status)
	if out != nil {
		r.decode(u.api.t, out)
	}
}

// family creates a family owned by admin and joins members via the invite code.
func (a *testAPI) family(admin *user, members ...*user) (uuid.UUID, string) {
	a.t.Helper()
	var f createFamilyResponse
	admin.must("POST", "/v1/families", map[string]string{"name": "The " + admin.name + "s"}, http.StatusCreated, &f)
	for _, m := range members {
		m.must("POST", "/v1/families/join", map[string]string{"code": f.Invite.Code}, http.StatusOK, nil)
	}
	return f.ID, f.Invite.Code
}

func (u *user) heartbeat(lat, lng float64, at time.Time) heartbeatResponse {
	u.api.t.Helper()
	var hb heartbeatResponse
	u.must("POST", "/v1/location/heartbeat", map[string]any{
		"lat": lat, "lng": lng, "accuracy_m": 12.5, "speed_mps": 1.2, "bearing_deg": 90,
		"activity": "WALKING", "battery_pct": 81, "recorded_at": at.UTC().Format(time.RFC3339Nano),
	}, http.StatusOK, &hb)
	return hb
}

func (u *user) locations() map[uuid.UUID]memberLocationJSON {
	u.api.t.Helper()
	var list []memberLocationJSON
	u.must("GET", "/v1/family/locations", nil, http.StatusOK, &list)
	out := map[uuid.UUID]memberLocationJSON{}
	for _, l := range list {
		out[l.UserID] = l
	}
	return out
}

func (u *user) grant(viewer *user, level string) {
	u.api.t.Helper()
	u.must("PUT", "/v1/grants/"+viewer.id.String(), map[string]string{"level": level}, http.StatusOK, nil)
}

func (u *user) incident(kind string, hdr ...string) (createIncidentResponse, int) {
	u.api.t.Helper()
	return u.incidentAt(kind, time.Now().UTC(), hdr...)
}

// incidentAt posts a fixed payload for a given detection time (so retries are byte-identical).
func (u *user) incidentAt(kind string, now time.Time, hdr ...string) (createIncidentResponse, int) {
	u.api.t.Helper()
	r := u.call("POST", "/v1/incidents", map[string]any{
		"kind": kind, "detected_at": now.Format(time.RFC3339Nano), "lat": 19.0760, "lng": 72.8777,
		"details": map[string]any{"peak_g": 6.2, "mode": "WALKING"},
		"trail": []map[string]any{
			{"lat": 19.0700, "lng": 72.8700, "recorded_at": now.Add(-10 * time.Minute).Format(time.RFC3339Nano), "accuracy_m": 9, "landmark": "Dadar Station"},
			{"lat": 19.0730, "lng": 72.8740, "recorded_at": now.Add(-5 * time.Minute).Format(time.RFC3339Nano), "accuracy_m": 11},
			{"lat": 19.0760, "lng": 72.8777, "recorded_at": now.Add(-10 * time.Second).Format(time.RFC3339Nano), "accuracy_m": 6, "landmark": "<script>alert(1)</script> Café"},
		},
	}, hdr...)
	if r.status != http.StatusCreated && r.status != http.StatusOK {
		u.api.t.Fatalf("create incident: %d %s", r.status, r.body)
	}
	var out createIncidentResponse
	r.decode(u.api.t, &out)
	return out, r.status
}

func sharePath(t *testing.T, shareURL string) string {
	t.Helper()
	const base = "https://safety.example.test"
	if !strings.HasPrefix(shareURL, base+"/s/") {
		t.Fatalf("unexpected share url %q", shareURL)
	}
	return strings.TrimPrefix(shareURL, base)
}

// ---------- tests ----------

func TestHealthzAndErrors(t *testing.T) {
	a := newTestAPI(t, nil)
	r := a.expect(a.call("GET", "/healthz", "", nil), http.StatusOK)
	if !strings.Contains(string(r.body), `"ok"`) || r.header.Get("Cache-Control") != "no-store" ||
		r.header.Get("X-Content-Type-Options") != "nosniff" || r.header.Get("Content-Security-Policy") == "" {
		t.Fatalf("healthz: %s %v", r.body, r.header)
	}
	if code := a.expect(a.call("GET", "/v1/nope", "", nil), http.StatusNotFound).errCode(t); code != "not_found" {
		t.Fatalf("code %q", code)
	}
	a.expect(a.call("DELETE", "/v1/me", "", nil), http.StatusMethodNotAllowed)
	if code := a.expect(a.call("GET", "/v1/me", "", nil), http.StatusUnauthorized).errCode(t); code != "unauthorized" {
		t.Fatalf("code %q", code)
	}
	a.expect(a.call("GET", "/v1/me", "not-a-jwt", nil), http.StatusUnauthorized)
}

func TestAuthLifecycle(t *testing.T) {
	a := newTestAPI(t, nil)
	alice := a.register("Alice")

	// Validation.
	for _, body := range []map[string]string{
		{"email": "nope", "password": "longenough", "display_name": "X"},
		{"email": "x@example.test", "password": "short", "display_name": "X"},
		{"email": "x@example.test", "password": "longenough", "display_name": "  "},
	} {
		a.expect(a.call("POST", "/v1/auth/register", "", body), http.StatusBadRequest)
	}
	a.expect(a.call("POST", "/v1/auth/register", "", `{"email":`), http.StatusBadRequest)
	a.expect(a.call("POST", "/v1/auth/register", "", `{"email":"a@b.co"} {"x":1}`), http.StatusBadRequest)
	// Duplicate email (case-insensitive).
	r := a.expect(a.call("POST", "/v1/auth/register", "", map[string]string{
		"email": strings.ToUpper(alice.email), "password": "another password", "display_name": "Imposter"}), http.StatusConflict)
	if r.errCode(t) != "email_taken" {
		t.Fatalf("code %s", r.body)
	}

	// Login.
	a.expect(a.call("POST", "/v1/auth/login", "", map[string]string{"email": alice.email, "password": "wrong password"}), http.StatusUnauthorized)
	a.expect(a.call("POST", "/v1/auth/login", "", map[string]string{"email": "ghost@example.test", "password": "whatever1"}), http.StatusUnauthorized)
	var login authResponse
	a.expect(a.call("POST", "/v1/auth/login", "", map[string]string{"email": " " + strings.ToUpper(alice.email), "password": "correct horse battery"}), http.StatusOK).decode(t, &login)
	if login.User.ID != alice.id || login.TokenType != "Bearer" || login.ExpiresIn != 900 || len(login.RefreshToken) != 43 {
		t.Fatalf("login response: %+v", login)
	}

	// /me works with the access token.
	var me meResponse
	a.expect(a.call("GET", "/v1/me", login.AccessToken, nil), http.StatusOK).decode(t, &me)
	if me.User.ID != alice.id || me.Settings.SharingState != "on" || !me.Settings.AlwaysAlertOnAccident || len(me.Families) != 0 {
		t.Fatalf("me: %+v", me)
	}

	// Refresh rotates; the old token is then rejected, and its reuse revokes
	// the whole session family (including the newly issued token).
	var r1 authResponse
	a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": login.RefreshToken}), http.StatusOK).decode(t, &r1)
	if r1.RefreshToken == login.RefreshToken || r1.User.ID != alice.id {
		t.Fatal("refresh did not rotate")
	}
	if code := a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": login.RefreshToken}), http.StatusUnauthorized).errCode(t); code != "invalid_refresh_token" {
		t.Fatalf("code %s", code)
	}
	a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": r1.RefreshToken}), http.StatusUnauthorized)
	a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": "garbage"}), http.StatusUnauthorized)

	// Logout revokes; idempotent.
	var r2 authResponse
	a.expect(a.call("POST", "/v1/auth/login", "", map[string]string{"email": alice.email, "password": "correct horse battery"}), http.StatusOK).decode(t, &r2)
	a.expect(a.call("POST", "/v1/auth/logout", "", map[string]string{"refresh_token": r2.RefreshToken}), http.StatusNoContent)
	a.expect(a.call("POST", "/v1/auth/logout", "", map[string]string{"refresh_token": r2.RefreshToken}), http.StatusNoContent)
	a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": r2.RefreshToken}), http.StatusUnauthorized)

	// Expired refresh token.
	var r3 authResponse
	a.expect(a.call("POST", "/v1/auth/login", "", map[string]string{"email": alice.email, "password": "correct horse battery"}), http.StatusOK).decode(t, &r3)
	if _, err := a.d.Admin(t).Exec(context.Background(), `UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = $1`,
		auth.HashToken(r3.RefreshToken)); err != nil {
		t.Fatal(err)
	}
	a.expect(a.call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": r3.RefreshToken}), http.StatusUnauthorized)

	// Only hashes are stored.
	var stored int
	if err := a.d.Admin(t).QueryRow(context.Background(), `SELECT count(*) FROM refresh_tokens WHERE token_hash = $1::bytea OR encode(token_hash,'escape') = $2`,
		auth.HashToken(r3.RefreshToken), r3.RefreshToken).Scan(&stored); err != nil || stored != 1 {
		t.Fatalf("expected exactly the hash stored: %d %v", stored, err)
	}
	var hash string
	if err := a.d.Admin(t).QueryRow(context.Background(), `SELECT password_hash FROM users WHERE id = $1`, alice.id).Scan(&hash); err != nil || !strings.HasPrefix(hash, "$argon2id$") {
		t.Fatalf("password hash %q %v", hash, err)
	}
}

func TestAuthRateLimits(t *testing.T) {
	a := newTestAPI(t, func(c *config.Config) { c.AuthPerMinute, c.AuthBurst = 1, 3 })
	body := map[string]string{"email": "nobody@example.test", "password": "whatever1"}
	for i := 0; i < 3; i++ {
		a.expect(a.call("POST", "/v1/auth/login", "", body, "X-Real-IP", "203.0.113.7"), http.StatusUnauthorized)
	}
	r := a.expect(a.call("POST", "/v1/auth/login", "", body, "X-Real-IP", "203.0.113.7"), http.StatusTooManyRequests)
	if r.errCode(t) != "rate_limited" || r.header.Get("Retry-After") == "" {
		t.Fatalf("429 without code/Retry-After: %s %v", r.body, r.header)
	}
	// Register shares the per-IP budget; another IP is unaffected.
	a.expect(a.call("POST", "/v1/auth/register", "", body, "X-Real-IP", "203.0.113.7"), http.StatusTooManyRequests)
	a.expect(a.call("POST", "/v1/auth/login", "", body, "X-Real-IP", "198.51.100.9"), http.StatusUnauthorized)

	// Per-account limit holds across many IPs.
	b := newTestAPI(t, func(c *config.Config) { c.LoginPerAccount, c.LoginAcctBurst = 1, 2 })
	victim := map[string]string{"email": "victim@example.test", "password": "guess-guess"}
	b.expect(b.call("POST", "/v1/auth/login", "", victim, "X-Real-IP", "192.0.2.1"), http.StatusUnauthorized)
	b.expect(b.call("POST", "/v1/auth/login", "", victim, "X-Real-IP", "192.0.2.2"), http.StatusUnauthorized)
	b.expect(b.call("POST", "/v1/auth/login", "", victim, "X-Real-IP", "192.0.2.3"), http.StatusTooManyRequests)
}

func TestBodyLimit(t *testing.T) {
	a := newTestAPI(t, nil)
	u := a.register("Big")
	huge := `{"lat":1,"lng":1,"recorded_at":"2026-01-01T00:00:00Z","activity":"` + strings.Repeat("x", 1<<20) + `"}`
	r := a.expect(u.call("POST", "/v1/location/heartbeat", huge), http.StatusRequestEntityTooLarge)
	if r.errCode(t) != "body_too_large" {
		t.Fatalf("code: %s", r.body)
	}
}

func TestFamiliesInvitesAndMembership(t *testing.T) {
	a := newTestAPI(t, nil)
	alice, bob, carol, dave := a.register("Alice"), a.register("Bob"), a.register("Carol"), a.register("Dave")

	var f createFamilyResponse
	alice.must("POST", "/v1/families", map[string]string{"name": "  Rivera family "}, http.StatusCreated, &f)
	if f.Name != "Rivera family" || f.Role != "admin" || f.MemberCount != 1 || len(f.Invite.Code) != 8 ||
		time.Until(f.Invite.ExpiresAt) < 47*time.Hour || time.Until(f.Invite.ExpiresAt) > 49*time.Hour {
		t.Fatalf("create family: %+v", f)
	}
	alice.must("POST", "/v1/families", map[string]string{"name": ""}, http.StatusBadRequest, nil)
	fam := "/v1/families/" + f.ID.String()

	// Join tolerates lower case and separators; re-joining is idempotent.
	typed := strings.ToLower(f.Invite.Code[:4] + "-" + f.Invite.Code[4:])
	var joined familyJSON
	bob.must("POST", "/v1/families/join", map[string]string{"code": typed}, http.StatusOK, &joined)
	if joined.ID != f.ID || joined.Role != "member" || joined.MemberCount != 2 {
		t.Fatalf("join: %+v", joined)
	}
	bob.must("POST", "/v1/families/join", map[string]string{"code": f.Invite.Code}, http.StatusOK, nil)
	var uses int
	if err := a.d.Admin(t).QueryRow(context.Background(), `SELECT uses FROM family_invites WHERE code=$1`, f.Invite.Code).Scan(&uses); err != nil || uses != 1 {
		t.Fatalf("re-join consumed a use: %d %v", uses, err)
	}
	if r := carol.call("POST", "/v1/families/join", map[string]string{"code": "ZZZZZZZZ"}); r.status != http.StatusNotFound || r.errCode(t) != "invite_invalid" {
		t.Fatalf("bad code: %d %s", r.status, r.body)
	}
	carol.must("POST", "/v1/families/join", map[string]string{"code": "short"}, http.StatusNotFound, nil)

	// Members list: default grants are 'emergency' both ways; self has none.
	var members []memberJSON
	bob.must("GET", fam+"/members", nil, http.StatusOK, &members)
	if len(members) != 2 {
		t.Fatalf("members: %+v", members)
	}
	for _, m := range members {
		switch m.UserID {
		case bob.id:
			if !m.IsMe || m.MyGrant != nil || m.TheirGrant != nil {
				t.Fatalf("self row: %+v", m)
			}
		case alice.id:
			if m.Role != "admin" || *m.MyGrant != "emergency" || *m.TheirGrant != "emergency" {
				t.Fatalf("alice row: %+v", m)
			}
		}
	}

	// Non-members get 404 (no existence leak); members who are not admin get 403.
	carol.must("GET", fam+"/members", nil, http.StatusNotFound, nil)
	carol.must("POST", fam+"/invites", nil, http.StatusNotFound, nil)
	carol.must("PATCH", fam, map[string]string{"name": "Hijacked"}, http.StatusNotFound, nil)
	carol.must("GET", "/v1/families/not-a-uuid/members", nil, http.StatusNotFound, nil)
	bob.must("POST", fam+"/invites", nil, http.StatusForbidden, nil)
	bob.must("PATCH", fam, map[string]string{"name": "Bob's"}, http.StatusForbidden, nil)
	bob.must("DELETE", fam+"/members/"+alice.id.String(), nil, http.StatusForbidden, nil)

	// Admin: new invite, rename.
	var inv inviteJSON
	alice.must("POST", fam+"/invites", nil, http.StatusCreated, &inv)
	if inv.Code == f.Invite.Code || inv.MaxUses != 10 {
		t.Fatalf("invite: %+v", inv)
	}
	var renamed familyJSON
	alice.must("PATCH", fam, map[string]string{"name": "Riveras"}, http.StatusOK, &renamed)
	if renamed.Name != "Riveras" {
		t.Fatalf("rename: %+v", renamed)
	}

	// Expired and used-up invites are rejected.
	admin := a.d.Admin(t)
	if _, err := admin.Exec(context.Background(), `UPDATE family_invites SET expires_at = now() - interval '1 minute' WHERE code=$1`, inv.Code); err != nil {
		t.Fatal(err)
	}
	carol.must("POST", "/v1/families/join", map[string]string{"code": inv.Code}, http.StatusNotFound, nil)
	alice.must("POST", fam+"/invites", nil, http.StatusCreated, &inv)
	if _, err := admin.Exec(context.Background(), `UPDATE family_invites SET max_uses = 1, uses = 1 WHERE code=$1`, inv.Code); err != nil {
		t.Fatal(err)
	}
	carol.must("POST", "/v1/families/join", map[string]string{"code": inv.Code}, http.StatusNotFound, nil)

	// /me lists the family.
	var me meResponse
	bob.must("GET", "/v1/me", nil, http.StatusOK, &me)
	if len(me.Families) != 1 || me.Families[0].ID != f.ID || me.Families[0].Role != "member" {
		t.Fatalf("me families: %+v", me.Families)
	}

	// Admin removes Bob; Bob loses access immediately.
	carol.must("POST", "/v1/families/join", map[string]string{"code": f.Invite.Code}, http.StatusOK, nil)
	dave.must("POST", "/v1/families/join", map[string]string{"code": f.Invite.Code}, http.StatusOK, nil)
	alice.must("DELETE", fam+"/members/"+uuid.NewString(), nil, http.StatusNotFound, nil)
	alice.must("DELETE", fam+"/members/"+bob.id.String(), nil, http.StatusNoContent, nil)
	bob.must("GET", fam+"/members", nil, http.StatusNotFound, nil)
	// Removal revokes every outstanding code Bob may know, so he cannot walk back in;
	// a fresh invite issued afterwards works.
	bob.must("POST", "/v1/families/join", map[string]string{"code": f.Invite.Code}, http.StatusNotFound, nil)
	var fresh inviteJSON
	alice.must("POST", fam+"/invites", nil, http.StatusCreated, &fresh)
	bob.must("POST", "/v1/families/join", map[string]string{"code": fresh.Code}, http.StatusOK, nil)
	// Leaving voluntarily does not revoke the family's invites.
	bob.must("DELETE", fam+"/members/"+bob.id.String(), nil, http.StatusNoContent, nil)
	bob.must("POST", "/v1/families/join", map[string]string{"code": fresh.Code}, http.StatusOK, nil)
	alice.must("DELETE", fam+"/members/"+bob.id.String(), nil, http.StatusNoContent, nil)

	// The last admin leaving promotes the longest-standing member (Carol).
	alice.must("DELETE", fam+"/members/"+alice.id.String(), nil, http.StatusNoContent, nil)
	carol.must("GET", fam+"/members", nil, http.StatusOK, &members)
	if len(members) != 2 || members[0].UserID != carol.id || members[0].Role != "admin" {
		t.Fatalf("promotion: %+v", members)
	}
	// Members leave; when the last one leaves the family is deleted.
	dave.must("DELETE", fam+"/members/"+dave.id.String(), nil, http.StatusNoContent, nil)
	carol.must("DELETE", fam+"/members/"+carol.id.String(), nil, http.StatusNoContent, nil)
	var n int
	if err := admin.QueryRow(context.Background(), `SELECT count(*) FROM families WHERE id=$1`, f.ID).Scan(&n); err != nil || n != 0 {
		t.Fatalf("empty family not deleted: %d %v", n, err)
	}
}

func TestGrants(t *testing.T) {
	a := newTestAPI(t, nil)
	alice, bob, carol := a.register("Alice"), a.register("Bob"), a.register("Carol")
	fid, _ := a.family(alice, bob)

	var grants []grantJSON
	alice.must("GET", "/v1/grants", nil, http.StatusOK, &grants)
	if len(grants) != 1 || grants[0].ViewerID != bob.id || grants[0].Level != "emergency" || !grants[0].IsDefault || grants[0].UpdatedAt != nil {
		t.Fatalf("default grants: %+v", grants)
	}
	var g grantJSON
	alice.must("PUT", "/v1/grants/"+bob.id.String(), map[string]string{"level": "live"}, http.StatusOK, &g)
	if g.Level != "live" || g.IsDefault || g.UpdatedAt == nil || g.DisplayName != "Bob" {
		t.Fatalf("put grant: %+v", g)
	}
	alice.must("PUT", "/v1/grants/"+bob.id.String(), map[string]string{"level": "bogus"}, http.StatusBadRequest, nil)
	alice.must("PUT", "/v1/grants/"+alice.id.String(), map[string]string{"level": "live"}, http.StatusBadRequest, nil)
	alice.must("PUT", "/v1/grants/"+carol.id.String(), map[string]string{"level": "live"}, http.StatusNotFound, nil)
	alice.must("PUT", "/v1/grants/"+uuid.NewString(), map[string]string{"level": "live"}, http.StatusNotFound, nil)

	// Bob sees Alice's grant towards him as their_grant; his own stays default.
	var members []memberJSON
	bob.must("GET", "/v1/families/"+fid.String()+"/members", nil, http.StatusOK, &members)
	for _, m := range members {
		if m.UserID == alice.id && (*m.TheirGrant != "live" || *m.MyGrant != "emergency") {
			t.Fatalf("bob's view of alice: %+v", m)
		}
	}
	// Strangers have no grants listed.
	carol.must("GET", "/v1/grants", nil, http.StatusOK, &grants)
	if len(grants) != 0 {
		t.Fatalf("carol grants: %+v", grants)
	}
}

func TestHeartbeatAndFamilyLocations(t *testing.T) {
	a := newTestAPI(t, nil)
	alice, bob, carol := a.register("Alice"), a.register("Bob"), a.register("Carol")
	a.family(alice, bob)
	now := time.Now()

	hb := alice.heartbeat(19.0760, 72.8777, now)
	if len(hb.PendingAlerts) != 0 || time.Since(hb.ServerTime) > time.Minute {
		t.Fatalf("heartbeat: %+v", hb)
	}
	// Default grant (emergency): Bob sees Alice listed but hidden, without coordinates.
	locs := bob.locations()
	if l := locs[alice.id]; l.Status != "hidden" || l.Lat != nil || l.DisplayName != "Alice" {
		t.Fatalf("default visibility: %+v", l)
	}
	// Live grant: coordinates appear.
	alice.grant(bob, "live")
	l := bob.locations()[alice.id]
	if l.Status != "live" || l.Lat == nil || *l.Lat != 19.0760 || *l.Lng != 72.8777 || *l.Activity != "walking" ||
		*l.BatteryPct != 81 || *l.AccuracyM != 12.5 || l.RecordedAt == nil {
		t.Fatalf("live visibility: %+v", l)
	}
	// Alice does not see Bob (he never granted live); Carol (stranger) sees nobody.
	if bob.heartbeat(19.1, 72.9, now); alice.locations()[bob.id].Status != "hidden" {
		t.Fatal("reverse visibility without grant")
	}
	if len(carol.locations()) != 0 {
		t.Fatal("stranger sees family")
	}
	// An older, out-of-order heartbeat never replaces the newer point.
	alice.heartbeat(10, 10, now.Add(-time.Minute))
	if l := bob.locations()[alice.id]; *l.Lat != 19.0760 {
		t.Fatalf("stale point overwrote newer: %+v", l)
	}

	// Pause: status paused, no coordinates. Validation of the pause window.
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "paused", "paused_until": now.Add(-time.Hour)}, http.StatusBadRequest, nil)
	alice.must("PATCH", "/v1/me/settings", map[string]any{"paused_until": now.Add(time.Hour)}, http.StatusBadRequest, nil)
	var st settingsJSON
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "paused", "paused_until": now.Add(time.Hour)}, http.StatusOK, &st)
	if st.SharingState != "paused" || st.PausedUntil == nil || st.EffectiveSharingState != "paused" {
		t.Fatalf("pause: %+v", st)
	}
	if l := bob.locations()[alice.id]; l.Status != "paused" || l.Lat != nil {
		t.Fatalf("paused visibility: %+v", l)
	}
	// Elapsed pause counts as on again.
	if _, err := a.d.Admin(t).Exec(context.Background(), `UPDATE user_settings SET paused_until = now() - interval '1 second' WHERE user_id=$1`, alice.id); err != nil {
		t.Fatal(err)
	}
	if l := bob.locations()[alice.id]; l.Status != "live" || l.Lat == nil {
		t.Fatalf("elapsed pause: %+v", l)
	}
	// Off: hidden, and the stored point is deleted (data minimisation); heartbeats are not stored.
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "off", "always_alert_on_accident": false}, http.StatusOK, &st)
	if st.SharingState != "off" || st.PausedUntil != nil || st.AlwaysAlertOnAccident {
		t.Fatalf("off: %+v", st)
	}
	alice.heartbeat(19.2, 72.9, time.Now())
	var n int
	if err := a.d.Admin(t).QueryRow(context.Background(), `SELECT count(*) FROM locations_latest WHERE user_id=$1`, alice.id).Scan(&n); err != nil || n != 0 {
		t.Fatalf("location kept while sharing off: %d %v", n, err)
	}
	if l := bob.locations()[alice.id]; l.Status != "hidden" {
		t.Fatalf("off visibility: %+v", l)
	}
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": nil}, http.StatusBadRequest, nil)
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "maybe"}, http.StatusBadRequest, nil)

	// Validation.
	for _, body := range []map[string]any{
		{"lat": 91, "lng": 0, "recorded_at": now},
		{"lat": 1, "lng": 181, "recorded_at": now},
		{"lng": 1, "recorded_at": now},
		{"lat": 1, "lng": 1},
		{"lat": 1, "lng": 1, "recorded_at": now.Add(time.Hour)},
		{"lat": 1, "lng": 1, "recorded_at": now, "battery_pct": 101},
		{"lat": 1, "lng": 1, "recorded_at": now, "activity": "teleporting"},
		{"lat": 1, "lng": 1, "recorded_at": now, "bearing_deg": -1},
	} {
		bob.must("POST", "/v1/location/heartbeat", body, http.StatusBadRequest, nil)
	}
}

func TestIncidentLifecycleAndVisibility(t *testing.T) {
	a := newTestAPI(t, nil)
	alice, bob, carol, erin := a.register("Alice"), a.register("Bob"), a.register("Carol"), a.register("Erin")
	a.family(alice, bob, erin)

	inc, status := alice.incident("fall")
	if status != http.StatusCreated || inc.Status != "active" || time.Until(inc.ShareExpiresAt) < 71*time.Hour {
		t.Fatalf("create: %d %+v", status, inc)
	}
	path := "/v1/incidents/" + inc.ID.String()

	// Bob (default emergency grant) is alerted via heartbeat and sees it.
	hb := bob.heartbeat(19.1, 72.9, time.Now())
	if len(hb.PendingAlerts) != 1 || hb.PendingAlerts[0].ID != inc.ID || hb.PendingAlerts[0].DisplayName != "Alice" ||
		hb.PendingAlerts[0].Kind != "fall" || hb.PendingAlerts[0].LastLocation == nil || hb.PendingAlerts[0].LastLocation.Lat != 19.0760 {
		t.Fatalf("pending alerts: %+v", hb.PendingAlerts)
	}
	var active []incidentSummary
	bob.must("GET", "/v1/incidents/active", nil, http.StatusOK, &active)
	if len(active) != 1 || active[0].IsMine {
		t.Fatalf("bob active: %+v", active)
	}
	alice.must("GET", "/v1/incidents/active", nil, http.StatusOK, &active)
	if len(active) != 1 || !active[0].IsMine {
		t.Fatalf("alice active: %+v", active)
	}
	var detail incidentDetail
	bob.must("GET", path, nil, http.StatusOK, &detail)
	if len(detail.Trail) != 3 || detail.Trail[0].Landmark == nil || *detail.Trail[0].Landmark != "Dadar Station" ||
		!strings.Contains(string(detail.Details), "peak_g") || *detail.Lat != 19.0760 {
		t.Fatalf("detail: %+v", detail)
	}

	// Strangers and 'off' grants see nothing: 404, same as a random id.
	carol.must("GET", path, nil, http.StatusNotFound, nil)
	carol.must("POST", path+"/ack", map[string]string{"state": "seen"}, http.StatusNotFound, nil)
	carol.must("POST", path+"/resolve", map[string]string{"resolution": "handled"}, http.StatusNotFound, nil)
	carol.must("GET", "/v1/incidents/"+uuid.NewString(), nil, http.StatusNotFound, nil)
	carol.must("GET", "/v1/incidents/active", nil, http.StatusOK, &active)
	if len(active) != 0 {
		t.Fatal("stranger sees active incidents")
	}
	alice.grant(erin, "off")
	erin.must("GET", path, nil, http.StatusNotFound, nil)
	if hb := erin.heartbeat(19.1, 72.9, time.Now()); len(hb.PendingAlerts) != 0 {
		t.Fatal("grant off still alerted")
	}

	// Acks: viewer only, never downgraded; owner sees all acks.
	alice.must("POST", path+"/ack", map[string]string{"state": "seen"}, http.StatusBadRequest, nil)
	bob.must("POST", path+"/ack", map[string]string{"state": "maybe"}, http.StatusBadRequest, nil)
	var ack ackJSON
	bob.must("POST", path+"/ack", map[string]string{"state": "on_my_way"}, http.StatusOK, &ack)
	bob.must("POST", path+"/ack", map[string]string{"state": "seen"}, http.StatusOK, &ack)
	if ack.State != "on_my_way" {
		t.Fatalf("ack downgraded: %+v", ack)
	}
	if hb := bob.heartbeat(19.1, 72.9, time.Now()); len(hb.PendingAlerts) != 0 {
		t.Fatal("acked incident still pending")
	}
	alice.must("GET", path, nil, http.StatusOK, &detail)
	if len(detail.Acks) != 1 || detail.Acks[0].UserID != bob.id || detail.Acks[0].State != "on_my_way" {
		t.Fatalf("owner acks: %+v", detail.Acks)
	}

	// Live trail updates: owner only; duplicates ignored.
	pts := map[string]any{"points": []map[string]any{
		{"lat": 19.0770, "lng": 72.8790, "recorded_at": time.Now().UTC().Format(time.RFC3339Nano), "accuracy_m": 5},
	}}
	bob.must("POST", path+"/trail", pts, http.StatusForbidden, nil)
	var acc map[string]int
	alice.must("POST", path+"/trail", pts, http.StatusOK, &acc)
	if acc["accepted"] != 1 {
		t.Fatalf("accepted %v", acc)
	}
	alice.must("POST", path+"/trail", pts, http.StatusOK, &acc)
	if acc["accepted"] != 0 {
		t.Fatalf("duplicate accepted %v", acc)
	}
	alice.must("POST", path+"/trail", map[string]any{"points": []map[string]any{{"lat": 200, "lng": 1, "recorded_at": time.Now()}}}, http.StatusBadRequest, nil)
	bob.must("GET", path, nil, http.StatusOK, &detail)
	if detail.LastLocation == nil || detail.LastLocation.Lat != 19.0770 || len(detail.Trail) != 4 {
		t.Fatalf("last location after update: %+v", detail.LastLocation)
	}

	// Resolution: a viewer may only mark it handled.
	bob.must("POST", path+"/resolve", map[string]string{"resolution": "user_ok"}, http.StatusForbidden, nil)
	bob.must("POST", path+"/resolve", map[string]string{"resolution": "nope"}, http.StatusBadRequest, nil)
	var resolved incidentSummary
	bob.must("POST", path+"/resolve", map[string]string{"resolution": "handled"}, http.StatusOK, &resolved)
	if resolved.Status != "resolved" || *resolved.Resolution != "handled" || resolved.ResolvedAt == nil {
		t.Fatalf("resolved: %+v", resolved)
	}
	alice.must("POST", path+"/resolve", map[string]string{"resolution": "user_ok"}, http.StatusConflict, nil)
	alice.must("POST", path+"/trail", pts, http.StatusConflict, nil)
	bob.must("POST", path+"/ack", map[string]string{"state": "seen"}, http.StatusConflict, nil)
	alice.must("GET", "/v1/incidents/active", nil, http.StatusOK, &active)
	if len(active) != 0 {
		t.Fatal("resolved incident still active")
	}

	// Owner cancels a false alarm.
	inc2, _ := alice.incident("vehicle_crash")
	alice.must("POST", "/v1/incidents/"+inc2.ID.String()+"/resolve", map[string]string{"resolution": "false_alarm"}, http.StatusOK, &resolved)
	if resolved.Status != "cancelled" {
		t.Fatalf("false alarm: %+v", resolved)
	}

	// Sharing off: incidents stay visible while always_alert is on (default) ...
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "off"}, http.StatusOK, nil)
	inc3, _ := alice.incident("sos")
	bob.must("GET", "/v1/incidents/"+inc3.ID.String(), nil, http.StatusOK, nil)
	// ... and disappear when always_alert is off.
	alice.must("PATCH", "/v1/me/settings", map[string]any{"always_alert_on_accident": false}, http.StatusOK, nil)
	bob.must("GET", "/v1/incidents/"+inc3.ID.String(), nil, http.StatusNotFound, nil)

	// Removal from the family revokes access immediately.
	alice.must("PATCH", "/v1/me/settings", map[string]any{"sharing_state": "on"}, http.StatusOK, nil)
	bob.must("GET", "/v1/incidents/"+inc3.ID.String(), nil, http.StatusOK, nil)
	var me meResponse
	alice.must("GET", "/v1/me", nil, http.StatusOK, &me)
	alice.must("DELETE", "/v1/families/"+me.Families[0].ID.String()+"/members/"+bob.id.String(), nil, http.StatusNoContent, nil)
	bob.must("GET", "/v1/incidents/"+inc3.ID.String(), nil, http.StatusNotFound, nil)

	// Validation.
	for _, body := range []map[string]any{
		{"kind": "meteor", "detected_at": time.Now()},
		{"kind": "fall"},
		{"kind": "fall", "detected_at": time.Now().Add(time.Hour)},
		{"kind": "fall", "detected_at": time.Now(), "lat": 1},
		{"kind": "fall", "detected_at": time.Now(), "details": []int{1}},
		{"kind": "fall", "detected_at": time.Now(), "trail": []map[string]any{{"lat": 1, "lng": 1}}},
	} {
		alice.must("POST", "/v1/incidents", body, http.StatusBadRequest, nil)
	}
	// An incident without a GPS fix is still accepted.
	alice.must("POST", "/v1/incidents", map[string]any{"kind": "sos", "detected_at": time.Now()}, http.StatusCreated, nil)
}

func TestIncidentIdempotencyKey(t *testing.T) {
	a := newTestAPI(t, nil)
	alice := a.register("Alice")
	key := "Idempotency-Key"
	at := time.Now().UTC()
	first, s1 := alice.incidentAt("pedestrian_impact", at, key, "android-7f3a")
	second, s2 := alice.incidentAt("pedestrian_impact", at, key, "android-7f3a")
	if s1 != http.StatusCreated || s2 != http.StatusOK || first.ID != second.ID || first.ShareURL == second.ShareURL {
		t.Fatalf("idempotent retry: %d %+v / %d %+v", s1, first, s2, second)
	}
	// The link was rotated: the old one no longer works, the new one does.
	a.expect(a.call("GET", sharePath(t, first.ShareURL)+"/data", "", nil), http.StatusNotFound)
	var d struct {
		Trail []json.RawMessage `json:"trail"`
	}
	a.expect(a.call("GET", sharePath(t, second.ShareURL)+"/data", "", nil), http.StatusOK).decode(t, &d)
	if len(d.Trail) != 3 {
		t.Fatalf("trail duplicated or lost on retry: %d", len(d.Trail))
	}
	alice.must("POST", "/v1/incidents", map[string]any{"kind": "sos", "detected_at": time.Now()}, http.StatusBadRequest, nil, key, "bad key with spaces")
}

func TestPublicSharePage(t *testing.T) {
	a := newTestAPI(t, nil)
	alice, bob := a.register("Alice Secretname"), a.register("Bob")
	a.family(alice, bob)
	inc, _ := alice.incident("vehicle_crash")
	path := sharePath(t, inc.ShareURL)

	// JSON feed: location, trail, landmarks; no identity.
	r := a.expect(a.call("GET", path+"/data", "", nil), http.StatusOK)
	if r.header.Get("Cache-Control") != "no-store" || !strings.Contains(r.header.Get("X-Robots-Tag"), "noindex") {
		t.Fatalf("data headers: %v", r.header)
	}
	for _, leak := range []string{"Secretname", alice.id.String(), alice.email, "user_id", "display_name", "details", "peak_g"} {
		if strings.Contains(string(r.body), leak) {
			t.Fatalf("share data leaks %q: %s", leak, r.body)
		}
	}
	var d struct {
		Kind, Status string
		Latest       *struct{ Lat, Lng float64 }
		Trail        []struct {
			Lat, Lng float64
			Landmark *string
		}
	}
	r.decode(t, &d)
	if d.Kind != "vehicle_crash" || d.Status != "active" || len(d.Trail) != 3 || d.Latest == nil || d.Latest.Lat != 19.0760 {
		t.Fatalf("share data: %+v", d)
	}

	// HTML page: CSP, noindex, no-store, escaped landmarks, vendored module script.
	r = a.expect(a.call("GET", path, "", nil), http.StatusOK)
	html := string(r.body)
	csp := r.header.Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'self'") || !strings.Contains(csp, "https://tiles.openfreemap.org") || strings.Contains(csp, "unsafe") {
		t.Fatalf("csp: %q", csp)
	}
	if r.header.Get("Cache-Control") != "no-store" || r.header.Get("Referrer-Policy") != "no-referrer" ||
		!strings.HasPrefix(r.header.Get("Content-Type"), "text/html") {
		t.Fatalf("page headers: %v", r.header)
	}
	for _, want := range []string{`name="robots" content="noindex,nofollow"`, `<script type="module" src="/static/share.js">`,
		"/static/maplibre-gl/6.11.2/maplibre-gl.css", "Possible vehicle crash", "19.076000, 72.877700", "Dadar Station",
		"&lt;script&gt;alert(1)&lt;/script&gt; Café", "openstreetmap.org/?mlat=19.076000", `href="geo:19.076000,72.877700"`} {
		if !strings.Contains(html, want) {
			t.Errorf("page missing %q", want)
		}
	}
	if strings.Contains(html, "<script>alert(1)") || strings.Contains(html, "Secretname") || strings.Contains(html, "style=") {
		t.Fatal("page contains unescaped input, identity, or inline style")
	}

	// Static assets: correct JS MIME for ES modules, same CSP (workers inherit it), caching.
	r = a.expect(a.call("GET", "/static/share.js", "", nil), http.StatusOK)
	if !strings.HasPrefix(r.header.Get("Content-Type"), "text/javascript") || r.header.Get("Content-Security-Policy") == "" ||
		!strings.Contains(string(r.body), "./maplibre-gl/6.11.2/maplibre-gl.mjs") {
		t.Fatalf("share.js: %v", r.header)
	}
	r = a.expect(a.call("GET", "/static/maplibre-gl/6.11.2/maplibre-gl-worker.mjs", "", nil), http.StatusOK)
	if !strings.Contains(r.header.Get("Cache-Control"), "immutable") || !strings.HasPrefix(r.header.Get("Content-Type"), "text/javascript") ||
		!strings.Contains(r.header.Get("Content-Security-Policy"), "connect-src 'self' https://tiles.openfreemap.org") {
		t.Fatalf("worker headers: %v", r.header)
	}
	a.expect(a.call("GET", "/static/maplibre-gl/6.11.2/maplibre-gl.mjs", "", nil, "If-None-Match", r.header.Get("ETag")), http.StatusOK)
	a.expect(a.call("GET", "/static/maplibre-gl/6.11.2/maplibre-gl-worker.mjs", "", nil, "If-None-Match", r.header.Get("ETag")), http.StatusNotModified)
	a.expect(a.call("GET", "/static/nope.js", "", nil), http.StatusNotFound)
	a.expect(a.call("GET", "/static/maplibre-gl/6.11.2/maplibre-gl.css", "", nil), http.StatusOK)

	// Invalid / unknown tokens: identical 404 (page and data).
	for _, p := range []string{"/s/short", "/s/" + strings.Repeat("A", 43), "/s/" + strings.Repeat("A", 43) + "/data"} {
		r := a.expect(a.call("GET", p, "", nil), http.StatusNotFound)
		if r.header.Get("Cache-Control") != "no-store" {
			t.Fatalf("%s: headers %v", p, r.header)
		}
	}

	// Resolved: the link still answers but no longer reveals location or trail.
	alice.must("POST", "/v1/incidents/"+inc.ID.String()+"/resolve", map[string]string{"resolution": "user_ok"}, http.StatusOK, nil)
	r = a.expect(a.call("GET", path+"/data", "", nil), http.StatusOK)
	r.decode(t, &d)
	if d.Status != "resolved" || d.Latest != nil || len(d.Trail) != 0 {
		t.Fatalf("resolved share still exposes location: %s", r.body)
	}
	html = string(a.expect(a.call("GET", path, "", nil), http.StatusOK).body)
	if !strings.Contains(html, "They reported that they are OK.") || strings.Contains(html, "19.076000") || strings.Contains(html, `id="map"`) {
		t.Fatalf("resolved page: %s", html)
	}

	// Expired link: 404 like any unknown token.
	if _, err := a.d.Admin(t).Exec(context.Background(), `UPDATE incidents SET share_expires_at = now() - interval '1 second' WHERE id=$1`, inc.ID); err != nil {
		t.Fatal(err)
	}
	a.expect(a.call("GET", path, "", nil), http.StatusNotFound)
	a.expect(a.call("GET", path+"/data", "", nil), http.StatusNotFound)
}

func TestShareRateLimit(t *testing.T) {
	a := newTestAPI(t, func(c *config.Config) { c.SharePerMinute, c.ShareBurst = 1, 2 })
	p := "/s/" + strings.Repeat("B", 43) + "/data"
	a.expect(a.call("GET", p, "", nil, "X-Real-IP", "192.0.2.50"), http.StatusNotFound)
	a.expect(a.call("GET", p, "", nil, "X-Real-IP", "192.0.2.50"), http.StatusNotFound)
	a.expect(a.call("GET", p, "", nil, "X-Real-IP", "192.0.2.50"), http.StatusTooManyRequests)
}
