package rlsharness

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func NewPool(ctx context.Context, dsn string, maxConns int32) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = maxConns
	cfg.MinConns = 1
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = time.Minute
	cfg.ConnConfig.RuntimeParams["application_name"] = "eldering-api"
	return pgxpool.NewWithConfig(ctx, cfg)
}

// WithUser runs fn in a transaction whose RLS identity is uid.
// set_config(..., true) == SET LOCAL: reverts at COMMIT/ROLLBACK, so a pooled
// connection never carries one request's identity into the next.
func WithUser(ctx context.Context, pool *pgxpool.Pool, uid uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.user_id', $1, true)`, uid.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}

// Anon runs fn in a transaction with no identity (public share page, login, register).
func Anon(ctx context.Context, pool *pgxpool.Pool, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{}, fn)
}
