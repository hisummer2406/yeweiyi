// Command backfill copies every 出入库 (stockEvent) and 报警 (alarm) record ThingsBoard still holds
// into the business database (issue #29). Run it once before enabling a ThingsBoard telemetry TTL
// (TS_RETENTION_SECONDS > 0): the server only backfills a bounded lookback on its own, plus the whole
// history of a device the database has no records for.
//
// Idempotent and safe to run while the server is up or to repeat: rows converge on the same state
// however the live subscription and this interleave. It never deletes anything.
//
//	go run ./cmd/backfill [-config config.yaml] [-device NAME] [-from RFC3339]
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"yeweiyi-cloud/internal/config"
	"yeweiyi-cloud/internal/ingest"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

func main() {
	configPath := flag.String("config", "", "path to config.yaml (see config.example.yaml)")
	only := flag.String("device", "", "backfill just this deviceName (default: every device in the business database)")
	from := flag.String("from", "", "only read points at or after this RFC3339 instant (default: from the beginning)")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	if cfg.ThingsBoard.BaseURL == "" {
		log.Fatal("thingsboard.baseURL is not set: nothing to backfill from")
	}

	var fromMs int64
	if *from != "" {
		t, err := time.Parse(time.RFC3339, *from)
		if err != nil {
			log.Fatalf("-from must be RFC3339: %v", err)
		}
		fromMs = t.UnixMilli()
	}

	ctx := context.Background()
	pool, err := pgxpool.New(ctx, cfg.Database.URL)
	if err != nil {
		log.Fatalf("postgres: %v", err)
	}
	defer pool.Close()
	queries := db.New(pool)

	tb, err := tbclient.New(tbclient.Config{
		BaseURL: cfg.ThingsBoard.BaseURL, Username: cfg.ThingsBoard.Username, Password: cfg.ThingsBoard.Password,
	})
	if err != nil {
		log.Fatalf("thingsboard: %v", err)
	}
	if err := tb.Login(ctx); err != nil {
		log.Fatalf("thingsboard login: %v", err)
	}

	ing := ingest.New(ingest.Config{TB: tb, Queries: queries, Logf: log.Printf})

	names := []string{*only}
	if *only == "" {
		if names, err = queries.ListAllDeviceNames(ctx); err != nil {
			log.Fatalf("list devices: %v", err)
		}
	}

	failed, absent := 0, 0
	for _, name := range names {
		res, err := ing.BackfillDevice(ctx, name, fromMs)
		if errors.Is(err, tbclient.ErrNotFound) {
			// Bound in the business database but never provisioned in ThingsBoard: it has published
			// nothing, so there is nothing to lose. Counted and named, not treated as a failure.
			absent++
			log.Printf("%s: not in ThingsBoard, skipped", name)
			continue
		}
		if err != nil {
			failed++
			log.Printf("%s: FAILED: %v", name, err)
			continue
		}
		log.Printf("%s: read %d stockEvent and %d alarm points", name, res.StockPoints, res.AlarmPoints)
	}
	log.Printf("done: %d device(s), %d not in ThingsBoard, %d failed", len(names), absent, failed)
	if failed > 0 {
		log.Fatal("some devices failed; do not enable the ThingsBoard TTL until a rerun is clean")
	}
}
