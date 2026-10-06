package ingest

import (
	"context"
	"errors"
	"time"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// defaultCommandReconcileInterval is short on purpose: a command's validity window is a minute by
// default, and a result that is only found after the window closes can no longer be recorded (the
// state machine keeps 'expired' terminal). The pass is cheap — it reads nothing for a device that has
// no open command.
const defaultCommandReconcileInterval = 10 * time.Second

// commandReconcileLoop re-reads command results from ThingsBoard until ctx ends.
//
// The live subscription is not enough on its own for results, for the same reasons it is not for
// 出入库 and 报警: a batch keeps only the newest point per key, the socket can be down when the device
// reports, and the database can fail while a result is being applied. A lost result is worse than a
// lost record — the command was acknowledged, may well have run, and would be swept into 'expired'
// as if the outcome were unknown when ThingsBoard has held the answer all along.
func (i *Ingester) commandReconcileLoop(ctx context.Context) {
	ticker := time.NewTicker(i.cfg.CommandReconcileInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			i.ReconcileCommands(ctx)
		}
	}
}

// ReconcileCommands runs one pass: for every device with a command still open, read its cmdResult
// history from the oldest such command's issue time and apply each point. It returns how many
// results it recorded.
//
// Safe to repeat and to run beside the live path: ApplyResult is guarded in SQL, so a point for a
// command that is already terminal, or that belongs to another device, changes nothing.
func (i *Ingester) ReconcileCommands(ctx context.Context) int {
	if i.cfg.Control == nil || i.cfg.Queries == nil || i.cfg.TB == nil {
		return 0
	}
	open, err := i.cfg.Queries.ListDevicesWithOpenControlCommandsUnscoped(ctx)
	if err != nil {
		if ctx.Err() == nil {
			i.cfg.Logf("ingest: command reconcile: list open commands: %v", err)
		}
		return 0
	}

	recorded := 0
	for _, row := range open {
		deviceName := row.DeviceName
		dev, err := i.cfg.TB.GetDeviceByName(ctx, deviceName)
		if err != nil {
			// A device ThingsBoard does not know has no results to find; anything else is a platform
			// problem the next pass will meet again, so it is logged and skipped.
			if ctx.Err() == nil && !errors.Is(err, tbclient.ErrNotFound) {
				i.cfg.Logf("ingest: command reconcile: %s: %v", deviceName, err)
			}
			continue
		}

		// A little before the issue time, in case the device's clock runs behind ours; the guard
		// makes extra points harmless.
		from := row.EarliestIssuedAt.Time.Add(-backfillSkew).UnixMilli()
		to := time.Now().Add(backfillSkew).UnixMilli()
		_, err = i.scanKey(ctx, dev.ID.ID, protocol.KeyCmdResult, from, to, func(pt tbclient.TSValue) error {
			res, err := protocol.ParseCmdResult(pt.Value)
			if err != nil {
				i.cfg.Logf("ingest: command reconcile: %s: unreadable cmdResult at %d: %v", deviceName, pt.TS, err)
				return nil // one bad point must not hide the good ones after it
			}
			cmd, applied, err := i.cfg.Control.ApplyResult(ctx, deviceName, res)
			if err != nil {
				return err // the database is failing; stop and let the next pass try again
			}
			if applied {
				recorded++
				i.cfg.Logf("ingest: %s: command %s -> %s (recovered from ThingsBoard history)", deviceName, res.CommandID, cmd.Status)
			}
			return nil
		})
		if err != nil && ctx.Err() == nil {
			i.cfg.Logf("ingest: command reconcile: %s: %v", deviceName, err)
		}
	}
	return recorded
}
