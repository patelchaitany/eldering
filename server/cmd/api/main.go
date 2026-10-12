// Command eldering-api is the Eldering backend.
//
//	eldering-api serve        run the HTTP API (default)
//	eldering-api migrate      bootstrap roles/grants (with DATABASE_ADMIN_URL) and apply migrations
//	eldering-api healthcheck  exit 0 if GET /healthz on the local listener is 200 (for Docker)
//	eldering-api version      print the build version
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/eldering/safety/server/internal/config"
	"github.com/eldering/safety/server/internal/db"
	"github.com/eldering/safety/server/internal/handlers"
	"github.com/eldering/safety/server/internal/retention"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	cmd := "serve"
	if len(args) > 0 {
		cmd = args[0]
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: logLevel()}))
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var err error
	switch cmd {
	case "serve":
		err = serve(ctx, log)
	case "migrate":
		err = migrate(ctx, log)
	case "healthcheck":
		err = healthcheck()
	case "version":
		fmt.Printf("eldering-api %s %s %s/%s\n", version, runtime.Version(), runtime.GOOS, runtime.GOARCH)
	default:
		fmt.Fprintf(os.Stderr, "usage: eldering-api [serve|migrate|healthcheck|version]\n")
		return 2
	}
	if err != nil {
		if cmd == "healthcheck" {
			fmt.Fprintln(os.Stderr, err)
		} else {
			log.Error(cmd+" failed", "err", err)
		}
		return 1
	}
	return 0
}

func logLevel() slog.Level {
	switch strings.ToLower(os.Getenv("LOG_LEVEL")) {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	}
	return slog.LevelInfo
}

func serve(ctx context.Context, log *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	pool, err := openWithRetry(ctx, log, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	srv, err := handlers.New(handlers.Deps{Config: cfg, Pool: pool, Logger: log})
	if err != nil {
		return err
	}
	bg, cancelBG := context.WithCancel(ctx)
	defer cancelBG()
	srv.RunJanitors(bg)
	go retention.New(pool, log, cfg.Retention).Run(bg)

	hs := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.ListenAddr, "version", version, "go", runtime.Version())
		errc <- hs.ListenAndServe()
	}()
	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		log.Info("shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := hs.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("shutdown: %w", err)
		}
	}
	return nil
}

// openWithRetry tolerates the database starting slightly after the API.
func openWithRetry(ctx context.Context, log *slog.Logger, dsn string) (*pgxpool.Pool, error) {
	pool, err := db.Open(ctx, dsn)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(60 * time.Second)
	for {
		pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		err = pool.Ping(pingCtx)
		cancel()
		if err == nil {
			return pool, nil
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			pool.Close()
			return nil, fmt.Errorf("database not reachable: %w", err)
		}
		log.Warn("waiting for database", "err", err)
		select {
		case <-ctx.Done():
		case <-time.After(2 * time.Second):
		}
	}
}

func migrate(ctx context.Context, log *slog.Logger) error {
	cfg, err := config.LoadMigrate()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	if cfg.AdminURL != "" {
		admin, err := connectWithRetry(ctx, cfg.AdminURL)
		if err != nil {
			return fmt.Errorf("connect admin: %w", err)
		}
		err = db.Bootstrap(ctx, admin, cfg.OwnerPassword, cfg.AppPassword)
		admin.Close(context.Background())
		if err != nil {
			return err
		}
		log.Info("roles and grants bootstrapped")
	} else {
		log.Info("DATABASE_ADMIN_URL not set; skipping role bootstrap")
	}
	owner, err := connectWithRetry(ctx, cfg.OwnerURL)
	if err != nil {
		return fmt.Errorf("connect owner: %w", err)
	}
	defer owner.Close(context.Background())
	applied, err := db.Migrate(ctx, owner)
	if err != nil {
		return err
	}
	log.Info("migrations complete", "applied", applied)
	return nil
}

func connectWithRetry(ctx context.Context, dsn string) (*pgx.Conn, error) {
	deadline := time.Now().Add(60 * time.Second)
	for {
		c, err := pgx.Connect(ctx, dsn)
		if err == nil {
			return c, nil
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			return nil, err
		}
		select {
		case <-ctx.Done():
		case <-time.After(2 * time.Second):
		}
	}
}

// healthcheck probes the local listener; distroless images have no curl.
func healthcheck() error {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("bad LISTEN_ADDR %q: %w", addr, err)
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/healthz")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("healthz returned %d", resp.StatusCode)
	}
	return nil
}
