// Package sharepage serves the public, token-gated incident page
// (GET /s/{token}), its JSON feed (GET /s/{token}/data) and the static assets
// it needs (vendored MapLibre GL JS + page script/style), all same-origin.
//
// A share token is 32 random bytes (base64url); only its SHA-256 is stored.
// The page shows the incident's latest location, trail and landmarks while the
// link is valid (72 h) and the incident is active — nothing else.
package sharepage

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"html/template"
	"io/fs"
	"log/slog"
	"net/http"
	"path"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/auth"
	"github.com/eldering/safety/server/internal/db"
)

// MapLibreVersion is the vendored MapLibre GL JS release (ESM-only since v6).
const MapLibreVersion = "6.11.2"

// StyleURL is the OpenFreeMap style the page renders (no API key; attribution required).
const StyleURL = "https://tiles.openfreemap.org/styles/liberty"

// CSP for the page and the static assets. Workers take the CSP of their own
// script response, so the static files carry the same policy (connect-src must
// allow the tile host for the MapLibre worker).
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; " +
	"img-src 'self' data: blob: https://tiles.openfreemap.org; " +
	"connect-src 'self' https://tiles.openfreemap.org; worker-src 'self'; " +
	"base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

//go:embed static templates
var content embed.FS

// Handler serves the share page.
type Handler struct {
	pool   *pgxpool.Pool
	log    *slog.Logger
	tmpl   *template.Template
	static map[string]staticFile
}

type staticFile struct {
	body        []byte
	contentType string
	etag        string
	immutable   bool
}

var contentTypes = map[string]string{
	".mjs": "text/javascript; charset=utf-8",
	".js":  "text/javascript; charset=utf-8",
	".css": "text/css; charset=utf-8",
	".txt": "text/plain; charset=utf-8",
}

// New parses the template and indexes the embedded assets.
func New(pool *pgxpool.Pool, log *slog.Logger) (*Handler, error) {
	tmpl, err := template.New("share.html").Funcs(template.FuncMap{
		"human": func(t time.Time) string { return t.UTC().Format("2006-01-02 15:04 UTC") },
		"iso":   func(t time.Time) string { return t.UTC().Format(time.RFC3339) },
	}).ParseFS(content, "templates/share.html")
	if err != nil {
		return nil, fmt.Errorf("parse share template: %w", err)
	}
	h := &Handler{pool: pool, log: log, tmpl: tmpl, static: map[string]staticFile{}}
	err = fs.WalkDir(content, "static", func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		ct, ok := contentTypes[path.Ext(p)]
		if !ok {
			return nil
		}
		b, err := content.ReadFile(p)
		if err != nil {
			return err
		}
		sum := sha256.Sum256(b)
		h.static[strings.TrimPrefix(p, "static/")] = staticFile{
			body:        b,
			contentType: ct,
			etag:        `"` + hex.EncodeToString(sum[:8]) + `"`,
			immutable:   strings.HasPrefix(p, "static/maplibre-gl/"),
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return h, nil
}

// Static serves /static/* from the embedded assets.
func (h *Handler) Static() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		f, ok := h.static[strings.TrimPrefix(r.URL.Path, "/static/")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		hd := w.Header()
		hd.Set("Content-Type", f.contentType)
		hd.Set("Content-Security-Policy", CSP)
		hd.Set("X-Robots-Tag", "noindex, nofollow")
		hd.Set("Cross-Origin-Resource-Policy", "same-origin")
		hd.Set("ETag", f.etag)
		if f.immutable { // versioned path
			hd.Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			hd.Set("Cache-Control", "public, max-age=300")
		}
		if match := r.Header.Get("If-None-Match"); match != "" && match == f.etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.WriteHeader(http.StatusOK)
		if r.Method == http.MethodGet {
			_, _ = w.Write(f.body)
		}
	})
}

// Point is a trail/latest location in the public feed.
type Point struct {
	Lat        float64   `json:"lat"`
	Lng        float64   `json:"lng"`
	RecordedAt time.Time `json:"recorded_at"`
	AccuracyM  *float32  `json:"accuracy_m"`
	Landmark   *string   `json:"landmark,omitempty"`
}

// Data is the public JSON for a share link. It deliberately carries no user
// identity (name, email, user id): only what the SPEC allows.
type Data struct {
	Kind             string     `json:"kind"`
	Status           string     `json:"status"`
	DetectedAt       time.Time  `json:"detected_at"`
	ShareExpiresAt   time.Time  `json:"share_expires_at"`
	ResolvedAt       *time.Time `json:"resolved_at"`
	Resolution       *string    `json:"resolution"`
	IncidentLocation *Point     `json:"incident_location"`
	Latest           *Point     `json:"latest"`
	Trail            []Point    `json:"trail"`
	ServerTime       time.Time  `json:"server_time"`
}

var errNotFound = errors.New("share: not found")

// load resolves a token anonymously through the SECURITY DEFINER functions;
// unknown, malformed and expired tokens are indistinguishable.
func (h *Handler) load(r *http.Request) (*Data, error) {
	token := chi.URLParam(r, "token")
	if !auth.LooksLikeOpaqueToken(token) {
		return nil, errNotFound
	}
	hash := auth.HashToken(token)
	ctx := r.Context()
	var d Data
	err := db.Anon(ctx, h.pool, func(tx pgx.Tx) error {
		var lat, lng *float64
		err := tx.QueryRow(ctx, `SELECT kind, status, detected_at, lat, lng, share_expires_at, resolved_at, resolution, now()
			FROM rls.shared_incident($1)`, hash).
			Scan(&d.Kind, &d.Status, &d.DetectedAt, &lat, &lng, &d.ShareExpiresAt, &d.ResolvedAt, &d.Resolution, &d.ServerTime)
		if db.IsNoRows(err) {
			return errNotFound
		}
		if err != nil {
			return err
		}
		if lat != nil && lng != nil {
			d.IncidentLocation = &Point{Lat: *lat, Lng: *lng, RecordedAt: d.DetectedAt}
		}
		rows, err := tx.Query(ctx, `SELECT recorded_at, lat, lng, accuracy_m, landmark FROM rls.shared_trail($1)`, hash)
		if err != nil {
			return err
		}
		d.Trail, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (Point, error) {
			var p Point
			err := row.Scan(&p.RecordedAt, &p.Lat, &p.Lng, &p.AccuracyM, &p.Landmark)
			return p, err
		})
		return err
	})
	if err != nil {
		return nil, err
	}
	if d.Trail == nil {
		d.Trail = []Point{}
	}
	if n := len(d.Trail); n > 0 {
		last := d.Trail[n-1]
		d.Latest = &Point{Lat: last.Lat, Lng: last.Lng, RecordedAt: last.RecordedAt, AccuracyM: last.AccuracyM}
	} else if d.IncidentLocation != nil {
		d.Latest = d.IncidentLocation
	}
	return &d, nil
}

func publicHeaders(w http.ResponseWriter) {
	hd := w.Header()
	hd.Set("Cache-Control", "no-store")
	hd.Set("X-Robots-Tag", "noindex, nofollow")
	hd.Set("Referrer-Policy", "no-referrer") // keeps the token out of Referer to tile hosts
	hd.Set("Content-Security-Policy", CSP)
}

// Data serves GET /s/{token}/data.
func (h *Handler) Data(w http.ResponseWriter, r *http.Request) {
	publicHeaders(w)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	d, err := h.load(r)
	switch {
	case errors.Is(err, errNotFound):
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"link is invalid or has expired"}}` + "\n"))
		return
	case err != nil:
		h.log.ErrorContext(r.Context(), "share data", "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":{"code":"internal","message":"internal server error"}}` + "\n"))
		return
	}
	w.WriteHeader(http.StatusOK)
	_ = jsonEncode(w, d)
}

// Page serves GET /s/{token}: a complete text rendering (works with no
// JavaScript and no map tiles), progressively enhanced by /static/share.js.
func (h *Handler) Page(w http.ResponseWriter, r *http.Request) {
	publicHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	d, err := h.load(r)
	status := http.StatusOK
	var view *pageView
	switch {
	case errors.Is(err, errNotFound):
		status = http.StatusNotFound
	case err != nil:
		h.log.ErrorContext(r.Context(), "share page", "err", err)
		status = http.StatusInternalServerError
	default:
		view = newPageView(d)
	}
	w.WriteHeader(status)
	if err := h.tmpl.Execute(w, pageModel{View: view, Status: status, MapLibre: "/static/maplibre-gl/" + MapLibreVersion}); err != nil {
		h.log.ErrorContext(r.Context(), "render share page", "err", err)
	}
}
