package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/rbac"
)

// apiError is an error with a client-facing status, code and message.
type apiError struct {
	Status  int
	Code    string
	Message string
}

func (e *apiError) Error() string { return fmt.Sprintf("%d %s: %s", e.Status, e.Code, e.Message) }

func badRequest(code, msg string) error { return &apiError{http.StatusBadRequest, code, msg} }
func conflict(code, msg string) error   { return &apiError{http.StatusConflict, code, msg} }
func notFound() error                   { return &apiError{http.StatusNotFound, "not_found", "resource not found"} }
func forbidden(msg string) error        { return &apiError{http.StatusForbidden, "forbidden", msg} }
func unauthorized(code, msg string) error {
	return &apiError{http.StatusUnauthorized, code, msg}
}

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// writeJSON writes v with status. Encoding errors after the header is sent can
// only be logged.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(true)
	_ = enc.Encode(v)
}

// writeError writes the SPEC error envelope {error:{code,message}}.
func writeError(w http.ResponseWriter, status int, code, msg string) {
	if status == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", `Bearer realm="eldering"`)
	}
	writeJSON(w, status, errorBody{Error: errorDetail{Code: code, Message: msg}})
}

// handlerFunc is an HTTP handler that reports failures as errors; wrap maps
// them to responses in one place.
type handlerFunc func(w http.ResponseWriter, r *http.Request) error

func (s *Server) wrap(h handlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := h(w, r); err != nil {
			s.respondError(w, r, err)
		}
	}
}

// respondError maps domain and database errors to HTTP without leaking
// existence of other users' rows (RLS denials look like 404).
func (s *Server) respondError(w http.ResponseWriter, r *http.Request, err error) {
	var ae *apiError
	var mbe *http.MaxBytesError
	switch {
	case errors.As(err, &ae):
		writeError(w, ae.Status, ae.Code, ae.Message)
	case errors.As(err, &mbe):
		writeError(w, http.StatusRequestEntityTooLarge, "body_too_large", fmt.Sprintf("request body exceeds %d bytes", mbe.Limit))
	case errors.Is(err, rbac.ErrNotFound), db.IsNoRows(err):
		writeError(w, http.StatusNotFound, "not_found", "resource not found")
	case errors.Is(err, rbac.ErrForbidden):
		writeError(w, http.StatusForbidden, "forbidden", "not allowed")
	case db.IsRLSViolation(err):
		s.log.WarnContext(r.Context(), "rls denial", "err", err, "route", routePattern(r))
		writeError(w, http.StatusNotFound, "not_found", "resource not found")
	case db.IsUniqueViolation(err):
		writeError(w, http.StatusConflict, "conflict", "resource already exists")
	case db.Code(err) == db.CodeForeignKeyViolation, db.Code(err) == db.CodeCheckViolation, db.Code(err) == db.CodeInvalidText:
		s.log.InfoContext(r.Context(), "constraint violation", "err", err, "route", routePattern(r))
		writeError(w, http.StatusBadRequest, "invalid_request", "request violates a constraint")
	case db.IsTimeout(err):
		s.log.WarnContext(r.Context(), "database timeout", "err", err, "route", routePattern(r))
		writeError(w, http.StatusServiceUnavailable, "unavailable", "temporarily unavailable, retry")
	case errors.Is(err, context.Canceled):
		// Client went away; nothing useful to send.
		s.log.DebugContext(r.Context(), "request canceled", "route", routePattern(r))
	default:
		s.log.ErrorContext(r.Context(), "internal error", "err", err, "route", routePattern(r))
		writeError(w, http.StatusInternalServerError, "internal", "internal server error")
	}
}

// decodeJSON reads exactly one JSON value from the (size-limited) body.
// Unknown fields are ignored on purpose: a newer client adding an optional
// field must never break an emergency upload. Required fields are validated
// by each handler.
func decodeJSON(r *http.Request, dst any) error {
	if r.Body == nil {
		return badRequest("invalid_json", "request body required")
	}
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var mbe *http.MaxBytesError
		switch {
		case errors.As(err, &mbe):
			return err
		case errors.Is(err, io.EOF):
			return badRequest("invalid_json", "request body required")
		default:
			return badRequest("invalid_json", "malformed JSON: "+err.Error())
		}
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return err
		}
		return badRequest("invalid_json", "unexpected data after JSON body")
	}
	return nil
}

// Optional distinguishes an absent JSON field from an explicit null (PATCH).
type Optional[T any] struct {
	Set   bool
	Null  bool
	Value T
}

// UnmarshalJSON is only called when the field is present.
func (o *Optional[T]) UnmarshalJSON(b []byte) error {
	o.Set = true
	if string(b) == "null" {
		o.Null = true
		return nil
	}
	return json.Unmarshal(b, &o.Value)
}
