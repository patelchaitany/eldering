// Package handlers implements the Eldering HTTP API (SPEC "HTTP API").
package handlers

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"runtime/debug"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/config"
	"github.com/eldering/safety/server/internal/ratelimit"
	"github.com/eldering/safety/server/internal/sharepage"
)

// Deps are the collaborators of the API server.
type Deps struct {
	Config config.Config
	Pool   *pgxpool.Pool
	Logger *slog.Logger
	// Hasher is optional; tests inject cheaper argon2 parameters.
	Hasher *auth.Hasher
}

// Server holds the API state shared by all handlers.
type Server struct {
	cfg    config.Config
	pool   *pgxpool.Pool
	log    *slog.Logger
	hasher *auth.Hasher
	tokens *auth.Tokens
	share  *sharepage.Handler

	authLimit      *ratelimit.Limiter // per IP: register, login, join
	tokenLimit     *ratelimit.Limiter // per IP: refresh, logout
	loginAcctLimit *ratelimit.Limiter // per account: login
	shareLimit     *ratelimit.Limiter // per IP: public share page + data
}

// New builds a Server.
func New(d Deps) (*Server, error) {
	if d.Pool == nil {
		return nil, errors.New("handlers: nil pool")
	}
	if d.Logger == nil {
		d.Logger = slog.Default()
	}
	tokens, err := auth.NewTokens(d.Config.JWTSecret, d.Config.AccessTTL)
	if err != nil {
		return nil, err
	}
	hasher := d.Hasher
	if hasher == nil {
		hasher = auth.NewHasher(auth.DefaultArgon2, d.Config.MaxHashJobs)
	}
	share, err := sharepage.New(d.Pool, d.Logger)
	if err != nil {
		return nil, err
	}
	const ttl, maxKeys = 15 * time.Minute, 10_000
	c := d.Config
	return &Server{
		cfg:            c,
		pool:           d.Pool,
		log:            d.Logger,
		hasher:         hasher,
		tokens:         tokens,
		share:          share,
		authLimit:      ratelimit.New(c.AuthPerMinute, c.AuthBurst, ttl, maxKeys),
		tokenLimit:     ratelimit.New(c.TokenPerMinute, c.TokenBurst, ttl, maxKeys),
		loginAcctLimit: ratelimit.New(c.LoginPerAccount, c.LoginAcctBurst, time.Hour, maxKeys),
		shareLimit:     ratelimit.New(c.SharePerMinute, c.ShareBurst, ttl, maxKeys),
	}, nil
}

// RunJanitors evicts idle rate-limit buckets until ctx is done.
func (s *Server) RunJanitors(ctx context.Context) {
	for _, l := range []*ratelimit.Limiter{s.authLimit, s.tokenLimit, s.loginAcctLimit, s.shareLimit} {
		go l.Janitor(ctx, time.Minute)
	}
}

// Handler returns the root HTTP handler.
func (s *Server) Handler() http.Handler {
	r := chi.NewRouter()
	if s.cfg.ClientIPHeader != "" {
		// Safe only because nginx overwrites this header and the API port is
		// published on 127.0.0.1 only (chi's RealIP is deprecated: spoofable).
		r.Use(middleware.ClientIPFromHeader(s.cfg.ClientIPHeader))
	}
	r.Use(middleware.RequestID)
	r.Use(s.logRequests)
	r.Use(s.recoverPanics)
	r.Use(middleware.RequestSize(s.cfg.BodyLimit))
	r.Use(securityHeaders)

	r.NotFound(func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "resource not found")
	})
	r.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
	})

	r.Get("/healthz", s.healthz)

	// Public, token-gated incident share page (own CSP; see sharepage).
	r.Group(func(r chi.Router) {
		r.Use(s.shareLimit.Middleware(clientIP, s.rateLimited))
		r.Get("/s/{token}", s.share.Page)
		r.Get("/s/{token}/data", s.share.Data)
	})
	r.Handle("/static/*", s.share.Static())

	r.Route("/v1", func(r chi.Router) {
		r.Group(func(r chi.Router) {
			r.Use(s.authLimit.Middleware(clientIP, s.rateLimited))
			r.Post("/auth/register", s.wrap(s.register))
			r.Post("/auth/login", s.wrap(s.login))
		})
		r.Group(func(r chi.Router) {
			r.Use(s.tokenLimit.Middleware(clientIP, s.rateLimited))
			r.Post("/auth/refresh", s.wrap(s.refresh))
			r.Post("/auth/logout", s.wrap(s.logout))
		})

		r.Group(func(r chi.Router) {
			r.Use(s.tokens.Middleware(func(w http.ResponseWriter, r *http.Request) {
				writeError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid access token")
			}))

			r.Get("/me", s.wrap(s.getMe))
			r.Patch("/me/settings", s.wrap(s.patchSettings))

			r.Post("/families", s.wrap(s.createFamily))
			r.With(s.authLimit.Middleware(userKey, s.rateLimited)).Post("/families/join", s.wrap(s.joinFamily))
			r.Patch("/families/{id}", s.wrap(s.renameFamily))
			r.Post("/families/{id}/invites", s.wrap(s.createInvite))
			r.Get("/families/{id}/members", s.wrap(s.listMembers))
			r.Delete("/families/{id}/members/{userId}", s.wrap(s.removeMember))

			r.Get("/grants", s.wrap(s.listGrants))
			r.Put("/grants/{viewerId}", s.wrap(s.putGrant))

			r.Post("/location/heartbeat", s.wrap(s.heartbeat))
			r.Get("/family/locations", s.wrap(s.familyLocations))

			r.Post("/incidents", s.wrap(s.createIncident))
			r.Get("/incidents/active", s.wrap(s.activeIncidents))
			r.Get("/incidents/{id}", s.wrap(s.getIncident))
			r.Post("/incidents/{id}/trail", s.wrap(s.appendTrail))
			r.Post("/incidents/{id}/resolve", s.wrap(s.resolveIncident))
			r.Post("/incidents/{id}/ack", s.wrap(s.ackIncident))
		})
	})
	return r
}

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.pool.Ping(ctx); err != nil {
		s.log.WarnContext(r.Context(), "health check: database unreachable", "err", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) rateLimited(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, retry later")
}

// clientIP is the per-IP rate-limit key: the proxy-supplied IP, else the TCP
// peer, normalised by ipRateKey.
func clientIP(r *http.Request) string {
	ip := middleware.GetClientIP(r.Context())
	if ip == "" {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		ip = host
	}
	return ipRateKey(ip)
}

// ipRateKey keys IPv6 clients by their /64. A subscriber is normally handed a
// whole /64 (often a /56), so per-address keys would let one client rotate
// addresses and reset every per-IP limit. IPv4 (including v4-mapped IPv6) is
// keyed per address; unparsable input is used verbatim.
func ipRateKey(ip string) string {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return ip
	}
	addr = addr.Unmap().WithZone("")
	if addr.Is4() {
		return addr.String()
	}
	p, err := addr.Prefix(64)
	if err != nil {
		return addr.String()
	}
	return p.String()
}

func userKey(r *http.Request) string {
	if uid, ok := auth.UserID(r.Context()); ok {
		return "user:" + uid.String()
	}
	return clientIP(r)
}

// currentUser returns the authenticated caller (routes behind the auth middleware).
func currentUser(r *http.Request) uuid.UUID {
	uid, _ := auth.UserID(r.Context())
	return uid
}

// pathUUID parses a UUID path parameter. Malformed ids are reported as 404 so
// probing never distinguishes "bad id" from "not yours".
func pathUUID(r *http.Request, name string) (uuid.UUID, error) {
	id, err := uuid.Parse(chi.URLParam(r, name))
	if err != nil || id == uuid.Nil {
		return uuid.Nil, notFound()
	}
	return id, nil
}

func routePattern(r *http.Request) string {
	if rc := chi.RouteContext(r.Context()); rc != nil && rc.RoutePattern() != "" {
		return rc.RoutePattern()
	}
	return "unmatched"
}

// securityHeaders sets restrictive defaults; the share page and static assets
// override the CSP with their own policy.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Cache-Control", "no-store")
		h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}

// logRequests logs one line per request using the route pattern, never the raw
// path: share tokens live in /s/{token} URLs and must not reach logs.
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		if id := middleware.GetReqID(r.Context()); id != "" {
			ww.Header().Set("X-Request-Id", id)
		}
		next.ServeHTTP(ww, r)
		status := ww.Status()
		if status == 0 {
			status = http.StatusOK
		}
		level := slog.LevelInfo
		if status >= 500 {
			level = slog.LevelError
		}
		s.log.Log(r.Context(), level, "request",
			"method", r.Method,
			"route", routePattern(r),
			"status", status,
			"bytes", ww.BytesWritten(),
			"duration_ms", time.Since(start).Milliseconds(),
			"request_id", middleware.GetReqID(r.Context()),
		)
	})
}

func (s *Server) recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if v == http.ErrAbortHandler {
					panic(v)
				}
				s.log.ErrorContext(r.Context(), "panic", "value", fmt.Sprint(v), "stack", string(debug.Stack()))
				writeError(w, http.StatusInternalServerError, "internal", "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
