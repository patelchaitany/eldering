package auth

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// cheap params keep unit tests fast; production uses DefaultArgon2.
var testParams = Argon2Params{MemoryKiB: 1024, Time: 1, Threads: 1, SaltLen: 16, KeyLen: 32}

// Each argon2 computation must not leave its scratch buffer behind as garbage:
// under a burst of logins those buffers piled up until the API was OOM-killed.
func TestHasherReleasesScratchMemory(t *testing.T) {
	p := Argon2Params{MemoryKiB: 32 * 1024, Time: 1, Threads: 1, SaltLen: 16, KeyLen: 32}
	h := NewHasher(p, 1)
	ctx := context.Background()
	enc, err := h.Hash(ctx, "warm up the heap")
	if err != nil {
		t.Fatal(err)
	}
	heapAlloc := func() uint64 {
		var m runtime.MemStats
		runtime.ReadMemStats(&m)
		return m.HeapAlloc
	}
	limit := uint64(p.MemoryKiB) * 1024 / 2
	for name, run := range map[string]func(){
		"hash":         func() { _, _ = h.Hash(ctx, "pw") },
		"verify":       func() { _, _, _ = h.Verify(ctx, "pw", enc) },
		"dummy verify": func() { h.DummyVerify(ctx, "pw") },
	} {
		runtime.GC()
		before := heapAlloc()
		run()
		if after := heapAlloc(); after > before+limit {
			t.Errorf("%s left %d MiB on the heap (scratch buffer not collected)", name, (after-before)>>20)
		}
	}
}

func TestPasswordRoundTrip(t *testing.T) {
	h := NewHasher(testParams, 2)
	ctx := context.Background()
	enc, err := h.Hash(ctx, "correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(enc, "$argon2id$v=19$m=1024,t=1,p=1$") {
		t.Fatalf("unexpected encoding %q", enc)
	}
	ok, rehash, err := h.Verify(ctx, "correct horse battery staple", enc)
	if err != nil || !ok || rehash {
		t.Fatalf("verify: ok=%v rehash=%v err=%v", ok, rehash, err)
	}
	ok, _, err = h.Verify(ctx, "wrong", enc)
	if err != nil || ok {
		t.Fatalf("wrong password accepted: ok=%v err=%v", ok, err)
	}
	enc2, _ := h.Hash(ctx, "correct horse battery staple")
	if enc == enc2 {
		t.Fatal("salt not random")
	}
}

func TestPasswordNeedsRehash(t *testing.T) {
	ctx := context.Background()
	weak := NewHasher(testParams, 1)
	enc, err := weak.Hash(ctx, "pw-pw-pw-pw")
	if err != nil {
		t.Fatal(err)
	}
	stronger := testParams
	stronger.Time = 2
	ok, rehash, err := NewHasher(stronger, 1).Verify(ctx, "pw-pw-pw-pw", enc)
	if err != nil || !ok || !rehash {
		t.Fatalf("want ok+rehash, got ok=%v rehash=%v err=%v", ok, rehash, err)
	}
}

func TestPasswordMalformed(t *testing.T) {
	h := NewHasher(testParams, 1)
	salt := base64.RawStdEncoding.EncodeToString(make([]byte, 16))
	key := base64.RawStdEncoding.EncodeToString(make([]byte, 32))
	for _, enc := range []string{
		"",
		"plain",
		"$argon2i$v=19$m=1024,t=1,p=1$" + salt + "$" + key,
		"$argon2id$v=18$m=1024,t=1,p=1$" + salt + "$" + key,
		"$argon2id$v=19$m=999999999,t=1,p=1$" + salt + "$" + key, // absurd memory
		"$argon2id$v=19$m=1024,t=99,p=1$" + salt + "$" + key,     // absurd time
		"$argon2id$v=19$m=1024,t=1,p=0$" + salt + "$" + key,
		"$argon2id$v=19$m=1024,t=1,p=1$!!!$" + key,
		"$argon2id$v=19$m=1024,t=1,p=1$" + salt + "$short",
	} {
		if ok, _, err := h.Verify(context.Background(), "x", enc); err == nil || ok {
			t.Errorf("accepted malformed hash %q", enc)
		}
	}
}

func TestHasherRespectsContext(t *testing.T) {
	h := NewHasher(testParams, 1)
	h.sem <- struct{}{} // saturate
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := h.Hash(ctx, "x"); err == nil {
		t.Fatal("expected context error while semaphore is full")
	}
	<-h.sem
	h.DummyVerify(context.Background(), "x") // must not deadlock
}

func newTokens(t *testing.T) *Tokens {
	t.Helper()
	tk, err := NewTokens([]byte(strings.Repeat("k", 32)), 15*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func TestJWTRoundTrip(t *testing.T) {
	tk := newTokens(t)
	uid := uuid.New()
	s, exp, err := tk.Issue(uid)
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Until(exp); d < 14*time.Minute || d > 15*time.Minute+time.Second {
		t.Fatalf("unexpected expiry in %v", d)
	}
	got, err := tk.Parse(s)
	if err != nil || got != uid {
		t.Fatalf("parse: %v %v", got, err)
	}
}

func TestJWTRejections(t *testing.T) {
	tk := newTokens(t)
	uid := uuid.New()
	sign := func(m jwt.SigningMethod, key any, c jwt.RegisteredClaims) string {
		s, err := jwt.NewWithClaims(m, c).SignedString(key)
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	now := time.Now()
	good := jwt.RegisteredClaims{Issuer: issuer, Audience: jwt.ClaimStrings{audience}, Subject: uid.String(),
		IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(time.Minute))}
	wrongAud, noExp, expired, badSub := good, good, good, good
	wrongAud.Audience = jwt.ClaimStrings{"other"}
	noExp.ExpiresAt = nil
	expired.ExpiresAt = jwt.NewNumericDate(now.Add(-time.Hour))
	badSub.Subject = "not-a-uuid"

	cases := map[string]string{
		"alg none":     sign(jwt.SigningMethodNone, jwt.UnsafeAllowNoneSignatureType, good),
		"wrong secret": sign(jwt.SigningMethodHS256, []byte(strings.Repeat("x", 32)), good),
		"HS512":        sign(jwt.SigningMethodHS512, []byte(strings.Repeat("k", 32)), good),
		"wrong aud":    sign(jwt.SigningMethodHS256, []byte(strings.Repeat("k", 32)), wrongAud),
		"no exp":       sign(jwt.SigningMethodHS256, []byte(strings.Repeat("k", 32)), noExp),
		"expired":      sign(jwt.SigningMethodHS256, []byte(strings.Repeat("k", 32)), expired),
		"bad subject":  sign(jwt.SigningMethodHS256, []byte(strings.Repeat("k", 32)), badSub),
		"garbage":      "a.b.c",
	}
	for name, s := range cases {
		if _, err := tk.Parse(s); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	if _, err := NewTokens([]byte("short"), time.Minute); err == nil {
		t.Error("short secret accepted")
	}
}

func TestOpaqueToken(t *testing.T) {
	tok, h, err := NewOpaqueToken()
	if err != nil {
		t.Fatal(err)
	}
	if len(tok) != OpaqueTokenLen || !LooksLikeOpaqueToken(tok) || len(h) != 32 {
		t.Fatalf("bad token %q (%d) hash %d", tok, len(tok), len(h))
	}
	if string(HashToken(tok)) != string(h) {
		t.Fatal("hash mismatch")
	}
	tok2, _, _ := NewOpaqueToken()
	if tok == tok2 {
		t.Fatal("tokens repeat")
	}
	for _, bad := range []string{"", "short", strings.Repeat("a", 42) + "=", strings.Repeat("a", 42) + "/", strings.Repeat("a", 44)} {
		if LooksLikeOpaqueToken(bad) {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestMiddleware(t *testing.T) {
	tk := newTokens(t)
	uid := uuid.New()
	s, _, _ := tk.Issue(uid)
	var seen uuid.UUID
	h := tk.Middleware(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusUnauthorized) })(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			seen, _ = UserID(r.Context())
			w.WriteHeader(http.StatusNoContent)
		}))
	for hdr, want := range map[string]int{
		"":                 http.StatusUnauthorized,
		"Bearer":           http.StatusUnauthorized,
		"Basic " + s:       http.StatusUnauthorized,
		"Bearer garbage":   http.StatusUnauthorized,
		"Bearer " + s:      http.StatusNoContent,
		"bearer " + s + "": http.StatusNoContent,
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		if hdr != "" {
			req.Header.Set("Authorization", hdr)
		}
		h.ServeHTTP(rec, req)
		if rec.Code != want {
			t.Errorf("header %q: got %d want %d", hdr, rec.Code, want)
		}
	}
	if seen != uid {
		t.Fatalf("user id not propagated: %v", seen)
	}
}
