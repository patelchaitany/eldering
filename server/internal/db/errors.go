package db

import (
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// SQLSTATE codes the API maps to HTTP statuses.
const (
	CodeUniqueViolation     = "23505"
	CodeForeignKeyViolation = "23503"
	CodeCheckViolation      = "23514"
	CodeInvalidText         = "22P02"
	CodeInsufficientPriv    = "42501" // includes "new row violates row-level security policy"
	CodeQueryCanceled       = "57014" // statement_timeout
	CodeLockNotAvailable    = "55P03" // lock_timeout
)

// Code returns the SQLSTATE of err, or "" if err is not a server error.
func Code(err error) string {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr.Code
	}
	return ""
}

// IsNoRows reports whether err is pgx.ErrNoRows.
func IsNoRows(err error) bool { return errors.Is(err, pgx.ErrNoRows) }

// IsUniqueViolation reports a unique/primary-key conflict.
func IsUniqueViolation(err error) bool { return Code(err) == CodeUniqueViolation }

// IsRLSViolation reports a row-level-security WITH CHECK failure or missing privilege.
func IsRLSViolation(err error) bool { return Code(err) == CodeInsufficientPriv }

// IsTimeout reports statement/lock timeouts.
func IsTimeout(err error) bool {
	c := Code(err)
	return c == CodeQueryCanceled || c == CodeLockNotAvailable
}
