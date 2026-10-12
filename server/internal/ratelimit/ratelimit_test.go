package ratelimit

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time          { return c.t }
func (c *clock) advance(d time.Duration) { c.t = c.t.Add(d) }

func TestAllowBurstThenRefill(t *testing.T) {
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	l := New(6, 3, time.Minute, 100) // 1 token / 10 s, burst 3
	l.now = c.now
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("1.2.3.4"); !ok {
			t.Fatalf("burst request %d denied", i)
		}
	}
	ok, retry := l.Allow("1.2.3.4")
	if ok || retry <= 0 || retry > 10*time.Second {
		t.Fatalf("4th request: ok=%v retry=%v", ok, retry)
	}
	if ok, _ := l.Allow("5.6.7.8"); !ok {
		t.Fatal("other key affected")
	}
	c.advance(10 * time.Second)
	if ok, _ := l.Allow("1.2.3.4"); !ok {
		t.Fatal("token not refilled")
	}
}

func TestDeniedRequestsDoNotConsume(t *testing.T) {
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	l := New(60, 1, time.Minute, 10) // 1/s
	l.now = c.now
	l.Allow("k")
	for i := 0; i < 5; i++ {
		l.Allow("k") // denied; must not push the next token further out
	}
	c.advance(time.Second)
	if ok, _ := l.Allow("k"); !ok {
		t.Fatal("denied requests consumed future tokens")
	}
}

func TestEvictionAndCap(t *testing.T) {
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	l := New(60, 1, time.Minute, 2)
	l.now = c.now
	l.Allow("a")
	l.Allow("b")
	if ok, _ := l.Allow("c"); ok {
		t.Fatal("cap exceeded while all keys active")
	}
	c.advance(2 * time.Minute)
	if ok, _ := l.Allow("c"); !ok {
		t.Fatal("idle keys not evicted")
	}
	if n := l.Len(); n != 1 {
		t.Fatalf("expected 1 tracked key, got %d", n)
	}
}

func TestMiddleware(t *testing.T) {
	l := New(1, 2, time.Minute, 100)
	h := l.Middleware(func(r *http.Request) string { return r.Header.Get("X-Key") },
		func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusTooManyRequests) })(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) }))
	do := func(key string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/", nil)
		req.Header.Set("X-Key", key)
		h.ServeHTTP(rec, req)
		return rec
	}
	if do("a").Code != 200 || do("a").Code != 200 {
		t.Fatal("burst denied")
	}
	rec := do("a")
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("want 429 with Retry-After, got %d %q", rec.Code, rec.Header().Get("Retry-After"))
	}
	if do("b").Code != 200 {
		t.Fatal("other key limited")
	}
}
