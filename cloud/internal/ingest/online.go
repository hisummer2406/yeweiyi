package ingest

import (
	"context"
	"time"

	"yeweiyi-cloud/internal/store/db"
)

// onlineLoop keeps devices.is_online in step with ThingsBoard until ctx ends.
//
// Why polling and not the telemetry subscription: ThingsBoard reports connectivity as the `active`
// device attribute, which the per-device telemetry WebSocket does not carry, and a deviceInfos page
// answers a thousand devices in one request (see tbclient.DeviceActivity).
func (i *Ingester) onlineLoop(ctx context.Context) {
	i.syncOnline(ctx)

	ticker := time.NewTicker(i.cfg.OnlineStatusInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			i.syncOnline(ctx)
		}
	}
}

// syncOnline runs one pass. It returns how many rows changed.
//
// A failed listing changes nothing: DeviceActivity returns no map on error, so an unreachable
// ThingsBoard leaves every device in its last known state rather than flipping the fleet offline.
func (i *Ingester) syncOnline(ctx context.Context) int64 {
	active, err := i.cfg.TB.DeviceActivity(ctx)
	if err != nil {
		i.cfg.Logf("ingest: online status: %v", err)
		return 0
	}

	names := make([]string, 0, len(active))
	flags := make([]bool, 0, len(active))
	for name, a := range active {
		names = append(names, name)
		flags = append(flags, a)
	}

	changed, err := i.cfg.Queries.SyncDeviceOnlineStatus(ctx, db.SyncDeviceOnlineStatusParams{
		DeviceNames: names,
		Actives:     flags,
	})
	if err != nil {
		i.cfg.Logf("ingest: online status: sync: %v", err)
		return 0
	}

	// A device deleted from ThingsBoard no longer appears in the listing, so it needs its own pass
	// or it would stay online forever.
	gone, err := i.cfg.Queries.MarkDevicesOfflineExcept(ctx, names)
	if err != nil {
		i.cfg.Logf("ingest: online status: mark missing offline: %v", err)
		return changed
	}
	return changed + gone
}

// SyncOnlineForTest runs one pass synchronously so tests need not wait out the ticker.
func (i *Ingester) SyncOnlineForTest(ctx context.Context) int64 { return i.syncOnline(ctx) }
