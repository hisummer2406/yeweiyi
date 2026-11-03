// Package ingest consumes device data from ThingsBoard and routes it into the business layer.
//
// What it does NOT do: **it does not persist continuous telemetry.** The 30-second tank snapshots
// stay in ThingsBoard and are read back from there on demand (internal/tbclient). Copying them into
// the business PostgreSQL would create a second time-series store — the duplication GitHub issue #10
// forbids. (Hourly aggregates of them are a separate, planned feature: issue #30.)
//
// What crosses the boundary into the business database:
//
//   - command results, which close the control state machine (internal/control);
//   - stock events (出入库) and alarms — business records whose long-term source of truth is the
//     business database, not ThingsBoard (CLAUDE.md constraint 5, issue #29). See records.go;
//   - alarms are additionally fanned out live to operators.
//
// Everything else is observed and dropped on purpose.
package ingest

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// AlarmChannel is the Redis pub/sub channel /ws/alarms fans out from. Kept identical to the one the
// legacy MQTT path publishes on so the WebSocket endpoint needs no change when the old stack goes.
const AlarmChannel = "alarms:live"

// Config wires an Ingester.
type Config struct {
	TB      *tbclient.Client
	Control *control.Service
	Redis   *redis.Client
	Queries *db.Queries

	// DeviceRefreshInterval controls how often the device list is re-read so newly bound devices
	// get a subscription. Defaults to 30s.
	DeviceRefreshInterval time.Duration
	// ResubscribeDelay is how long to wait before reopening a subscription that dropped.
	ResubscribeDelay time.Duration
	// RecordReconcileInterval is how often each device's recent stockEvent/alarm points are re-read
	// from ThingsBoard to fill what the live subscription missed. Defaults to 10m.
	RecordReconcileInterval time.Duration
	// RecordReconcileLookback is how far back each reconcile pass reads. It must reach back at least
	// as far as the longest a device can sit offline before replaying, and should not exceed
	// ThingsBoard's own retention. Defaults to 8 days (one day more than the 7-day telemetry TTL).
	RecordReconcileLookback time.Duration
	// OnlineStatusInterval is how often devices.is_online is re-synced from ThingsBoard's `active`
	// flag. Defaults to 30s. It cannot make detection faster than ThingsBoard's own inactivity
	// timeout, only shorten the extra delay on top of it.
	OnlineStatusInterval time.Duration
	// TankSettingsInterval is how often each device's reported tank configuration (client attributes)
	// is re-read into tank_settings (cloud#70). Defaults to 1m; a read also happens on every
	// (re)subscription, which is when a device that just reconnected republishes its attributes.
	TankSettingsInterval time.Duration
	// CommandReconcileInterval is how often open commands' results are re-read from ThingsBoard
	// (see commands.go). Defaults to 10s; it needs to be well inside the command validity window.
	CommandReconcileInterval time.Duration

	Logf func(format string, args ...any)
}

// Ingester maintains one live ThingsBoard telemetry subscription per device.
type Ingester struct {
	cfg Config

	mu     sync.Mutex
	active map[string]context.CancelFunc // deviceName -> stop its subscription
}

// New builds an Ingester.
func New(cfg Config) *Ingester {
	if cfg.DeviceRefreshInterval <= 0 {
		cfg.DeviceRefreshInterval = 30 * time.Second
	}
	if cfg.ResubscribeDelay <= 0 {
		cfg.ResubscribeDelay = 5 * time.Second
	}
	if cfg.RecordReconcileInterval <= 0 {
		cfg.RecordReconcileInterval = 10 * time.Minute
	}
	if cfg.RecordReconcileLookback <= 0 {
		cfg.RecordReconcileLookback = 8 * 24 * time.Hour
	}
	if cfg.OnlineStatusInterval <= 0 {
		cfg.OnlineStatusInterval = 30 * time.Second
	}
	if cfg.TankSettingsInterval <= 0 {
		cfg.TankSettingsInterval = time.Minute
	}
	if cfg.CommandReconcileInterval <= 0 {
		cfg.CommandReconcileInterval = defaultCommandReconcileInterval
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	return &Ingester{cfg: cfg, active: map[string]context.CancelFunc{}}
}

// Run keeps subscriptions in sync with the device list until ctx ends.
func (i *Ingester) Run(ctx context.Context) {
	go i.onlineLoop(ctx)
	go i.commandReconcileLoop(ctx)
	i.sync(ctx)

	ticker := time.NewTicker(i.cfg.DeviceRefreshInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			i.stopAll()
			return
		case <-ticker.C:
			i.sync(ctx)
		}
	}
}

// sync starts subscriptions for devices that lack one. It deliberately does not stop subscriptions
// for devices that disappeared from the list: an unbound device is a business-layer change, and
// tearing down its stream mid-command would lose the result that closes an in-flight command.
func (i *Ingester) sync(ctx context.Context) {
	names, err := i.cfg.Queries.ListAllDeviceNames(ctx)
	if err != nil {
		i.cfg.Logf("ingest: list devices: %v", err)
		return
	}

	for _, name := range names {
		i.mu.Lock()
		_, running := i.active[name]
		i.mu.Unlock()
		if running {
			continue
		}
		i.StartDevice(ctx, name)
	}
}

// StartDevice opens a subscription for one device and keeps it open, reconnecting on failure, until
// ctx ends. Safe to call for a device that already has one — the duplicate is ignored.
func (i *Ingester) StartDevice(parent context.Context, deviceName string) {
	i.mu.Lock()
	if _, running := i.active[deviceName]; running {
		i.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(parent)
	i.active[deviceName] = cancel
	i.mu.Unlock()

	go func() {
		defer func() {
			i.mu.Lock()
			delete(i.active, deviceName)
			i.mu.Unlock()
			cancel()
		}()

		for ctx.Err() == nil {
			if err := i.subscribeOnce(ctx, deviceName); err != nil && ctx.Err() == nil {
				i.cfg.Logf("ingest: %s: %v; retrying in %s", deviceName, err, i.cfg.ResubscribeDelay)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(i.cfg.ResubscribeDelay):
			}
		}
	}()
}

// StopDevice ends the device's subscription and its reconcile loop, if this process runs one. Used by
// the device purge (cloud#83): sync never stops a subscription on its own, and the purged device must
// not keep one. The SQL fence already refuses the writes; this ends the traffic.
func (i *Ingester) StopDevice(deviceName string) {
	i.mu.Lock()
	cancel, ok := i.active[deviceName]
	i.mu.Unlock()
	if ok {
		cancel()
	}
}

// subscribeOnce runs a single subscription until it fails or ctx ends.
func (i *Ingester) subscribeOnce(ctx context.Context, deviceName string) error {
	dev, err := i.cfg.TB.GetDeviceByName(ctx, deviceName)
	if err != nil {
		if errors.Is(err, tbclient.ErrNotFound) {
			// Bound in the business database but never provisioned in ThingsBoard. Retrying is
			// right — provisioning may simply not have happened yet.
			return err
		}
		return err
	}

	// nil keys: subscribe to everything this device reports. The ingester must see alarm and
	// cmdResult events without knowing in advance how many tanks a device has.
	stream, err := i.cfg.TB.SubscribeTelemetry(ctx, dev.ID.ID, nil)
	if err != nil {
		return err
	}
	defer stream.Close()

	i.cfg.Logf("ingest: subscribed to %s", deviceName)

	// Subscribe first, then reconcile: records that land between the two are caught by one or the
	// other, and the idempotent writes make catching them twice harmless.
	rctx, stopReconcile := context.WithCancel(ctx)
	defer stopReconcile()
	go i.reconcileLoop(rctx, deviceName, dev.ID.ID)
	go i.tankSettingsLoop(rctx, deviceName, dev.ID.ID)

	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-stream.Err:
			return err
		case update, ok := <-stream.Updates:
			if !ok {
				return errors.New("telemetry stream closed")
			}
			i.HandleUpdate(ctx, deviceName, update)
		}
	}
}

// HandleUpdate routes one telemetry batch. Exported so tests can drive it directly without a live
// subscription.
//
// Note what is absent: there is no branch that writes tank readings anywhere. Snapshot keys arrive
// here on every 30-second cycle and are intentionally ignored — see the package comment.
//
// A batch carries only the newest point per key, so a burst of events can reach here as one. That is
// why the live path is not the only path: reconcileLoop re-reads what it may have skipped.
func (i *Ingester) HandleUpdate(ctx context.Context, deviceName string, u tbclient.TelemetryUpdate) {
	if raw, ok := u.Values[protocol.KeyStockEvent]; ok {
		i.handleStockEvent(ctx, deviceName, u.EnvTS(protocol.KeyStockEvent), raw)
	}
	if raw, ok := u.Values[protocol.KeyCmdResult]; ok {
		i.handleCmdResult(ctx, deviceName, raw)
	}
	if raw, ok := u.Values[protocol.KeyAlarm]; ok {
		i.handleAlarm(ctx, deviceName, u.EnvTS(protocol.KeyAlarm), raw)
	}
	if raw, ok := u.Values[protocol.KeyShiftEvent]; ok {
		i.saveRecord(ctx, deviceName, protocol.KeyShiftEvent, u.EnvTS(protocol.KeyShiftEvent), raw, SaveShiftEvent)
	}
	if raw, ok := u.Values[protocol.KeyStockRecord]; ok {
		i.saveRecord(ctx, deviceName, protocol.KeyStockRecord, u.EnvTS(protocol.KeyStockRecord), raw, SaveStockRecord)
	}
}

// saveRecord writes a shift or stock record seen live. A failure is not fatal: reconcileLoop re-reads
// the point from ThingsBoard on its next pass.
func (i *Ingester) saveRecord(ctx context.Context, deviceName, key string, envTS int64, raw any,
	save func(context.Context, *db.Queries, string, int64, any) error) {
	if i.cfg.Queries == nil {
		return
	}
	if err := save(ctx, i.cfg.Queries, deviceName, envTS, raw); err != nil {
		i.cfg.Logf("ingest: %s: save %s: %v", deviceName, key, err)
	}
}

func (i *Ingester) handleCmdResult(ctx context.Context, deviceName string, raw any) {
	res, err := protocol.ParseCmdResult(raw)
	if err != nil {
		i.cfg.Logf("ingest: %s: bad cmdResult: %v", deviceName, err)
		return
	}
	if i.cfg.Control == nil {
		return
	}

	cmd, applied, err := i.cfg.Control.ApplyResult(ctx, deviceName, res)
	if err != nil {
		// A database failure, not "unknown command": the result is valid and unrecorded. It is not
		// lost — commandReconcileLoop reads it back from ThingsBoard until the command's window closes.
		i.cfg.Logf("ingest: %s: apply cmdResult %s: %v (will be retried from ThingsBoard history)", deviceName, res.CommandID, err)
		return
	}
	if !applied {
		// Unknown command, or one already in a terminal state. Both are normal — a device may
		// redeliver a result, and a restored database may not know the command at all.
		i.cfg.Logf("ingest: %s: cmdResult %s did not apply (unknown or already final)", deviceName, res.CommandID)
		return
	}
	i.cfg.Logf("ingest: %s: command %s -> %s", deviceName, res.CommandID, cmd.Status)
}

func (i *Ingester) handleStockEvent(ctx context.Context, deviceName string, envTS int64, raw any) {
	if i.cfg.Queries == nil {
		return
	}
	if err := SaveStockEvent(ctx, i.cfg.Queries, deviceName, envTS, raw); err != nil {
		// Not fatal: reconcileLoop re-reads this point from ThingsBoard on its next pass.
		i.cfg.Logf("ingest: %s: save stockEvent: %v", deviceName, err)
	}
}

// handleAlarm stores an alarm and fans it out to connected operators. The write comes first and its
// failure does not stop the fan-out: a live notification is worth more than a clean log line, and
// reconcileLoop retries the write.
func (i *Ingester) handleAlarm(ctx context.Context, deviceName string, envTS int64, raw any) {
	var ev protocol.AlarmEvent
	if i.cfg.Queries != nil {
		saved, ok, err := SaveAlarmEvent(ctx, i.cfg.Queries, deviceName, envTS, raw)
		if err != nil {
			i.cfg.Logf("ingest: %s: save alarm: %v", deviceName, err)
		}
		if !ok {
			i.cfg.Logf("ingest: %s: bad alarm kept in record_rejects", deviceName)
			return
		}
		ev = saved
	} else {
		var err error
		if ev, err = protocol.ParseAlarmEvent(raw); err != nil {
			i.cfg.Logf("ingest: %s: bad alarm: %v", deviceName, err)
			return
		}
	}
	if i.cfg.Redis == nil {
		return
	}

	fanout, err := json.Marshal(map[string]any{
		"deviceName": deviceName,
		"alarmId":    ev.AlarmID,
		"tank":       ev.Tank,
		"type":       ev.Type,
		"severity":   ev.Severity,
		"status":     ev.Status,
		"msg":        ev.Text,
		"value":      ev.Value,
		"tsMs":       ev.TSMs,
	})
	if err != nil {
		i.cfg.Logf("ingest: %s: marshal alarm fanout: %v", deviceName, err)
		return
	}
	if err := i.cfg.Redis.Publish(ctx, AlarmChannel, fanout).Err(); err != nil {
		i.cfg.Logf("ingest: %s: publish alarm fanout: %v", deviceName, err)
	}
}

func (i *Ingester) stopAll() {
	i.mu.Lock()
	defer i.mu.Unlock()
	for _, cancel := range i.active {
		cancel()
	}
}

// ActiveDevices reports which devices currently have a live subscription, for tests and diagnostics.
func (i *Ingester) ActiveDevices() []string {
	i.mu.Lock()
	defer i.mu.Unlock()
	names := make([]string, 0, len(i.active))
	for name := range i.active {
		names = append(names, name)
	}
	return names
}
