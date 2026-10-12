package e2e

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// cfg is the suite configuration (see doc.go).
var cfg struct {
	baseURL   string
	trustedIP bool
	jwtSecret []byte
	psql      string
	matrix    string
}

var httpClient = &http.Client{
	Timeout: 60 * time.Second,
	// Never follow redirects: a redirect is itself a result worth asserting.
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

func TestMain(m *testing.M) {
	cfg.baseURL = strings.TrimRight(os.Getenv("E2E_BASE_URL"), "/")
	if cfg.baseURL == "" {
		fmt.Println("e2e: E2E_BASE_URL is not set; the black-box suite did not run (see doc.go)")
		os.Exit(0)
	}
	cfg.trustedIP = os.Getenv("E2E_TRUSTED_IP_HEADER") == "1"
	if s := strings.TrimSpace(os.Getenv("E2E_JWT_SECRET")); s != "" {
		b, err := hex.DecodeString(s)
		if err != nil || len(b) < 32 {
			fmt.Fprintln(os.Stderr, "e2e: E2E_JWT_SECRET must be hex encoded, at least 32 bytes")
			os.Exit(2)
		}
		cfg.jwtSecret = b
	}
	cfg.psql = strings.TrimSpace(os.Getenv("E2E_PSQL"))
	cfg.matrix = os.Getenv("E2E_MATRIX_FILE")

	code := m.Run()
	writeMatrix(os.Stdout, false)
	if cfg.matrix != "" {
		if f, err := os.Create(cfg.matrix); err == nil {
			writeMatrix(f, true)
			_ = f.Close()
		} else {
			fmt.Fprintln(os.Stderr, "e2e: cannot write matrix:", err)
		}
	}
	os.Exit(code)
}

// ---------------------------------------------------------------------------
// Result matrix

type outcome struct{ name, result, note string }

var matrix struct {
	sync.Mutex
	rows  []outcome
	notes map[string]string
}

// check runs fn as a named subtest and records its outcome in the matrix.
func check(t *testing.T, name string, fn func(t *testing.T)) {
	t.Helper()
	t.Run(name, func(t *testing.T) {
		t.Cleanup(func() {
			res := "PASS"
			switch {
			case t.Failed():
				res = "FAIL"
			case t.Skipped():
				res = "SKIP"
			}
			matrix.Lock()
			matrix.rows = append(matrix.rows, outcome{name: t.Name(), result: res, note: matrix.notes[t.Name()]})
			matrix.Unlock()
		})
		fn(t)
	})
}

// annotate attaches a note (documented behaviour, skip reason) to the current check.
func annotate(t *testing.T, note string) {
	matrix.Lock()
	defer matrix.Unlock()
	if matrix.notes == nil {
		matrix.notes = map[string]string{}
	}
	matrix.notes[t.Name()] = note
}

func skip(t *testing.T, reason string) {
	annotate(t, reason)
	t.Skip(reason)
}

func writeMatrix(w io.Writer, markdown bool) {
	matrix.Lock()
	defer matrix.Unlock()
	rows := append([]outcome(nil), matrix.rows...)
	sort.Slice(rows, func(i, j int) bool { return rows[i].name < rows[j].name })
	counts := map[string]int{}
	if markdown {
		fmt.Fprintf(w, "| Result | Check | Note |\n|---|---|---|\n")
	} else {
		fmt.Fprintf(w, "\n==== e2e check matrix (%s) ====\n", cfg.baseURL)
	}
	for _, r := range rows {
		counts[r.result]++
		if markdown {
			fmt.Fprintf(w, "| %s | `%s` | %s |\n", r.result, r.name, strings.ReplaceAll(r.note, "|", "/"))
			continue
		}
		line := fmt.Sprintf("%-4s  %s", r.result, r.name)
		if r.note != "" {
			line += "  -- " + r.note
		}
		fmt.Fprintln(w, line)
	}
	fmt.Fprintf(w, "\nTOTAL %d: PASS %d, FAIL %d, SKIP %d\n", len(rows), counts["PASS"], counts["FAIL"], counts["SKIP"])
}

// ---------------------------------------------------------------------------
// Randomness

func randBytes(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b) // never fails (crypto/rand panics on a broken source)
	return b
}

func randHex(n int) string { return hex.EncodeToString(randBytes(n)) }

// randToken has the shape of a real share/refresh token (32 bytes, base64url).
func randToken() string { return base64.RawURLEncoding.EncodeToString(randBytes(32)) }

func randUUID() string {
	b := randBytes(16)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

var ips struct {
	sync.Mutex
	used map[string]bool
}

// newIP returns a fresh synthetic client address (198.18.0.0/15, RFC 2544).
// It is only sent (as X-Real-IP) when the API trusts that header.
func newIP() string {
	ips.Lock()
	defer ips.Unlock()
	if ips.used == nil {
		ips.used = map[string]bool{}
	}
	for {
		b := randBytes(3)
		ip := fmt.Sprintf("198.%d.%d.%d", 18+int(b[0]&1), b[1], 1+int(b[2])%254)
		if !ips.used[ip] {
			ips.used[ip] = true
			return ip
		}
	}
}

// ---------------------------------------------------------------------------
// HTTP

type request struct {
	method  string
	path    string
	token   string            // bearer access token
	body    any               // nil, []byte / string (raw), or a value to JSON-encode
	header  map[string]string // applied last (can override Authorization)
	ip      string            // X-Real-IP, sent only when cfg.trustedIP
	chunked bool              // send the body without Content-Length
}

type response struct {
	method, path string
	status       int
	header       http.Header
	body         []byte
}

func (r *response) String() string {
	b := string(r.body)
	if len(b) > 500 {
		b = b[:500] + "..."
	}
	return fmt.Sprintf("%s %s -> %d %s", r.method, r.path, r.status, strings.TrimSpace(b))
}

func (r *response) decode(t testing.TB, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decode %s: %v", r, err)
	}
}

func (r *response) errCode() string {
	var e struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	_ = json.Unmarshal(r.body, &e)
	return e.Error.Code
}

func send(t testing.TB, rq request) *response {
	t.Helper()
	r, err := doRequest(rq)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// doRequest performs rq without a testing.T, so it is safe in goroutines.
func doRequest(rq request) (*response, error) {
	var body io.Reader
	switch b := rq.body.(type) {
	case nil:
	case []byte:
		body = bytes.NewReader(b)
	case string:
		body = strings.NewReader(b)
	default:
		j, err := json.Marshal(b)
		if err != nil {
			return nil, fmt.Errorf("marshal request body: %w", err)
		}
		body = bytes.NewReader(j)
	}
	if rq.chunked && body != nil {
		body = io.MultiReader(body) // hides the length: Transfer-Encoding: chunked
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, rq.method, cfg.baseURL+rq.path, body)
	if err != nil {
		return nil, fmt.Errorf("build %s %s: %w", rq.method, rq.path, err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if rq.token != "" {
		req.Header.Set("Authorization", "Bearer "+rq.token)
	}
	if rq.ip != "" && cfg.trustedIP {
		req.Header.Set("X-Real-IP", rq.ip)
	}
	for k, v := range rq.header {
		req.Header.Set(k, v)
	}
	res, err := httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s %s: %w", rq.method, rq.path, err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, 16<<20))
	if err != nil {
		return nil, fmt.Errorf("read %s %s: %w", rq.method, rq.path, err)
	}
	return &response{method: rq.method, path: rq.path, status: res.StatusCode, header: res.Header, body: b}, nil
}

// sendRetry is send for setup calls: a 429 is waited out (Retry-After) rather
// than failing, because behind a proxy every actor shares one client IP.
func sendRetry(t testing.TB, rq request) *response {
	t.Helper()
	deadline := time.Now().Add(6 * time.Minute)
	for {
		r := send(t, rq)
		if r.status != http.StatusTooManyRequests || time.Now().After(deadline) {
			return r
		}
		time.Sleep(retryAfter(r))
	}
}

func retryAfter(r *response) time.Duration {
	secs, err := strconv.Atoi(r.header.Get("Retry-After"))
	if err != nil || secs < 1 {
		secs = 6 // nginx limit_req sends no Retry-After; 10/min refills every 6 s
	}
	return time.Duration(min(secs, 65))*time.Second + 250*time.Millisecond
}

func want(t testing.TB, r *response, status int) {
	t.Helper()
	if r.status != status {
		t.Fatalf("want HTTP %d, got %s", status, r)
	}
}

func wantErr(t testing.TB, r *response, status int, code string) {
	t.Helper()
	want(t, r, status)
	if got := r.errCode(); got != code {
		t.Fatalf("want error code %q, got %s", code, r)
	}
}

// sameResponse asserts two responses are indistinguishable (status + body):
// the basis of every "does not leak existence" check.
func sameResponse(t testing.TB, a, b *response) {
	t.Helper()
	if a.status != b.status || !bytes.Equal(a.body, b.body) {
		t.Fatalf("responses differ (existence leak):\n  %s\n  %s", a, b)
	}
}

func notContains(t testing.TB, r *response, needles ...string) {
	t.Helper()
	for _, n := range needles {
		if n != "" && bytes.Contains(r.body, []byte(n)) {
			t.Fatalf("response must not contain %q: %s", n, r)
		}
	}
}

// ---------------------------------------------------------------------------
// Actors and API helpers

type actor struct {
	role        string
	email       string
	password    string
	displayName string
	id          string
	access      string
	refresh     string
	ip          string
}

type userJSON struct {
	ID          string `json:"id"`
	Email       string `json:"email"`
	DisplayName string `json:"display_name"`
}

type authResp struct {
	AccessToken  string   `json:"access_token"`
	TokenType    string   `json:"token_type"`
	ExpiresIn    int      `json:"expires_in"`
	RefreshToken string   `json:"refresh_token"`
	User         userJSON `json:"user"`
}

func registerBody(email, password, name string) map[string]string {
	return map[string]string{"email": email, "password": password, "display_name": name}
}

// emailPrefix marks every account this suite creates (cleanup.sql deletes them).
const emailPrefix = "e2e-sec-"

func randomEmail() string { return emailPrefix + randHex(8) + "@example.test" }

// newActor registers a throwaway account.
func newActor(t testing.TB, role string) *actor {
	t.Helper()
	a := &actor{
		role:        role,
		email:       randomEmail(),
		password:    "pw-" + randHex(12),
		displayName: "e2e " + role + " " + randHex(3),
		ip:          newIP(),
	}
	r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/register", ip: a.ip,
		body: registerBody(a.email, a.password, a.displayName)})
	want(t, r, http.StatusCreated)
	var ar authResp
	r.decode(t, &ar)
	if !uuidRE.MatchString(ar.User.ID) || ar.AccessToken == "" || ar.RefreshToken == "" {
		t.Fatalf("incomplete register response: %s", r)
	}
	a.id, a.access, a.refresh = ar.User.ID, ar.AccessToken, ar.RefreshToken
	return a
}

func (a *actor) do(t testing.TB, method, path string, body any) *response {
	t.Helper()
	return send(t, request{method: method, path: path, token: a.access, ip: a.ip, body: body})
}

func (a *actor) doHeader(t testing.TB, method, path string, body any, header map[string]string) *response {
	t.Helper()
	return send(t, request{method: method, path: path, token: a.access, ip: a.ip, body: body, header: header})
}

func (a *actor) must(t testing.TB, method, path string, body any, status int) *response {
	t.Helper()
	r := a.do(t, method, path, body)
	want(t, r, status)
	return r
}

func (a *actor) login(t testing.TB) authResp {
	t.Helper()
	r := sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/login", ip: a.ip,
		body: map[string]string{"email": a.email, "password": a.password}})
	want(t, r, http.StatusOK)
	var ar authResp
	r.decode(t, &ar)
	return ar
}

func refresh(t testing.TB, ip, token string) *response {
	t.Helper()
	return sendRetry(t, request{method: http.MethodPost, path: "/v1/auth/refresh", ip: ip,
		body: map[string]string{"refresh_token": token}})
}

type familyJSON struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Role        string `json:"role"`
	MemberCount int    `json:"member_count"`
}

type inviteJSON struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expires_at"`
	MaxUses   int       `json:"max_uses"`
}

func (a *actor) createFamily(t testing.TB, name string) (id, code string) {
	t.Helper()
	var f struct {
		familyJSON
		Invite inviteJSON `json:"invite"`
	}
	a.must(t, http.MethodPost, "/v1/families", map[string]string{"name": name}, http.StatusCreated).decode(t, &f)
	if !uuidRE.MatchString(f.ID) || len(f.Invite.Code) != 8 || f.Role != "admin" {
		t.Fatalf("unexpected family: %+v", f)
	}
	return f.ID, f.Invite.Code
}

func (a *actor) invite(t testing.TB, familyID string) string {
	t.Helper()
	var inv inviteJSON
	a.must(t, http.MethodPost, "/v1/families/"+familyID+"/invites", nil, http.StatusCreated).decode(t, &inv)
	return inv.Code
}

func (a *actor) join(t testing.TB, code string) *response {
	t.Helper()
	return sendRetry(t, request{method: http.MethodPost, path: "/v1/families/join", token: a.access, ip: a.ip,
		body: map[string]string{"code": code}})
}

func (a *actor) mustJoin(t testing.TB, code string) string {
	t.Helper()
	r := a.join(t, code)
	want(t, r, http.StatusOK)
	var f familyJSON
	r.decode(t, &f)
	return f.ID
}

// newFamily creates a family administered by admin and joins the members.
func newFamily(t testing.TB, admin *actor, members ...*actor) (familyID, code string) {
	t.Helper()
	familyID, code = admin.createFamily(t, "e2e family "+randHex(3))
	for _, m := range members {
		m.mustJoin(t, code)
	}
	return familyID, code
}

type memberJSON struct {
	UserID      string  `json:"user_id"`
	DisplayName string  `json:"display_name"`
	Role        string  `json:"role"`
	IsMe        bool    `json:"is_me"`
	MyGrant     *string `json:"my_grant"`
	TheirGrant  *string `json:"their_grant"`
}

func (a *actor) members(t testing.TB, familyID string) []memberJSON {
	t.Helper()
	var ms []memberJSON
	a.must(t, http.MethodGet, "/v1/families/"+familyID+"/members", nil, http.StatusOK).decode(t, &ms)
	return ms
}

type meJSON struct {
	User     userJSON     `json:"user"`
	Families []familyJSON `json:"families"`
	Settings struct {
		SharingState          string `json:"sharing_state"`
		AlwaysAlertOnAccident bool   `json:"always_alert_on_accident"`
		EffectiveSharingState string `json:"effective_sharing_state"`
	} `json:"settings"`
}

func (a *actor) me(t testing.TB) meJSON {
	t.Helper()
	var m meJSON
	a.must(t, http.MethodGet, "/v1/me", nil, http.StatusOK).decode(t, &m)
	return m
}

func (a *actor) settings(t testing.TB, patch map[string]any) {
	t.Helper()
	a.must(t, http.MethodPatch, "/v1/me/settings", patch, http.StatusOK)
}

type grantJSON struct {
	ViewerID    string `json:"viewer_id"`
	DisplayName string `json:"display_name"`
	Level       string `json:"level"`
	IsDefault   bool   `json:"is_default"`
}

func (a *actor) setGrant(t testing.TB, viewer *actor, level string) {
	t.Helper()
	var g grantJSON
	a.must(t, http.MethodPut, "/v1/grants/"+viewer.id, map[string]string{"level": level}, http.StatusOK).decode(t, &g)
	if g.ViewerID != viewer.id || g.Level != level {
		t.Fatalf("grant not applied: %+v", g)
	}
}

func (a *actor) grants(t testing.TB) []grantJSON {
	t.Helper()
	var gs []grantJSON
	a.must(t, http.MethodGet, "/v1/grants", nil, http.StatusOK).decode(t, &gs)
	return gs
}

// grantTo returns a's outgoing grant towards viewer (false if not listed).
func (a *actor) grantTo(t testing.TB, viewer *actor) (grantJSON, bool) {
	t.Helper()
	for _, g := range a.grants(t) {
		if g.ViewerID == viewer.id {
			return g, true
		}
	}
	return grantJSON{}, false
}

type summaryJSON struct {
	ID         string  `json:"id"`
	UserID     string  `json:"user_id"`
	Kind       string  `json:"kind"`
	Status     string  `json:"status"`
	Resolution *string `json:"resolution"`
	IsMine     bool    `json:"is_mine"`
	MyAck      *string `json:"my_ack"`
}

type heartbeatJSON struct {
	PendingAlerts []summaryJSON `json:"pending_alerts"`
	ServerTime    time.Time     `json:"server_time"`
}

func (a *actor) heartbeat(t testing.TB, lat, lng float64) heartbeatJSON {
	t.Helper()
	var hb heartbeatJSON
	a.must(t, http.MethodPost, "/v1/location/heartbeat", map[string]any{
		"lat": lat, "lng": lng, "accuracy_m": 8, "speed_mps": 1.2, "bearing_deg": 90,
		"activity": "walking", "battery_pct": 77, "recorded_at": time.Now().UTC().Format(time.RFC3339Nano),
	}, http.StatusOK).decode(t, &hb)
	return hb
}

type locationJSON struct {
	UserID      string   `json:"user_id"`
	DisplayName string   `json:"display_name"`
	Status      string   `json:"status"`
	Lat         *float64 `json:"lat"`
	Lng         *float64 `json:"lng"`
}

func (a *actor) locations(t testing.TB) []locationJSON {
	t.Helper()
	var ls []locationJSON
	a.must(t, http.MethodGet, "/v1/family/locations", nil, http.StatusOK).decode(t, &ls)
	return ls
}

// view reports how a sees other on GET /v1/family/locations.
func (a *actor) view(t testing.TB, other *actor) (loc locationJSON, listed bool) {
	t.Helper()
	for _, l := range a.locations(t) {
		if l.UserID == other.id {
			return l, true
		}
	}
	return locationJSON{}, false
}

func (a *actor) expectView(t testing.TB, other *actor, status string, coords bool) locationJSON {
	t.Helper()
	l, listed := a.view(t, other)
	if !listed {
		t.Fatalf("%s does not list %s at all", a.role, other.role)
	}
	if l.Status != status || (l.Lat != nil) != coords || (l.Lng != nil) != coords {
		t.Fatalf("%s sees %s as status=%q coords=%v, want status=%q coords=%v", a.role, other.role, l.Status, l.Lat != nil, status, coords)
	}
	return l
}

func (a *actor) expectNotListed(t testing.TB, others ...*actor) {
	t.Helper()
	ls := a.locations(t)
	for _, o := range others {
		for _, l := range ls {
			if l.UserID == o.id {
				t.Fatalf("%s must not see %s at all, got %+v", a.role, o.role, l)
			}
		}
	}
}

func near(a, b float64) bool { return math.Abs(a-b) < 1e-6 }

type createdIncident struct {
	ID             string    `json:"id"`
	Status         string    `json:"status"`
	ShareURL       string    `json:"share_url"`
	ShareExpiresAt time.Time `json:"share_expires_at"`
}

func (c createdIncident) token(t testing.TB) string {
	t.Helper()
	i := strings.LastIndex(c.ShareURL, "/s/")
	if i < 0 {
		t.Fatalf("unexpected share_url %q", c.ShareURL)
	}
	return c.ShareURL[i+3:]
}

func trailPoint(lat, lng float64, at time.Time, landmark string) map[string]any {
	p := map[string]any{"lat": lat, "lng": lng, "recorded_at": at.UTC().Format(time.RFC3339Nano), "accuracy_m": 6}
	if landmark != "" {
		p["landmark"] = landmark
	}
	return p
}

// incidentBody is a POST /v1/incidents body with a three-point trail ending at (lat, lng).
func incidentBody(kind string, lat, lng float64, landmark string) map[string]any {
	now := time.Now().UTC()
	return map[string]any{
		"kind": kind, "detected_at": now.Format(time.RFC3339Nano), "lat": lat, "lng": lng,
		"details": map[string]any{"peak_g": 6.1, "source": "e2e"},
		"trail": []any{
			trailPoint(lat-0.002, lng-0.002, now.Add(-3*time.Minute), landmark),
			trailPoint(lat-0.001, lng-0.001, now.Add(-2*time.Minute), ""),
			trailPoint(lat, lng, now.Add(-1*time.Minute), ""),
		},
	}
}

func (a *actor) newIncident(t testing.TB, kind string, lat, lng float64, landmark string) createdIncident {
	t.Helper()
	var c createdIncident
	a.must(t, http.MethodPost, "/v1/incidents", incidentBody(kind, lat, lng, landmark), http.StatusCreated).decode(t, &c)
	if !uuidRE.MatchString(c.ID) || c.Status != "active" {
		t.Fatalf("unexpected incident: %+v", c)
	}
	return c
}

func containsIncident(list []summaryJSON, id string) bool {
	for _, s := range list {
		if s.ID == id {
			return true
		}
	}
	return false
}

// incidentVisibility checks the three read paths (detail, active list,
// heartbeat pending alerts) agree, and returns whether a sees the incident.
// Pending alerts exclude acknowledged and own incidents, so callers only use
// this for unacknowledged incidents of others.
func (a *actor) incidentVisibility(t testing.TB, id string) bool {
	t.Helper()
	r := a.do(t, http.MethodGet, "/v1/incidents/"+id, nil)
	var detail bool
	switch r.status {
	case http.StatusOK:
		detail = true
	case http.StatusNotFound:
		wantErr(t, r, http.StatusNotFound, "not_found")
	default:
		t.Fatalf("unexpected %s", r)
	}
	var active []summaryJSON
	a.must(t, http.MethodGet, "/v1/incidents/active", nil, http.StatusOK).decode(t, &active)
	hb := a.heartbeat(t, 51.5, -0.12)
	if containsIncident(active, id) != detail || containsIncident(hb.PendingAlerts, id) != detail {
		t.Fatalf("%s: inconsistent visibility of %s: detail=%v active=%v pending=%v", a.role, id, detail,
			containsIncident(active, id), containsIncident(hb.PendingAlerts, id))
	}
	return detail
}

func (a *actor) expectIncident(t testing.TB, id string, visible bool) {
	t.Helper()
	if got := a.incidentVisibility(t, id); got != visible {
		t.Fatalf("%s sees incident=%v, want %v", a.role, got, visible)
	}
}

// waitFor polls cond until it is true or the timeout elapses.
func waitFor(t testing.TB, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// ---------------------------------------------------------------------------
// psql (optional, E2E_PSQL)

// runPSQL feeds script to the configured superuser psql command.
func runPSQL(t testing.TB, script string) (string, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "sh", "-c", cfg.psql)
	cmd.Stdin = strings.NewReader(script)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func mustPSQL(t testing.TB, script string) string {
	t.Helper()
	out, err := runPSQL(t, "\\set ON_ERROR_STOP 1\n"+script)
	if err != nil {
		t.Fatalf("psql failed: %v\n%s", err, out)
	}
	return out
}
