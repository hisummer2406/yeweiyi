package partitions_test

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"yeweiyi-cloud/internal/partitions"
)

func testDatabaseURL() string {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
}

// TestEnsureFuture_CreatesMissingPartitionAndAcceptsInserts exercises the actual gap this package
// closes: db/migrations/000001_init_schema.up.sql only pre-creates partitions through 2026-09, so a
// row dated far enough ahead has nowhere to land without EnsureFuture having run first.
func TestEnsureFuture_CreatesMissingPartitionAndAcceptsInserts(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(pool.Close)

	// Far enough ahead that it can't already exist from the migration's own pre-created partitions.
	// Built the same way EnsureFuture computes month boundaries (time.Date month-arithmetic, not
	// AddDate on today's day-of-month) — AddDate(0, 6, 0) would silently roll into month 7 whenever
	// today's day-of-month doesn't exist 6 months out (e.g. Aug 29 -> Feb 29 in a non-leap year).
	now := time.Now().UTC()
	const lookaheadMonths = 6
	future := time.Date(now.Year(), now.Month()+time.Month(lookaheadMonths), 1, 0, 0, 0, 0, time.UTC)

	if err := partitions.EnsureFuture(ctx, pool, lookaheadMonths); err != nil {
		t.Fatalf("ensure future: %v", err)
	}

	const deviceName = "partitions-test-device"
	if _, err := pool.Exec(ctx, `INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT DO NOTHING`, deviceName); err != nil {
		t.Fatalf("seed device: %v", err)
	}
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM alarms WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup alarms: %v", err)
		}
		if _, err := pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup devices: %v", err)
		}
	})

	eventTime := time.Date(future.Year(), future.Month(), 15, 9, 0, 0, 0, time.UTC)
	_, err = pool.Exec(ctx, `
		INSERT INTO alarms (device_name, device_local_id, time, info, guanhao, jiezhi)
		VALUES ($1, 1, $2, 'test', '1', 'test')`, deviceName, eventTime)
	if err != nil {
		t.Fatalf("insert into future partition: %v", err)
	}

	// Idempotency the periodic Run loop relies on: calling again must be a no-op, not an error.
	if err := partitions.EnsureFuture(ctx, pool, lookaheadMonths); err != nil {
		t.Fatalf("ensure future (second call): %v", err)
	}
}
