package queue

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"github.com/riverqueue/river"

	"yeweiyi-cloud/internal/store/db"
)

// DefaultTTL is a placeholder, not a product decision. Design doc §5.3 uses "~30 days" only as an
// example of the device-side retry queue's TTL — CLAUDE.md's "Open product decisions" explicitly
// leaves the exact number unconfirmed. This gives the mechanism a sane default so the sweep exists
// from day one; override it via Config.TTL (wired from an env var in cmd/server) once product picks
// a real number.
const DefaultTTL = 30 * 24 * time.Hour

const DefaultTTLSweepInterval = time.Hour

type TTLSweepArgs struct{}

func (TTLSweepArgs) Kind() string { return "write_back_ttl_sweep" }

type TTLSweepWorker struct {
	river.WorkerDefaults[TTLSweepArgs]
	Queries *db.Queries
	TTL     time.Duration
}

func (w *TTLSweepWorker) Work(ctx context.Context, job *river.Job[TTLSweepArgs]) error {
	cutoff := time.Now().Add(-w.TTL)
	expired, err := w.Queries.ListExpirableWriteBackCommands(ctx, pgtype.Timestamptz{Time: cutoff, Valid: true})
	if err != nil {
		return err
	}

	for _, cmd := range expired {
		if err := w.Queries.MarkWriteBackCommandFailed(ctx, cmd.ID); err != nil {
			return err
		}
	}
	return nil
}

func newTTLSweepPeriodicJob(interval time.Duration) *river.PeriodicJob {
	return river.NewPeriodicJob(
		river.PeriodicInterval(interval),
		func() (river.JobArgs, *river.InsertOpts) { return TTLSweepArgs{}, nil },
		&river.PeriodicJobOpts{RunOnStart: false},
	)
}
