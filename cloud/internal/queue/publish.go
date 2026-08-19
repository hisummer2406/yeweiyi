// Package queue implements the cloud -> device write-back offline queue (design doc §5.2).
//
// CLAUDE.md scoping note: river only owns the pending -> sent hop (a "job" is "publish this
// command over MQTT"; success = published). The sent -> acked transition is driven by an unrelated
// inbound MQTT message (the device's confirmation, carrying the same command id) and must be
// handled by internal/mqtt calling MarkWriteBackCommandAcked directly, not through river — that
// wiring doesn't exist yet because internal/mqtt doesn't exist yet.
package queue

import (
	"context"

	"github.com/riverqueue/river"

	"yeweiyi-cloud/internal/store/db"
)

// Publisher is implemented by internal/mqtt once it exists. Kept as an interface here so
// internal/queue can be built and tested without a real MQTT/Aliyun IoT connection.
type Publisher interface {
	PublishCommand(ctx context.Context, deviceName string, commandID int64, commandType string, payload []byte) error
}

type PublishCommandArgs struct {
	CommandID int64 `json:"command_id"`
}

func (PublishCommandArgs) Kind() string { return "write_back_publish" }

type PublishCommandWorker struct {
	river.WorkerDefaults[PublishCommandArgs]
	Queries   *db.Queries
	Publisher Publisher
}

func (w *PublishCommandWorker) Work(ctx context.Context, job *river.Job[PublishCommandArgs]) error {
	cmd, err := w.Queries.GetWriteBackCommand(ctx, job.Args.CommandID)
	if err != nil {
		return err
	}

	// Idempotent: a retried job (or a manual re-run) on a command that already moved past pending
	// is a no-op, not an error — design doc §5.2 requires redelivery safety, not just first-try safety.
	if cmd.Status != "pending" {
		return nil
	}

	if err := w.Publisher.PublishCommand(ctx, cmd.DeviceName, cmd.ID, cmd.CommandType, cmd.Payload); err != nil {
		return err
	}

	return w.Queries.MarkWriteBackCommandSent(ctx, cmd.ID)
}
