// Package partitions keeps alarms/oil_in_out's monthly range partitions pre-created ahead of time.
// db/migrations/000001_init_schema.up.sql only creates the first couple of months (see its own
// comments) — without this running continuously, an insert whose time falls in an un-created month
// fails outright (declarative partitioning has no default partition here). Deliberately not a river
// periodic job like internal/queue's TTLSweepWorker: river is scoped in CLAUDE.md specifically to
// the write-back queue, and CREATE TABLE IF NOT EXISTS is already idempotent/safe to run from more
// than one instance without river's coordination, so a plain ticker is simpler and sufficient.
package partitions

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// PartitionedTables mirrors CLAUDE.md's "Tech stack" call-out: Alarm/OilInOut are the high-write-
// volume event tables that need partitioning; OilCanSet and write_back_commands stay small and
// aren't partitioned.
var PartitionedTables = []string{"alarms", "oil_in_out"}

const (
	DefaultInterval  = 24 * time.Hour
	DefaultLookahead = 2 // months beyond the current one kept pre-created at all times
)

// EnsureFuture creates any missing monthly partitions for PartitionedTables, covering the current
// month through lookahead months ahead. Table/partition names are built from a fixed table list and
// integer year/month values, never external input, so direct string interpolation into DDL (rather
// than a placeholder — Postgres doesn't support parameterizing identifiers) is safe here.
func EnsureFuture(ctx context.Context, pool *pgxpool.Pool, lookahead int) error {
	now := time.Now().UTC()
	for _, table := range PartitionedTables {
		for i := 0; i <= lookahead; i++ {
			start := time.Date(now.Year(), now.Month()+time.Month(i), 1, 0, 0, 0, 0, time.UTC)
			end := start.AddDate(0, 1, 0)
			partition := fmt.Sprintf("%s_%04d_%02d", table, start.Year(), int(start.Month()))
			stmt := fmt.Sprintf(
				`CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')`,
				partition, table, start.Format("2006-01-02"), end.Format("2006-01-02"),
			)
			if _, err := pool.Exec(ctx, stmt); err != nil {
				return fmt.Errorf("create partition %s: %w", partition, err)
			}
		}
	}
	return nil
}

// Run ensures future partitions exist immediately, then again on every tick, until ctx is
// cancelled. Intended to run as a background goroutine for the process lifetime (cmd/server).
func Run(ctx context.Context, pool *pgxpool.Pool, interval time.Duration, lookahead int) {
	if err := EnsureFuture(ctx, pool, lookahead); err != nil {
		log.Printf("partitions: initial ensure failed: %v", err)
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := EnsureFuture(ctx, pool, lookahead); err != nil {
				log.Printf("partitions: ensure failed: %v", err)
			}
		}
	}
}
