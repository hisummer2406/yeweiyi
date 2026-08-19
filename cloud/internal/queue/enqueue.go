package queue

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"

	"yeweiyi-cloud/internal/store/db"
)

// EnqueueWriteBack inserts a pending write_back_commands row and enqueues the river publish job in
// the same transaction. This is the reason river (Postgres-backed) was chosen over a Redis-backed
// queue (CLAUDE.md "Tech stack"): the business row and its delivery job commit atomically, so there
// is no window where one exists without the other.
func EnqueueWriteBack(ctx context.Context, pool *pgxpool.Pool, riverClient *river.Client[pgx.Tx], deviceName, commandType string, payload []byte) (int64, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	cmd, err := db.New(tx).InsertWriteBackCommand(ctx, db.InsertWriteBackCommandParams{
		DeviceName:  deviceName,
		CommandType: commandType,
		Payload:     payload,
	})
	if err != nil {
		return 0, err
	}

	if _, err := riverClient.InsertTx(ctx, tx, PublishCommandArgs{CommandID: cmd.ID}, nil); err != nil {
		return 0, err
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return cmd.ID, nil
}
