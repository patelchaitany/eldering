package sharepage

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestHandler(t *testing.T) *Handler {
	t.Helper()
	h, err := New(nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func render(t *testing.T, m pageModel) string {
	t.Helper()
	var b bytes.Buffer
	if err := newTestHandler(t).tmpl.Execute(&b, m); err != nil {
		t.Fatal(err)
	}
	return b.String()
}

func TestRenderActive(t *testing.T) {
	acc := float32(7.4)
	lm := `Joe's "Diner" <b>`
	at := time.Date(2026, 10, 12, 18, 30, 0, 0, time.UTC)
	d := &Data{
		Kind: "pedestrian_impact", Status: "active", DetectedAt: at.Add(-3 * time.Minute), ShareExpiresAt: at.Add(72 * time.Hour),
		Trail:      []Point{{Lat: 51.5, Lng: -0.12, RecordedAt: at.Add(-time.Minute), Landmark: &lm}, {Lat: 51.501, Lng: -0.121, RecordedAt: at, AccuracyM: &acc}},
		Latest:     &Point{Lat: 51.501, Lng: -0.121, RecordedAt: at, AccuracyM: &acc},
		ServerTime: at.Add(40 * time.Second),
	}
	html := render(t, pageModel{View: newPageView(d), Status: 200, MapLibre: "/static/maplibre-gl/" + MapLibreVersion})
	for _, want := range []string{
		"Possible pedestrian accident", "51.501000, -0.121000", "±7 m", "2026-10-12 18:30 UTC",
		`datetime="2026-10-12T18:30:00Z"`, "Joe&#39;s &#34;Diner&#34; &lt;b&gt;", `href="geo:51.501000,-0.121000"`,
		`id="map"`, `data-active="true"`, "/static/maplibre-gl/6.11.2/maplibre-gl.css",
		`<span class="muted" id="latest-ago">(40 s ago)</span>`, `<span class="muted" id="detected-ago">(4 min ago)</span>`,
	} {
		if !strings.Contains(html, want) {
			t.Errorf("missing %q", want)
		}
	}
	if strings.Contains(html, "<script>") || strings.Contains(html, " style=") || strings.Contains(html, "ZgotmplZ") {
		t.Fatal("inline script/style or unsafe URL in page")
	}
}

func TestRenderResolvedAndMissing(t *testing.T) {
	res := "false_alarm"
	now := time.Now()
	html := render(t, pageModel{View: newPageView(&Data{Kind: "fall", Status: "cancelled", Resolution: &res, DetectedAt: now, ShareExpiresAt: now}), Status: 200})
	if !strings.Contains(html, "cancelled as a false alarm") || strings.Contains(html, `id="map"`) || strings.Contains(html, "maplibre-gl.css") ||
		strings.Contains(html, "ago") {
		t.Fatalf("resolved page:\n%s", html)
	}
	html = render(t, pageModel{Status: 404})
	if !strings.Contains(html, "invalid or has expired") || strings.Contains(html, "share.js") {
		t.Fatalf("404 page:\n%s", html)
	}
}

func TestStaticAssets(t *testing.T) {
	h := newTestHandler(t)
	for _, p := range []string{"share.js", "share.css", "maplibre-gl/" + MapLibreVersion + "/maplibre-gl.mjs",
		"maplibre-gl/" + MapLibreVersion + "/maplibre-gl-shared.mjs", "maplibre-gl/" + MapLibreVersion + "/maplibre-gl-worker.mjs",
		"maplibre-gl/" + MapLibreVersion + "/maplibre-gl.css", "maplibre-gl/" + MapLibreVersion + "/LICENSE.txt"} {
		if _, ok := h.static[p]; !ok {
			t.Errorf("asset %s not embedded", p)
		}
	}
	if !bytes.Contains(h.static["share.js"].body, []byte("./maplibre-gl/"+MapLibreVersion+"/maplibre-gl.mjs")) {
		t.Fatal("share.js imports a different MapLibre version than MapLibreVersion")
	}
	rec := httptest.NewRecorder()
	h.Static().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/static/share.js", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST static: %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.Static().ServeHTTP(rec, httptest.NewRequest(http.MethodHead, "/static/share.css", nil))
	if rec.Code != http.StatusOK || rec.Body.Len() != 0 || rec.Header().Get("Content-Type") != "text/css; charset=utf-8" {
		t.Fatalf("HEAD static: %d %d %v", rec.Code, rec.Body.Len(), rec.Header())
	}
}

func TestOSMURL(t *testing.T) {
	if got := osmURL(-33.8688, 151.2093); got != "https://www.openstreetmap.org/?mlat=-33.868800&mlon=151.209300#map=17/-33.868800/151.209300" {
		t.Fatal(got)
	}
}

func TestAgoLabel(t *testing.T) {
	now := time.Date(2026, 10, 13, 23, 29, 0, 0, time.UTC)
	for _, c := range []struct {
		d    time.Duration
		want string
	}{
		{0, "(0 s ago)"}, {-5 * time.Second, "(0 s ago)"}, {89 * time.Second, "(89 s ago)"},
		{90 * time.Second, "(2 min ago)"}, {89 * time.Minute, "(89 min ago)"}, {90 * time.Minute, "(2 h ago)"},
		{50 * time.Hour, "(50 h ago)"},
	} {
		if got := agoLabel(now.Add(-c.d), now); got != c.want {
			t.Errorf("agoLabel(-%v) = %q, want %q", c.d, got, c.want)
		}
	}
	if agoLabel(now, time.Time{}) != "" || agoLabel(time.Time{}, now) != "" {
		t.Error("zero times must give no label")
	}
}

func TestIncidentAccuracy(t *testing.T) {
	at := time.Date(2026, 10, 13, 23, 29, 0, 0, time.UTC)
	f := func(v float32) *float32 { return &v }
	loc := &Point{Lat: 19.076, Lng: 72.8777, RecordedAt: at}
	trail := []Point{
		{Lat: 19.07, Lng: 72.87, RecordedAt: at.Add(-10 * time.Minute), AccuracyM: f(9)},
		{Lat: 19.076, Lng: 72.8777, RecordedAt: at.Add(-5 * time.Minute), AccuracyM: f(30)},
		{Lat: 19.076, Lng: 72.8777, RecordedAt: at.Add(-10 * time.Second), AccuracyM: f(6)}, // closest to detection
		{Lat: 19.076, Lng: 72.8777, RecordedAt: at.Add(-time.Second)},                       // closer, but no accuracy
		{Lat: 19.0761, Lng: 72.8777, RecordedAt: at, AccuracyM: f(3)},                       // other coordinates
	}
	if got := incidentAccuracy(loc, trail); got == nil || *got != 6 {
		t.Fatalf("accuracy = %v, want 6", got)
	}
	if got := incidentAccuracy(loc, trail[:1]); got != nil {
		t.Fatalf("no matching trail point: accuracy = %v, want nil", *got)
	}
	if got := incidentAccuracy(loc, nil); got != nil {
		t.Fatalf("no trail: accuracy = %v, want nil", *got)
	}
}
