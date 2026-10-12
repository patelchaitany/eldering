package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestNormalizeEmail(t *testing.T) {
	ok := map[string]string{
		"Alice@Example.COM":       "alice@example.com",
		"  bob.smith@mail.co.uk ": "bob.smith@mail.co.uk",
	}
	for in, want := range ok {
		got, err := normalizeEmail(in)
		if err != nil || got != want {
			t.Errorf("normalizeEmail(%q) = %q, %v", in, got, err)
		}
	}
	for _, bad := range []string{"", "nope", "a@b", "Alice <a@example.com>", "a@@example.com", strings.Repeat("a", 250) + "@x.com"} {
		if _, err := normalizeEmail(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestCleanName(t *testing.T) {
	if got, err := cleanName("  Grandma Rosa ", "display_name"); err != nil || got != "Grandma Rosa" {
		t.Fatalf("got %q %v", got, err)
	}
	for _, bad := range []string{"", "   ", strings.Repeat("x", 81), "tab\tname", "bell\a"} {
		if _, err := cleanName(bad, "name"); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
	if _, err := cleanName(strings.Repeat("é", 80), "name"); err != nil {
		t.Errorf("80 runes rejected: %v", err)
	}
}

func TestInviteCodes(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		c, err := newInviteCode()
		if err != nil {
			t.Fatal(err)
		}
		if len(c) != 8 || strings.ContainsAny(c, "ILOU") {
			t.Fatalf("bad code %q", c)
		}
		if _, ok := normalizeInviteCode(c); !ok {
			t.Fatalf("own code rejected: %q", c)
		}
		seen[c] = true
	}
	if len(seen) < 199 {
		t.Fatal("codes repeat")
	}
	cases := map[string]string{"abcd-efgh": "ABCDEFGH", "0o1i l2zz": "001112ZZ", " 7K3M 9PQR ": "7K3M9PQR"}
	for in, want := range cases {
		if got, ok := normalizeInviteCode(in); !ok || got != want {
			t.Errorf("normalizeInviteCode(%q) = %q %v, want %q", in, got, ok, want)
		}
	}
	for _, bad := range []string{"", "ABC", "ABCDEFGHJ", "ABCD!FGH", "ABCDUFGH"} {
		if _, ok := normalizeInviteCode(bad); ok {
			t.Errorf("accepted %q", bad)
		}
	}
}

func f64(v float64) *float64 { return &v }
func str(s string) *string   { return &s }

func TestHeartbeatValidation(t *testing.T) {
	now := time.Now()
	at := now.Add(-time.Second)
	h, err := heartbeatRequest{Lat: f64(19.07), Lng: f64(72.87), BearingDeg: f64(360), Activity: str(" Vehicle "),
		BatteryPct: f64(49.6), RecordedAt: &at}.validate(now)
	if err != nil {
		t.Fatal(err)
	}
	if *h.bearing != 0 || *h.activity != "vehicle" || *h.battery != 50 || h.accuracy != nil {
		t.Fatalf("normalisation: %+v", h)
	}
	future := now.Add(10 * time.Minute)
	old := now.Add(-8 * 24 * time.Hour)
	for name, req := range map[string]heartbeatRequest{
		"no lat":    {Lng: f64(1), RecordedAt: &at},
		"lat range": {Lat: f64(-90.1), Lng: f64(1), RecordedAt: &at},
		"no time":   {Lat: f64(1), Lng: f64(1)},
		"future":    {Lat: f64(1), Lng: f64(1), RecordedAt: &future},
		"too old":   {Lat: f64(1), Lng: f64(1), RecordedAt: &old},
		"speed":     {Lat: f64(1), Lng: f64(1), RecordedAt: &at, SpeedMps: f64(-1)},
		"accuracy":  {Lat: f64(1), Lng: f64(1), RecordedAt: &at, AccuracyM: f64(1e9)},
		"bearing":   {Lat: f64(1), Lng: f64(1), RecordedAt: &at, BearingDeg: f64(361)},
		"activity":  {Lat: f64(1), Lng: f64(1), RecordedAt: &at, Activity: str("flying")},
	} {
		if _, err := req.validate(now); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestParseTrailAndDetails(t *testing.T) {
	now := time.Now()
	at := now.Add(-time.Minute)
	c, err := parseTrail([]trailPointRequest{
		{Lat: f64(1), Lng: f64(2), RecordedAt: &at, Landmark: str("  Main St \n")},
		{Lat: f64(1.1), Lng: f64(2.1), RecordedAt: &at, Landmark: str("   ")},
	}, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.at) != 2 || *c.landmark[0] != "Main St" || c.landmark[1] != nil {
		t.Fatalf("trail: %+v", c)
	}
	if _, err := parseTrail(make([]trailPointRequest, maxTrailPoints+1), now); err == nil {
		t.Fatal("oversized trail accepted")
	}
	if _, err := parseTrail([]trailPointRequest{{Lat: f64(1), Lng: f64(2), RecordedAt: &at, Landmark: str(strings.Repeat("x", 201))}}, now); err == nil {
		t.Fatal("long landmark accepted")
	}

	for in, want := range map[string]string{"": "{}", "null": "{}", ` {"a":1} `: `{"a":1}`} {
		got, err := normalizeDetails(json.RawMessage(in))
		if err != nil || string(got) != want {
			t.Errorf("normalizeDetails(%q) = %q %v", in, got, err)
		}
	}
	for _, bad := range []string{`[1]`, `"x"`, `{"a":`, `{"a":"` + strings.Repeat("x", maxDetailsBytes) + `"}`} {
		if _, err := normalizeDetails(json.RawMessage(bad)); err == nil {
			t.Errorf("accepted details %.20q", bad)
		}
	}
}

func TestOptional(t *testing.T) {
	var p settingsPatch
	if err := json.Unmarshal([]byte(`{"paused_until":null,"sharing_state":"paused"}`), &p); err != nil {
		t.Fatal(err)
	}
	if !p.PausedUntil.Set || !p.PausedUntil.Null || !p.SharingState.Set || p.SharingState.Value != "paused" || p.AlwaysAlertOnAccident.Set {
		t.Fatalf("optional decode: %+v", p)
	}
}

func TestDecodeJSON(t *testing.T) {
	type body struct{ A int }
	cases := map[string]bool{
		`{"A":1}`:           true,
		`{"A":1,"extra":2}`: true, // unknown fields tolerated on purpose
		`{"A":1} {"A":2}`:   false,
		`{"A":`:             false,
		``:                  false,
	}
	for in, ok := range cases {
		r := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(in))
		var b body
		err := decodeJSON(r, &b)
		if (err == nil) != ok {
			t.Errorf("decodeJSON(%q) err=%v", in, err)
		}
		var ae *apiError
		if err != nil && !errors.As(err, &ae) {
			t.Errorf("decodeJSON(%q) returned non-API error %v", in, err)
		}
	}
	rec := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"A":"`+strings.Repeat("x", 100)+`"}`))
	r.Body = http.MaxBytesReader(rec, r.Body, 10)
	var mbe *http.MaxBytesError
	if err := decodeJSON(r, &struct{ A string }{}); !errors.As(err, &mbe) {
		t.Fatalf("want MaxBytesError, got %v", err)
	}
}

func TestIPRateKey(t *testing.T) {
	cases := map[string]string{
		"203.0.113.7":                   "203.0.113.7",
		"::ffff:203.0.113.7":            "203.0.113.7", // v4-mapped folds to v4
		"2001:db8:1:2:aaaa::1":          "2001:db8:1:2::/64",
		"2001:db8:1:2:ffff:ffff:ffff:1": "2001:db8:1:2::/64", // same /64, same bucket
		"2001:db8:1:3::1":               "2001:db8:1:3::/64",
		"fe80::1%eth0":                  "fe80::/64",
		"not-an-ip":                     "not-an-ip",
	}
	for in, want := range cases {
		if got := ipRateKey(in); got != want {
			t.Errorf("ipRateKey(%q) = %q, want %q", in, got, want)
		}
	}
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "[2001:db8:9:9::5]:4242"
	if got := clientIP(r); got != "2001:db8:9:9::/64" {
		t.Errorf("clientIP from RemoteAddr = %q", got)
	}
}
