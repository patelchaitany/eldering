// Package db owns the PostgreSQL connection pool, the per-request RLS identity,
// the role bootstrap and the embedded schema migrations.
package db

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Querier is the subset of pgx.Tx used by helpers that run inside a request
// transaction.
type Querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// Open creates a pool for dsn. Pool size comes from the DSN (pool_max_conns);
// timestamptz values are always scanned in UTC so JSON output is uniform.
func Open(ctx context.Context, dsn string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse database url: %w", err)
	}
	cfg.MinConns = 1
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = time.Minute
	cfg.ConnConfig.RuntimeParams["application_name"] = "eldering-api"
	cfg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	cfg.AfterConnect = func(_ context.Context, conn *pgx.Conn) error {
		conn.TypeMap().RegisterType(&pgtype.Type{
			Name:  "timestamptz",
			OID:   pgtype.TimestamptzOID,
			Codec: &pgtype.TimestamptzCodec{ScanLocation: time.UTC},
		})
		return nil
	}
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("open pool: %w", err)
	}
	return pool, nil
}

// TxBeginner is satisfied by *pgxpool.Pool and *pgx.Conn.
type TxBeginner interface {
	BeginTx(ctx context.Context, txOptions pgx.TxOptions) (pgx.Tx, error)
}

// WithUser runs fn in a transaction whose RLS identity is uid.
//
// set_config(..., is_local => true) is the parameterised form of SET LOCAL:
// it reverts at COMMIT/ROLLBACK, so a pooled connection never carries one
// request's identity into the next.
func WithUser(ctx context.Context, db TxBeginner, uid uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, db, pgx.TxOptions{}, func(tx pgx.Tx) error {
		if err := SetUser(ctx, tx, uid); err != nil {
			return err
		}
		return fn(tx)
	})
}

// Anon runs fn in a transaction without an identity: rls.uid() is NULL and
// every RLS-protected table appears empty.
func Anon(ctx context.Context, db TxBeginner, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, db, pgx.TxOptions{}, fn)
}

// SetUser sets the RLS identity for the rest of the current transaction.
// Used directly by registration, where the user id only exists mid-transaction.
func SetUser(ctx context.Context, tx pgx.Tx, uid uuid.UUID) error {
	if uid == uuid.Nil {
		return fmt.Errorf("db: refusing to set nil user id")
	}
	if _, err := tx.Exec(ctx, `SELECT set_config('app.user_id', $1, true)`, uid.String()); err != nil {
		return fmt.Errorf("set rls identity: %w", err)
	}
	return nil
}
