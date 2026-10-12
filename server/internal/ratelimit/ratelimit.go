// Package ratelimit provides keyed token-bucket limiters (per client IP or per
// account) with bounded memory and idle eviction.
package ratelimit

import (
	"context"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// Limiter is a set of token buckets keyed by an arbitrary string.
type Limiter struct {
	mu      sync.Mutex
	limit   rate.Limit
	burst   int
	ttl     time.Duration // idle buckets older than this are evicted
	max     int           // hard cap on tracked keys
	buckets map[string]*bucket
	now     func() time.Time
}

type bucket struct {
	lim  *rate.Limiter
	seen time.Time
}

// New returns a limiter allowing perMinute events per key with the given burst.
func New(perMinute float64, burst int, ttl time.Duration, maxKeys int) *Limiter {
	return &Limiter{
		limit:   rate.Limit(perMinute / 60),
		burst:   burst,
		ttl:     ttl,
		max:     maxKeys,
		buckets: make(map[string]*bucket),
		now:     time.Now,
	}
}

// Allow consumes one token for key. When the table is full of active keys new
// keys are denied (fail closed) rather than growing memory without bound.
func (l *Limiter) Allow(key string) (ok bool, retryAfter time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, found := l.buckets[key]
	if !found {
		if len(l.buckets) >= l.max {
			l.evictLocked(now)
			if len(l.buckets) >= l.max {
				return false, l.ttl
			}
		}
		b = &bucket{lim: rate.NewLimiter(l.limit, l.burst)}
		l.buckets[key] = b
	}
	b.seen = now
	r := b.lim.ReserveN(now, 1)
	if !r.OK() {
		return false, time.Minute
	}
	if d := r.DelayFrom(now); d > 0 {
		r.CancelAt(now)
		return false, d
	}
	return true, 0
}

// Len reports the number of tracked keys.
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

func (l *Limiter) evictLocked(now time.Time) {
	for k, b := range l.buckets {
		if now.Sub(b.seen) > l.ttl {
			delete(l.buckets, k)
		}
	}
}

// Janitor evicts idle buckets every interval until ctx is done.
func (l *Limiter) Janitor(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			l.mu.Lock()
			l.evictLocked(l.now())
			l.mu.Unlock()
		}
	}
}

// Middleware limits requests by key(r). An empty key is limited under a shared
// "unknown" bucket. On rejection it sets Retry-After and calls reject.
func (l *Limiter) Middleware(key func(*http.Request) string, reject http.HandlerFunc) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			k := key(r)
			if k == "" {
				k = "unknown"
			}
			if ok, retry := l.Allow(k); !ok {
				SetRetryAfter(w, retry)
				reject(w, r)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// SetRetryAfter writes a Retry-After header in whole seconds (at least 1).
func SetRetryAfter(w http.ResponseWriter, d time.Duration) {
	secs := int(math.Ceil(d.Seconds()))
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
}
