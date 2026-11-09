// Package purge carries out a platform administrator's complete deletion of one device and all of its
// cloud history (cloud#83, contract docs/device-purge-contract.md).
//
// The task row in device_purges is created by the API in the same transaction that locks the device;
// from that moment the SQL fence (device_record_writable) keeps every record writer out. This package
// then works the task through its steps:
//
//	thingsboard → business → verify → done
//
// ThingsBoard and PostgreSQL cannot share a transaction, so each step is idempotent and the task row
// records which step comes next. A failure parks the task as failed at that step; asking again resumes
// it there, and a restart resumes every task still marked running.
package purge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// Step names, as stored in device_purges.step.
const (
	StepThingsBoard = "thingsboard"
	StepBusiness    = "business"
	StepVerify      = "verify"
	StepDone        = "done"
)

// Status values, as stored in device_purges.status.
const (
	StatusRunning   = "running"
	StatusFailed    = "failed"
	StatusCompleted = "completed"
)

// TB is the part of tbclient the purge needs; tests substitute a failing one.
type TB interface {
	GetDeviceByName(ctx context.Context, name string) (tbclient.Device, error)
	TimeseriesKeys(ctx context.Context, deviceID string) ([]string, error)
	DeleteAllTimeseries(ctx context.Context, deviceID string, keys []string) error
	DeleteDevice(ctx context.Context, deviceID string) error
}

type Config struct {
	Pool    *pgxpool.Pool
	Queries *db.Queries
	TB      TB
	// StopDevice tears down this process's live subscription for the device (ingest.Ingester). The SQL
	// fence already keeps its writes out; stopping it just ends the useless traffic. May be nil.
	StopDevice func(deviceName string)
	// SettleTimeout bounds how long the ThingsBoard step waits for deleted keys to stop being listed.
	// Zero means 30s.
	SettleTimeout time.Duration
	Logf          func(string, ...any)
}

// maxBusinessRounds bounds how often verify may send a task back to the business step in one run.
const maxBusinessRounds = 3

type Runner struct {
	cfg Config

	roundsMu sync.Mutex
	round    map[int64]int

	mu      sync.Mutex
	running map[int64]bool
	wg      sync.WaitGroup
}

func New(cfg Config) *Runner {
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	if cfg.SettleTimeout == 0 {
		cfg.SettleTimeout = 30 * time.Second
	}
	return &Runner{cfg: cfg, running: map[int64]bool{}, round: map[int64]int{}}
}

// Start runs the task in the background unless this process is already running it.
func (r *Runner) Start(ctx context.Context, id int64) {
	r.mu.Lock()
	if r.running[id] {
		r.mu.Unlock()
		return
	}
	r.running[id] = true
	r.wg.Add(1)
	r.mu.Unlock()

	go func() {
		defer r.wg.Done()
		defer func() {
			r.mu.Lock()
			delete(r.running, id)
			r.mu.Unlock()
		}()
		if err := r.Run(ctx, id); err != nil {
			r.cfg.Logf("purge: task %d: %v", id, err)
		}
	}()
}

// Wait blocks until every task started by Start has returned. For tests and shutdown.
func (r *Runner) Wait() { r.wg.Wait() }

// Resume restarts every task a previous process left running (it was interrupted mid-step). Failed
// tasks wait for an administrator to ask again.
func (r *Runner) Resume(ctx context.Context) error {
	tasks, err := r.cfg.Queries.ListRunningDevicePurges(ctx)
	if err != nil {
		return err
	}
	for _, t := range tasks {
		r.cfg.Logf("purge: resuming task %d (%s) at step %s", t.ID, t.DeviceName, t.Step)
		r.Start(ctx, t.ID)
	}
	return nil
}

// Run works one task from its current step to the end, synchronously. A step failure parks the task
// as failed and is returned; a task that is not running (failed or completed) is left alone.
func (r *Runner) Run(ctx context.Context, id int64) error {
	task, err := r.cfg.Queries.GetDevicePurge(ctx, id)
	if err != nil {
		return err
	}
	if task.Status != StatusRunning {
		return nil
	}
	if r.cfg.StopDevice != nil {
		r.cfg.StopDevice(task.DeviceName)
	}

	for task.Status == StatusRunning && task.Step != StepDone {
		var next db.DevicePurge
		switch task.Step {
		case StepThingsBoard:
			next, err = r.thingsBoard(ctx, task)
		case StepBusiness:
			next, err = r.business(ctx, task)
		case StepVerify:
			next, err = r.verify(ctx, task)
		default:
			err = fmt.Errorf("unknown step %q", task.Step)
		}
		if err != nil {
			r.fail(task, err)
			return fmt.Errorf("%s: %w", task.Step, err)
		}
		task = next
	}
	if task.Status == StatusCompleted {
		r.cfg.Logf("purge: task %d: device %s completely deleted", task.ID, task.DeviceName)
	}
	return nil
}

// fail records the failure on a fresh context: the step may have failed because ctx was cancelled
// (shutdown), and the task must still say where it stopped. A shutdown-interrupted task is resumed on
// the next start only if it stays running, so a cancelled context does not mark it failed.
func (r *Runner) fail(task db.DevicePurge, cause error) {
	if errors.Is(cause, context.Canceled) {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	msg := cause.Error()
	if len(msg) > 2000 {
		msg = msg[:2000]
	}
	if _, err := r.cfg.Queries.FailDevicePurge(ctx, db.FailDevicePurgeParams{
		ID: task.ID, Error: pgtype.Text{String: msg, Valid: true},
	}); err != nil {
		r.cfg.Logf("purge: task %d: record failure %q: %v", task.ID, msg, err)
	}
}

func counts(m map[string]int64) []byte {
	b, _ := json.Marshal(m)
	return b
}

// thingsBoard deletes every timeseries key of the device, checks nothing is listed any more, then
// deletes the device entity (credentials and attributes go with it, so the device cannot report
// again). A device ThingsBoard does not know — never provisioned, or deleted by an earlier attempt —
// is done.
func (r *Runner) thingsBoard(ctx context.Context, task db.DevicePurge) (db.DevicePurge, error) {
	got := map[string]int64{"tbKeys": 0, "tbDevice": 0}
	dev, err := r.cfg.TB.GetDeviceByName(ctx, task.DeviceName)
	switch {
	case errors.Is(err, tbclient.ErrNotFound):
	case err != nil:
		return task, fmt.Errorf("look up device: %w", err)
	default:
		keys, err := r.cfg.TB.TimeseriesKeys(ctx, dev.ID.ID)
		if err != nil {
			return task, err
		}
		if err := r.cfg.TB.DeleteAllTimeseries(ctx, dev.ID.ID, keys); err != nil {
			return task, err
		}
		if err := r.waitNoKeys(ctx, dev.ID.ID); err != nil {
			return task, err
		}
		if err := r.cfg.TB.DeleteDevice(ctx, dev.ID.ID); err != nil && !errors.Is(err, tbclient.ErrNotFound) {
			return task, fmt.Errorf("delete device: %w", err)
		}
		got["tbKeys"] = int64(len(keys))
		got["tbDevice"] = 1
	}
	if _, err := r.cfg.TB.GetDeviceByName(ctx, task.DeviceName); !errors.Is(err, tbclient.ErrNotFound) {
		if err == nil {
			err = errors.New("device is still listed after deletion")
		}
		return task, fmt.Errorf("confirm device gone: %w", err)
	}
	return r.cfg.Queries.AdvanceDevicePurge(ctx, db.AdvanceDevicePurgeParams{
		ID: task.ID, Step: StepThingsBoard, NextStep: StepBusiness, Counts: counts(got),
	})
}

func (r *Runner) waitNoKeys(ctx context.Context, deviceID string) error {
	deadline := time.Now().Add(r.cfg.SettleTimeout)
	for {
		left, err := r.cfg.TB.TimeseriesKeys(ctx, deviceID)
		if err != nil {
			return err
		}
		if len(left) == 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("%d timeseries key(s) still listed after deletion", len(left))
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// business deletes the device's rows from every business table in one transaction, together with the
// step advance, so the counts on the task always describe a deletion that committed.
func (r *Runner) business(ctx context.Context, task db.DevicePurge) (db.DevicePurge, error) {
	tx, err := r.cfg.Pool.Begin(ctx)
	if err != nil {
		return task, err
	}
	defer tx.Rollback(ctx)
	q := r.cfg.Queries.WithTx(tx)
	name := task.DeviceName

	got := map[string]int64{}
	for _, del := range []struct {
		key string
		fn  func(context.Context, string) (int64, error)
	}{
		{"stockEvents", q.PurgeStockEvents},
		{"alarmEvents", q.PurgeAlarmEvents},
		{"shiftRecords", q.PurgeShiftRecords},
		{"stockRecords", q.PurgeStockRecords},
		{"recordRejects", q.PurgeRecordRejects},
		{"recordBackfills", q.PurgeRecordBackfills},
		{"telemetryHourly", q.PurgeTelemetryHourly},
		{"auditLog", q.PurgeAuditLog},
	} {
		n, err := del.fn(ctx, name)
		if err != nil {
			return task, fmt.Errorf("%s: %w", del.key, err)
		}
		got[del.key] = n
	}

	// The device row goes last; its foreign keys cascade to these four, so count them first.
	cascades, err := q.CountDeviceCascades(ctx, name)
	if err != nil {
		return task, err
	}
	got["controlCommands"] = cascades.ControlCommands
	got["tankGrants"] = cascades.TankGrants
	got["userDevices"] = cascades.UserDevices
	got["pairingSecrets"] = cascades.PairingSecrets
	got["tankSettings"] = cascades.TankSettings
	got["tankCalibration"] = cascades.TankCalibration
	got["deviceSettings"] = cascades.DeviceSettings
	if got["devices"], err = q.PurgeDeviceRow(ctx, name); err != nil {
		return task, fmt.Errorf("devices: %w", err)
	}

	// A second pass (verify sent the task back) adds to what the first one deleted.
	var before map[string]int64
	_ = json.Unmarshal(task.Counts, &before)
	for k, v := range got {
		got[k] = v + before[k]
	}
	next, err := q.AdvanceDevicePurge(ctx, db.AdvanceDevicePurgeParams{
		ID: task.ID, Step: StepBusiness, NextStep: StepVerify, Counts: counts(got),
	})
	if err != nil {
		return task, err
	}
	return next, tx.Commit(ctx)
}

// rounds counts the verify → business loop-backs of a task in this process.
func (r *Runner) rounds(id int64) int {
	r.roundsMu.Lock()
	defer r.roundsMu.Unlock()
	r.round[id]++
	return r.round[id] - 1
}

// verify checks the whole list once more before the task may say completed.
func (r *Runner) verify(ctx context.Context, task db.DevicePurge) (db.DevicePurge, error) {
	left, err := r.cfg.Queries.CountDeviceRemnants(ctx, task.DeviceName)
	if err != nil {
		return task, err
	}
	if left != 0 {
		// Something slipped in after the business step committed — an audit row about the device, a
		// command issued in the instant before the fence check. Delete again, a bounded number of times.
		if r.rounds(task.ID) >= maxBusinessRounds {
			return task, fmt.Errorf("%d business row(s) still reference the device", left)
		}
		return r.cfg.Queries.AdvanceDevicePurge(ctx, db.AdvanceDevicePurgeParams{
			ID: task.ID, Step: StepVerify, NextStep: StepBusiness, Counts: []byte("{}"),
		})
	}
	if _, err := r.cfg.TB.GetDeviceByName(ctx, task.DeviceName); !errors.Is(err, tbclient.ErrNotFound) {
		if err == nil {
			err = errors.New("ThingsBoard still has the device")
		}
		return task, err
	}
	return r.cfg.Queries.CompleteDevicePurge(ctx, task.ID)
}
