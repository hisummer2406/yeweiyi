package queue

import (
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"
	"github.com/riverqueue/river/riverdriver/riverpgxv5"

	"yeweiyi-cloud/internal/store/db"
)

type Config struct {
	Pool             *pgxpool.Pool
	Queries          *db.Queries
	Publisher        Publisher
	TTL              time.Duration // defaults to DefaultTTL
	TTLSweepInterval time.Duration // defaults to DefaultTTLSweepInterval
	MaxWorkers       int           // defaults to 10
}

// NewClient wires the two river workers this package owns (see publish.go's package doc for what
// river does and doesn't cover of the pending/sent/acked/failed state machine).
func NewClient(cfg Config) (*river.Client[pgx.Tx], error) {
	if cfg.TTL <= 0 {
		cfg.TTL = DefaultTTL
	}
	if cfg.TTLSweepInterval <= 0 {
		cfg.TTLSweepInterval = DefaultTTLSweepInterval
	}
	if cfg.MaxWorkers <= 0 {
		cfg.MaxWorkers = 10
	}

	workers := river.NewWorkers()
	river.AddWorker(workers, &PublishCommandWorker{Queries: cfg.Queries, Publisher: cfg.Publisher})
	river.AddWorker(workers, &TTLSweepWorker{Queries: cfg.Queries, TTL: cfg.TTL})

	return river.NewClient(riverpgxv5.New(cfg.Pool), &river.Config{
		Queues: map[string]river.QueueConfig{
			river.QueueDefault: {MaxWorkers: cfg.MaxWorkers},
		},
		Workers: workers,
		PeriodicJobs: []*river.PeriodicJob{
			newTTLSweepPeriodicJob(cfg.TTLSweepInterval),
		},
	})
}
