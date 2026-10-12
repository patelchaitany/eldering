// Package retention runs the hourly purge required by SPEC: incidents and their
// trail/acks are hard-deleted 72 h after resolution (or after share-link expiry
// for incidents never resolved). It also drops expired refresh tokens and
// long-expired invites.
package retention

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/db"
)

// Result counts what one run removed.
type Result struct {
	Incidents     int64
	RefreshTokens int64
	Invites       int64
}

// Job is the periodic purge.
type Job struct {
	pool     *pgxpool.Pool
	log      *slog.Logger
	interval time.Duration
}

// New returns a job that runs every interval.
func New(pool *pgxpool.Pool, log *slog.Logger, interval time.Duration) *Job {
	return &Job{pool: pool, log: log, interval: interval}
}

// RunOnce performs a single purge. Incidents are RLS-protected, so the delete
// goes through rls.purge_expired_incidents() (SECURITY DEFINER, owned by the
// BYPASSRLS definer role); the API role itself cannot delete other users' rows.
func (j *Job) RunOnce(ctx context.Context) (Result, error) {
	var res Result
	err := db.Anon(ctx, j.pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT rls.purge_expired_incidents()`).Scan(&res.Incidents); err != nil {
			return err
		}
		// Revoked-but-unexpired tokens are kept: they power refresh-reuse detection.
		tag, err := tx.Exec(ctx, `DELETE FROM refresh_tokens WHERE expires_at < now()`)
		if err != nil {
			return err
		}
		res.RefreshTokens = tag.RowsAffected()
		tag, err = tx.Exec(ctx, `DELETE FROM family_invites WHERE expires_at < now() - interval '7 days'`)
		if err != nil {
			return err
		}
		res.Invites = tag.RowsAffected()
		return nil
	})
	return res, err
}

// Run purges once shortly after start and then every interval until ctx is done.
func (j *Job) Run(ctx context.Context) {
	first := time.NewTimer(30 * time.Second)
	defer first.Stop()
	select {
	case <-ctx.Done():
		return
	case <-first.C:
	}
	j.runLogged(ctx)
	t := time.NewTicker(j.interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			j.runLogged(ctx)
		}
	}
}

func (j *Job) runLogged(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	res, err := j.RunOnce(ctx)
	if err != nil {
		j.log.ErrorContext(ctx, "retention purge failed", "err", err)
		return
	}
	j.log.InfoContext(ctx, "retention purge", "incidents", res.Incidents, "refresh_tokens", res.RefreshTokens, "invites", res.Invites)
}
