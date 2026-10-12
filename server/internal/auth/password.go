// Package auth implements password hashing (argon2id), access tokens (JWT),
// opaque refresh/share tokens and the bearer-token middleware.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"runtime"
	"strings"

	"golang.org/x/crypto/argon2"
)

// Argon2Params are argon2id cost parameters.
type Argon2Params struct {
	MemoryKiB uint32
	Time      uint32
	Threads   uint8
	SaltLen   uint32
	KeyLen    uint32
}

// DefaultArgon2 is the OWASP "m=19 MiB, t=2, p=1" profile (~114 ms on the 2-vCPU target VM).
var DefaultArgon2 = Argon2Params{MemoryKiB: 19456, Time: 2, Threads: 1, SaltLen: 16, KeyLen: 32}

// Upper bounds accepted when parsing a stored hash, so a tampered row cannot
// make Verify allocate gigabytes or spin for minutes.
const (
	maxMemoryKiB = 256 * 1024
	maxTime      = 10
	maxThreads   = 16
)

var (
	// ErrMalformedHash means the stored hash is not a PHC argon2id string we accept.
	ErrMalformedHash = errors.New("auth: malformed password hash")
	b64              = base64.RawStdEncoding
)

// Hasher hashes and verifies passwords. Each computation allocates MemoryKiB,
// so concurrency is capped with a semaphore to bound memory in a small container.
//
// The cap alone is not enough: every computation also leaves a MemoryKiB
// buffer of garbage, and the GC pacer lets those pile up until the heap goal
// (GOGC / GOMEMLIMIT) is reached. Under a burst of logins that is several
// dead 19 MiB buffers on top of the live ones, which OOM-killed the API in its
// 128 MiB container (e2e: ~14 concurrent sign-ups). Each computation therefore
// ends with a collection while it still holds its slot, so the next one reuses
// the freed memory: the hashing peak stays at about maxConcurrent buffers.
// A cycle over this mostly pointer-free heap costs ~1 ms next to ~100 ms of
// hashing, and only login, sign-up and dummy verification pay it.
type Hasher struct {
	params Argon2Params
	sem    chan struct{}
}

// NewHasher returns a Hasher allowing at most maxConcurrent simultaneous computations.
func NewHasher(p Argon2Params, maxConcurrent int) *Hasher {
	if maxConcurrent < 1 {
		maxConcurrent = 1
	}
	return &Hasher{params: p, sem: make(chan struct{}, maxConcurrent)}
}

func (h *Hasher) acquire(ctx context.Context) error {
	select {
	case h.sem <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (h *Hasher) release() { <-h.sem }

// Hash returns a PHC string: $argon2id$v=19$m=..,t=..,p=..$<salt>$<key>.
func (h *Hasher) Hash(ctx context.Context, password string) (string, error) {
	salt := make([]byte, h.params.SaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	if err := h.acquire(ctx); err != nil {
		return "", err
	}
	defer h.release()
	p := h.params
	key := idKey(password, salt, p, p.KeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, p.MemoryKiB, p.Time, p.Threads, b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

// Verify checks password against encoded. needsRehash is true when the stored
// parameters are weaker than the current ones.
func (h *Hasher) Verify(ctx context.Context, password, encoded string) (ok, needsRehash bool, err error) {
	p, salt, key, err := decodeHash(encoded)
	if err != nil {
		return false, false, err
	}
	if err := h.acquire(ctx); err != nil {
		return false, false, err
	}
	defer h.release()
	got := idKey(password, salt, p, uint32(len(key)))
	if subtle.ConstantTimeCompare(got, key) != 1 {
		return false, false, nil
	}
	cur := h.params
	needsRehash = p.MemoryKiB < cur.MemoryKiB || p.Time < cur.Time || p.Threads < cur.Threads ||
		uint32(len(key)) < cur.KeyLen || uint32(len(salt)) < cur.SaltLen
	return true, needsRehash, nil
}

// dummySalt feeds DummyVerify; its value is irrelevant, only the cost matters.
var dummySalt = make([]byte, 16)

// DummyVerify burns the same CPU/memory as a real verification. Call it when
// the account does not exist so response timing does not reveal registration.
func (h *Hasher) DummyVerify(ctx context.Context, password string) {
	if err := h.acquire(ctx); err != nil {
		return
	}
	defer h.release()
	p := h.params
	_ = idKey(password, dummySalt, p, p.KeyLen)
}

// idKey runs argon2id and then collects its scratch memory (see Hasher). The
// caller must hold a semaphore slot.
func idKey(password string, salt []byte, p Argon2Params, keyLen uint32) []byte {
	key := argon2.IDKey([]byte(password), salt, p.Time, p.MemoryKiB, p.Threads, keyLen)
	runtime.GC()
	return key
}

func decodeHash(encoded string) (Argon2Params, []byte, []byte, error) {
	var p Argon2Params
	parts := strings.Split(encoded, "$")
	// "", "argon2id", "v=19", "m=..,t=..,p=..", salt, key
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" {
		return p, nil, nil, ErrMalformedHash
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return p, nil, nil, ErrMalformedHash
	}
	var threads uint32
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &p.MemoryKiB, &p.Time, &threads); err != nil {
		return p, nil, nil, ErrMalformedHash
	}
	if p.MemoryKiB < 8 || p.MemoryKiB > maxMemoryKiB || p.Time < 1 || p.Time > maxTime || threads < 1 || threads > maxThreads {
		return p, nil, nil, ErrMalformedHash
	}
	p.Threads = uint8(threads)
	salt, err := b64.DecodeString(parts[4])
	if err != nil || len(salt) < 8 || len(salt) > 64 {
		return p, nil, nil, ErrMalformedHash
	}
	key, err := b64.DecodeString(parts[5])
	if err != nil || len(key) < 16 || len(key) > 128 {
		return p, nil, nil, ErrMalformedHash
	}
	p.SaltLen, p.KeyLen = uint32(len(salt)), uint32(len(key))
	return p, salt, key, nil
}
