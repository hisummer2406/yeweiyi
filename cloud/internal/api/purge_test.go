package api_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/purge"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// fakeTB stands in for ThingsBoard in the purge tests: it holds devices with their keys and can be made
// to fail, which a real ThingsBoard cannot be on demand. The real deletion calls are covered against a
// live ThingsBoard by tbclient's TestPurge_DeletesEveryKeyThenTheDevice.
type fakeTB struct {
	mu      sync.Mutex
	devices map[string][]string // name → timeseries keys
	failing error
}

func newFakeTB(names ...string) *fakeTB {
	f := &fakeTB{devices: map[string][]string{}}
	for _, n := range names {
		f.devices[n] = []string{"tank1.oilLevelMm", "tank2.oilLevelMm", "tank3.oilLevelMm", "alarm", "stockEvent"}
	}
	return f
}

func (f *fakeTB) fail(err error) { f.mu.Lock(); f.failing = err; f.mu.Unlock() }

func (f *fakeTB) has(name string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, ok := f.devices[name]
	return ok
}

func (f *fakeTB) GetDeviceByName(_ context.Context, name string) (tbclient.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failing != nil {
		return tbclient.Device{}, f.failing
	}
	if _, ok := f.devices[name]; !ok {
		return tbclient.Device{}, tbclient.ErrNotFound
	}
	d := tbclient.Device{Name: name}
	d.ID.ID = "id-" + name
	return d, nil
}

func (f *fakeTB) TimeseriesKeys(_ context.Context, id string) ([]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.devices[id[len("id-"):]]...), nil
}

func (f *fakeTB) DeleteAllTimeseries(_ context.Context, id string, _ []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.devices[id[len("id-"):]]; ok {
		f.devices[id[len("id-"):]] = nil
	}
	return nil
}

func (f *fakeTB) DeleteDevice(_ context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.devices, id[len("id-"):])
	return nil
}

type purgeEnv struct {
	*testEnv
	tb     *fakeTB
	runner *purge.Runner
	admin  string
}

func newPurgeEnv(t *testing.T, tb *fakeTB) *purgeEnv {
	t.Helper()
	pe := &purgeEnv{tb: tb}
	pe.testEnv = newTestEnvWith(t, func(d *api.Deps) {
		pe.runner = purge.New(purge.Config{Pool: d.Pool, Queries: d.Queries, TB: tb, Logf: t.Logf})
		d.Purger = pe.runner
	})
	pe.admin = pe.otherUserToken(control.RolePlatformAdmin)
	return pe
}

type purgeBody struct {
	Purge struct {
		ID         int64            `json:"id"`
		DeviceName string           `json:"deviceName"`
		Status     string           `json:"status"`
		Step       string           `json:"step"`
		Error      *string          `json:"error"`
		Counts     map[string]int64 `json:"counts"`
		CustomerID *int64           `json:"customerId"`
		StationID  *int64           `json:"stationId"`
		FinishedAt *time.Time       `json:"finishedAt"`
	} `json:"purge"`
	Code string `json:"code"`
}

func (pe *purgeEnv) purge(token, device, confirm, reason string) (purgeBody, int) {
	pe.t.Helper()
	rec := pe.do("POST", "/api/devices/"+device+"/purge", []byte(token),
		jsonBody(pe.t, map[string]string{"confirmDeviceName": confirm, "reason": reason}))
	var out purgeBody
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return out, rec.Code
}

func (pe *purgeEnv) getPurge(id int64) purgeBody {
	pe.t.Helper()
	rec := pe.do("GET", fmt.Sprintf("/api/device-purges/%d", id), []byte(pe.admin), nil)
	if rec.Code != http.StatusOK {
		pe.t.Fatalf("GET purge %d: %d %s", id, rec.Code, rec.Body.String())
	}
	var out purgeBody
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		pe.t.Fatal(err)
	}
	return out
}

func (pe *purgeEnv) cleanupPurges(names ...string) {
	pe.t.Cleanup(func() {
		ctx := context.Background()
		for _, n := range names {
			for _, table := range []string{"stock_events", "alarm_events", "record_rejects", "record_backfills", "telemetry_hourly", "audit_log", "device_purges"} {
				pe.pool.Exec(ctx, "DELETE FROM "+table+" WHERE device_name = $1", n)
			}
			pe.pool.Exec(ctx, `DELETE FROM devices WHERE device_name = $1`, n)
		}
	})
}

func (pe *purgeEnv) count(sql string, args ...any) int64 {
	pe.t.Helper()
	var n int64
	if err := pe.pool.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		pe.t.Fatalf("%s: %v", sql, err)
	}
	return n
}

// seedHistory writes one of everything the purge must remove: records under two customers (the device
// changed hands), three tanks of hourly summaries, a command, an audit row, a tank grant, a legacy
// binding and a pairing secret.
func (pe *purgeEnv) seedHistory(device string, oldCustomer, customer, staffID int64) {
	pe.t.Helper()
	ctx := context.Background()
	q := pe.queries
	now := time.Now().UnixMilli()

	pe.pool.Exec(ctx, `UPDATE devices SET customer_id = $2 WHERE device_name = $1`, device, oldCustomer)
	for i, cust := range []int64{oldCustomer, customer} {
		if i == 1 {
			pe.pool.Exec(ctx, `UPDATE devices SET customer_id = $2 WHERE device_name = $1`, device, cust)
		}
		ts := now - int64(10-i)*3600_000
		if err := q.UpsertStockEvent(ctx, db.UpsertStockEventParams{DeviceName: device, EventID: fmt.Sprintf("s%d", i),
			OccurredMs: ts, EnvTs: ts, Payload: []byte(`{"tank":1,"direction":"IN"}`)}); err != nil {
			pe.t.Fatal(err)
		}
		if err := q.InsertAlarmEvent(ctx, db.InsertAlarmEventParams{DeviceName: device, AlarmID: fmt.Sprintf("a%d", i),
			Status: "ACTIVE", OccurredMs: ts, EnvTs: ts, Payload: []byte(`{"tank":2}`)}); err != nil {
			pe.t.Fatal(err)
		}
		if err := q.InsertRecordReject(ctx, db.InsertRecordRejectParams{Kind: "stock", DeviceName: device, EnvTs: ts,
			Reason: "unparsable", Raw: fmt.Sprintf("raw-%d", i)}); err != nil {
			pe.t.Fatal(err)
		}
	}
	for tank := int32(1); tank <= 3; tank++ {
		if err := q.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{DeviceName: device, Tank: tank, Field: "oilLevelMm",
			HourStartMs: (now/3600_000 - 5) * 3600_000, AvgValue: 1, MinValue: 1, MaxValue: 1, Samples: 120}); err != nil {
			pe.t.Fatal(err)
		}
	}
	if err := q.MarkRecordBackfill(ctx, device); err != nil {
		pe.t.Fatal(err)
	}
	for _, sql := range []string{
		`INSERT INTO control_commands (command_id, device_name, method, operator, reason, expires_at, customer_id)
		 VALUES (gen_random_uuid(), $1, 'alarm.mute', 'tester', 'seed', now() + interval '1 hour', $2)`,
		`INSERT INTO audit_log (actor, action, device_name, outcome, detail, customer_id)
		 VALUES ('tester', 'command.issue', $1, 'allowed', '{}', $2)`,
	} {
		if _, err := pe.pool.Exec(ctx, sql, device, customer); err != nil {
			pe.t.Fatalf("%s: %v", sql, err)
		}
	}
	if _, err := pe.pool.Exec(ctx, `INSERT INTO user_tank_grants (user_id, device_name, tank) VALUES ($1, $2, 1), ($1, $2, 2)`, staffID, device); err != nil {
		pe.t.Fatal(err)
	}
	if _, err := pe.pool.Exec(ctx, `INSERT INTO user_devices (user_id, device_name) VALUES ($1, $2)`, staffID, device); err != nil {
		pe.t.Fatal(err)
	}
	if _, err := pe.pool.Exec(ctx, `INSERT INTO device_pairing_secrets (device_name, secret_hash) VALUES ($1, 'x')`, device); err != nil {
		pe.t.Fatal(err)
	}
	if _, err := pe.pool.Exec(ctx, `INSERT INTO tank_settings (device_name, tank, reported_at) VALUES ($1, 1, now()), ($1, 2, now())`, device); err != nil {
		pe.t.Fatal(err)
	}
	if _, err := pe.pool.Exec(ctx, `INSERT INTO tank_calibration (device_name, tank, height_mm, volume_l, reported_at) VALUES ($1, 1, '{0,100}', '{0,50}', now())`, device); err != nil {
		pe.t.Fatal(err)
	}
}

// historyRows counts every business row that names the device (the purge's own audit row excluded).
func (pe *purgeEnv) historyRows(device string) int64 {
	pe.t.Helper()
	var total int64
	for _, table := range []string{"stock_events", "alarm_events", "record_rejects", "record_backfills", "telemetry_hourly",
		"control_commands", "user_tank_grants", "user_devices", "device_pairing_secrets", "tank_settings", "tank_calibration", "devices"} {
		total += pe.count("SELECT COUNT(*) FROM "+table+" WHERE device_name = $1", device)
	}
	return total + pe.count(`SELECT COUNT(*) FROM audit_log WHERE device_name = $1 AND action <> 'device.purge'`, device)
}

func TestDevicePurge_RefusesEveryoneButPlatformAdmin(t *testing.T) {
	pe := newPurgeEnv(t, newFakeTB())
	customer := pe.newCustomer("purge-deny")
	device := "ZCT" + newBindDeviceName(t)
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)
	adminTok, _ := pe.userIn(control.RoleCustomerAdmin, customer)
	staffTok, _ := pe.userIn(control.RoleCustomerUser, customer)

	for name, tok := range map[string]string{"customer_admin": adminTok, "customer_user": staffTok} {
		rec := pe.do("POST", "/api/devices/"+device+"/purge", []byte(tok), []byte(`not even json`))
		if rec.Code != http.StatusForbidden || rec.Body.String() != `{"error":"not authorized"}` {
			t.Errorf("%s: %d %s, want 403 not authorized before the body is read", name, rec.Code, rec.Body.String())
		}
		if rec := pe.do("GET", "/api/device-purges?deviceName="+device, []byte(tok), nil); rec.Code != http.StatusForbidden {
			t.Errorf("%s list: %d, want 403", name, rec.Code)
		}
	}
	if n := pe.count(`SELECT COUNT(*) FROM device_purges WHERE device_name = $1`, device); n != 0 {
		t.Fatalf("%d task(s) created by refused callers", n)
	}
	if n := pe.count(`SELECT COUNT(*) FROM devices WHERE device_name = $1`, device); n != 1 {
		t.Fatal("device touched by refused callers")
	}
	if n := pe.count(`SELECT COUNT(*) FROM audit_log WHERE action = 'device.purge' AND outcome = 'denied' AND customer_id = $1`, customer); n < 2 {
		t.Fatalf("denied audits = %d, want at least 2", n)
	}
}

func TestDevicePurge_ValidatesRequest(t *testing.T) {
	pe := newPurgeEnv(t, newFakeTB())
	customer := pe.newCustomer("purge-val")
	device := "0012" + newBindDeviceName(t)
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)

	if body, code := pe.purge(pe.admin, device, device[2:], "r"); code != http.StatusBadRequest || body.Code != "confirm_mismatch" {
		t.Errorf("dropped leading zeros: %d %q", code, body.Code)
	}
	if body, code := pe.purge(pe.admin, device, device, "   "); code != http.StatusBadRequest || body.Code != "reason_required" {
		t.Errorf("blank reason: %d %q", code, body.Code)
	}
	if body, code := pe.purge(pe.admin, device+"x", device+"x", "r"); code != http.StatusNotFound || body.Code != "device_not_found" {
		t.Errorf("missing device: %d %q", code, body.Code)
	}
	if n := pe.count(`SELECT COUNT(*) FROM device_purges WHERE device_name = $1`, device); n != 0 {
		t.Fatalf("rejected requests created %d task(s)", n)
	}

	noTB := newTestEnv(t)
	rec := noTB.do("POST", "/api/devices/"+device+"/purge", []byte(noTB.otherUserToken(control.RolePlatformAdmin)),
		jsonBody(t, map[string]string{"confirmDeviceName": device, "reason": "r"}))
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("without ThingsBoard: %d, want 503", rec.Code)
	}
}

// TestDevicePurge_DeletesEverythingOfTheDeviceAndNothingElse is acceptance items 2 and 3 of cloud#83.
func TestDevicePurge_DeletesEverythingOfTheDeviceAndNothingElse(t *testing.T) {
	target := "ZCT" + newBindDeviceName(t)
	other := target + "-other"
	pe := newPurgeEnv(t, newFakeTB(target, other))
	ctx := context.Background()
	oldCustomer := pe.newCustomer("purge-old")
	customer := pe.newCustomer("purge-new")
	pe.cleanupPurges(target, other)
	pe.seedDeviceIn(target, customer)
	pe.seedDeviceIn(other, customer)
	_, staffID := pe.userIn(control.RoleCustomerUser, customer)
	var station int64
	if err := pe.pool.QueryRow(ctx, `INSERT INTO stations (customer_id, name) VALUES ($1, $2) RETURNING id`, customer, "站-"+target).Scan(&station); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pe.pool.Exec(context.Background(), `DELETE FROM stations WHERE id = $1`, station) })

	pe.seedHistory(target, oldCustomer, customer, staffID)
	pe.seedHistory(other, oldCustomer, customer, staffID)
	pe.pool.Exec(ctx, `UPDATE devices SET station_id = $2 WHERE device_name = $1`, target, station)
	otherBefore := pe.historyRows(other)

	body, code := pe.purge(pe.admin, target, target, "误登记")
	if code != http.StatusAccepted || body.Purge.Status != purge.StatusRunning {
		t.Fatalf("POST: %d %+v", code, body)
	}
	if body.Purge.CustomerID == nil || *body.Purge.CustomerID != customer || body.Purge.StationID == nil || *body.Purge.StationID != station {
		t.Errorf("snapshot of customer/station: %+v", body.Purge)
	}
	pe.runner.Wait()

	got := pe.getPurge(body.Purge.ID).Purge
	if got.Status != purge.StatusCompleted || got.Step != purge.StepDone || got.FinishedAt == nil {
		t.Fatalf("task after run: %+v", got)
	}
	want := map[string]int64{"stockEvents": 2, "alarmEvents": 2, "recordRejects": 2, "recordBackfills": 1, "telemetryHourly": 3,
		"auditLog": 1, "controlCommands": 1, "tankGrants": 2, "userDevices": 1, "pairingSecrets": 1, "tankSettings": 2, "tankCalibration": 1, "devices": 1, "tbKeys": 5, "tbDevice": 1}
	for k, v := range want {
		if got.Counts[k] != v {
			t.Errorf("counts[%s] = %d, want %d (all: %v)", k, got.Counts[k], v, got.Counts)
		}
	}

	if n := pe.historyRows(target); n != 0 {
		t.Errorf("%d business row(s) of the purged device remain", n)
	}
	if pe.tb.has(target) {
		t.Error("ThingsBoard still has the purged device")
	}
	if n := pe.historyRows(other); n != otherBefore {
		t.Errorf("other device: %d rows, had %d", n, otherBefore)
	}
	if !pe.tb.has(other) {
		t.Error("ThingsBoard lost the other device")
	}
	if n := pe.count(`SELECT COUNT(*) FROM stations WHERE id = $1`, station); n != 1 {
		t.Error("the station went with the device")
	}
	if n := pe.count(`SELECT COUNT(*) FROM devices WHERE station_id = $1`, station); n != 0 {
		t.Error("the station still has a device")
	}
	if n := pe.count(`SELECT COUNT(*) FROM users WHERE id = $1`, staffID); n != 1 {
		t.Error("the employee went with the device")
	}
	if n := pe.count(`SELECT COUNT(*) FROM audit_log WHERE device_name = $1 AND action = 'device.purge' AND outcome = 'allowed'`, target); n != 1 {
		t.Errorf("purge audit rows = %d, want 1", n)
	}

	// Done means gone: asking again is a 404, and the result is still found by name.
	if _, code := pe.purge(pe.admin, target, target, "again"); code != http.StatusNotFound {
		t.Errorf("purge after completion: %d, want 404", code)
	}
	rec := pe.do("GET", "/api/device-purges?deviceName="+target, []byte(pe.admin), nil)
	var list struct {
		Rows []struct {
			ID     int64  `json:"id"`
			Status string `json:"status"`
		} `json:"rows"`
	}
	json.Unmarshal(rec.Body.Bytes(), &list)
	if len(list.Rows) != 1 || list.Rows[0].ID != body.Purge.ID || list.Rows[0].Status != purge.StatusCompleted {
		t.Errorf("list by device: %s", rec.Body.String())
	}
}

// TestDevicePurge_FailureIsVisibleFencedAndResumable covers acceptance items 4 and 5: while a task is
// unfinished nothing reaches the device's tables and the management routes refuse it; a failed step
// is reported, not hidden, and asking again resumes the same task.
func TestDevicePurge_FailureIsVisibleFencedAndResumable(t *testing.T) {
	device := "ZCT" + newBindDeviceName(t)
	tb := newFakeTB(device)
	pe := newPurgeEnv(t, tb)
	ctx := context.Background()
	customer := pe.newCustomer("purge-fail")
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)
	var station int64
	pe.pool.QueryRow(ctx, `INSERT INTO stations (customer_id, name) VALUES ($1, $2) RETURNING id`, customer, "站-"+device).Scan(&station)
	t.Cleanup(func() { pe.pool.Exec(context.Background(), `DELETE FROM stations WHERE id = $1`, station) })

	tb.fail(errors.New("thingsboard: 503"))
	first, code := pe.purge(pe.admin, device, device, "作废")
	if code != http.StatusAccepted {
		t.Fatalf("POST: %d", code)
	}
	pe.runner.Wait()
	failed := pe.getPurge(first.Purge.ID).Purge
	if failed.Status != purge.StatusFailed || failed.Step != purge.StepThingsBoard || failed.Error == nil {
		t.Fatalf("after ThingsBoard failure: %+v", failed)
	}

	// Fenced: late records, summaries and backfill marks do not land.
	now := time.Now().UnixMilli()
	pe.queries.UpsertStockEvent(ctx, db.UpsertStockEventParams{DeviceName: device, EventID: "late", OccurredMs: now, EnvTs: now, Payload: []byte(`{}`)})
	pe.queries.InsertAlarmEvent(ctx, db.InsertAlarmEventParams{DeviceName: device, AlarmID: "late", Status: "ACTIVE", OccurredMs: now, EnvTs: now, Payload: []byte(`{}`)})
	pe.queries.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{DeviceName: device, Tank: 1, Field: "oilLevelMm", HourStartMs: now, Samples: 1})
	pe.queries.MarkRecordBackfill(ctx, device)
	if n := pe.historyRows(device); n != 1 { // the device row itself
		t.Errorf("fenced device gained %d row(s)", n-1)
	}
	names, _ := pe.queries.ListAllDeviceNames(ctx)
	for _, n := range names {
		if n == device {
			t.Error("a device being purged is still handed to ingest/rollup/backfill")
		}
	}

	// Fenced: the management routes refuse it.
	for what, rec := range map[string]func() int{
		"register": func() int {
			return pe.do("POST", "/api/devices", []byte(pe.admin), jsonBody(t, map[string]any{"deviceName": device, "customerId": customer})).Code
		},
		"pairing-secret": func() int {
			return pe.do("POST", "/api/devices/"+device+"/pairing-secret", []byte(pe.admin), jsonBody(t, map[string]any{"customerId": customer})).Code
		},
		"station": func() int {
			return pe.do("PUT", "/api/devices/"+device+"/station", []byte(pe.admin), jsonBody(t, map[string]any{"stationId": station})).Code
		},
		"customer": func() int {
			return pe.do("PUT", "/api/devices/"+device+"/customer", []byte(pe.admin), jsonBody(t, map[string]any{"customerId": customer})).Code
		},
	} {
		if code := rec(); code != http.StatusConflict {
			t.Errorf("%s while purging: %d, want 409", what, code)
		}
	}

	// Asking again resumes the same task and finishes it.
	tb.fail(nil)
	again, code := pe.purge(pe.admin, device, device, "作废")
	if code != http.StatusOK || again.Purge.ID != first.Purge.ID {
		t.Fatalf("retry: %d id=%d, want 200 and task %d", code, again.Purge.ID, first.Purge.ID)
	}
	pe.runner.Wait()
	if done := pe.getPurge(first.Purge.ID).Purge; done.Status != purge.StatusCompleted {
		t.Fatalf("after retry: %+v", done)
	}
}

// TestDevicePurge_ConcurrentRequestsMakeOneTask: the device row lock and the partial unique index leave
// exactly one task however many requests race.
func TestDevicePurge_ConcurrentRequestsMakeOneTask(t *testing.T) {
	device := "ZCT" + newBindDeviceName(t)
	tb := newFakeTB(device)
	tb.fail(errors.New("hold the task open"))
	pe := newPurgeEnv(t, tb)
	customer := pe.newCustomer("purge-race")
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)

	var wg sync.WaitGroup
	ids := make(chan int64, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			body, code := pe.purge(pe.admin, device, device, "race")
			if code != http.StatusOK && code != http.StatusAccepted {
				t.Errorf("code %d", code)
			}
			ids <- body.Purge.ID
		}()
	}
	wg.Wait()
	close(ids)
	pe.runner.Wait()
	seen := map[int64]bool{}
	for id := range ids {
		seen[id] = true
	}
	if len(seen) != 1 {
		t.Errorf("tasks returned: %v, want one", seen)
	}
	if n := pe.count(`SELECT COUNT(*) FROM device_purges WHERE device_name = $1`, device); n != 1 {
		t.Errorf("%d tasks in the table", n)
	}
}

// TestDevicePurge_RestartResumesRunningTasks: a task the process was working on when it stopped is
// picked up by the next process.
func TestDevicePurge_RestartResumesRunningTasks(t *testing.T) {
	device := "ZCT" + newBindDeviceName(t)
	tb := newFakeTB(device)
	pe := newPurgeEnv(t, tb)
	customer := pe.newCustomer("purge-restart")
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)

	// A task left running at the business step, as if the process died right after ThingsBoard.
	var id int64
	if err := pe.pool.QueryRow(context.Background(), `
		INSERT INTO device_purges (device_name, reason, requested_username, step, counts)
		VALUES ($1, 'restart', 'tester', 'business', '{"tbKeys":5,"tbDevice":1}') RETURNING id`, device).Scan(&id); err != nil {
		t.Fatal(err)
	}
	tb.DeleteDevice(context.Background(), "id-"+device)

	next := purge.New(purge.Config{Pool: pe.pool, Queries: pe.queries, TB: tb, Logf: t.Logf})
	if err := next.Resume(context.Background()); err != nil {
		t.Fatal(err)
	}
	next.Wait()
	got := pe.getPurge(id).Purge
	if got.Status != purge.StatusCompleted || got.Counts["tbKeys"] != 5 || got.Counts["devices"] != 1 {
		t.Fatalf("resumed task: %+v", got)
	}
}

// TestDevicePurge_ReRegisteredDeviceDoesNotGetItsHistoryBack is acceptance item 4's replay case: the
// same code registered again is a new device, and records generated before the purge finished — a
// device resending its local history — are dropped, while new ones land.
func TestDevicePurge_ReRegisteredDeviceDoesNotGetItsHistoryBack(t *testing.T) {
	device := "ZCT" + newBindDeviceName(t)
	pe := newPurgeEnv(t, newFakeTB(device))
	ctx := context.Background()
	customer := pe.newCustomer("purge-replay")
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)
	_, staffID := pe.userIn(control.RoleCustomerUser, customer)
	pe.seedHistory(device, customer, customer, staffID)

	body, _ := pe.purge(pe.admin, device, device, "重装")
	pe.runner.Wait()
	if got := pe.getPurge(body.Purge.ID).Purge; got.Status != purge.StatusCompleted {
		t.Fatalf("purge: %+v", got)
	}

	if rec := pe.do("POST", "/api/devices", []byte(pe.admin), jsonBody(t, map[string]any{"deviceName": device, "customerId": customer})); rec.Code != http.StatusCreated {
		t.Fatalf("re-register: %d %s", rec.Code, rec.Body.String())
	}
	if n := pe.historyRows(device); n != 1 {
		t.Fatalf("re-registered device came back with %d row(s) besides itself", n-1)
	}

	old := time.Now().Add(-48 * time.Hour).UnixMilli()
	fresh := time.Now().Add(time.Second).UnixMilli()
	for _, ts := range []int64{old, fresh} {
		pe.queries.UpsertStockEvent(ctx, db.UpsertStockEventParams{DeviceName: device, EventID: fmt.Sprint(ts), OccurredMs: ts, EnvTs: ts, Payload: []byte(`{}`)})
		pe.queries.InsertAlarmEvent(ctx, db.InsertAlarmEventParams{DeviceName: device, AlarmID: fmt.Sprint(ts), Status: "ACTIVE", OccurredMs: ts, EnvTs: ts, Payload: []byte(`{}`)})
	}
	if n := pe.count(`SELECT COUNT(*) FROM stock_events WHERE device_name = $1 AND env_ts = $2`, device, old); n != 0 {
		t.Error("a replayed pre-purge stock event was stored")
	}
	if n := pe.count(`SELECT COUNT(*) FROM alarm_events WHERE device_name = $1 AND env_ts = $2`, device, old); n != 0 {
		t.Error("a replayed pre-purge alarm was stored")
	}
	if n := pe.count(`SELECT COUNT(*) FROM stock_events WHERE device_name = $1 AND env_ts = $2`, device, fresh); n != 1 {
		t.Error("a new stock event after re-registration was dropped")
	}
	if n := pe.count(`SELECT COUNT(*) FROM alarm_events WHERE device_name = $1 AND env_ts = $2`, device, fresh); n != 1 {
		t.Error("a new alarm after re-registration was dropped")
	}
}

func TestDevicePurge_DisabledPlatformAdminIsRefused(t *testing.T) {
	pe := newPurgeEnv(t, newFakeTB())
	customer := pe.newCustomer("purge-disabled")
	device := "ZCT" + newBindDeviceName(t)
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)
	tok, id := pe.userIn(control.RolePlatformAdmin, 0)
	if _, err := pe.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	if _, code := pe.purge(tok, device, device, "r"); code != http.StatusForbidden {
		t.Errorf("disabled platform admin: %d, want 403", code)
	}
	if n := pe.count(`SELECT COUNT(*) FROM device_purges WHERE device_name = $1`, device); n != 0 {
		t.Errorf("%d task(s) created", n)
	}
}

// TestDevicePurge_BusinessFailureRollsBackAndResumes injects a database failure into the business step
// (a trigger that refuses this one device's rows): nothing of that step commits, the task says where it
// stopped, and asking again after the fault is gone finishes it with counts that add up.
func TestDevicePurge_BusinessFailureRollsBackAndResumes(t *testing.T) {
	device := "ZCT" + newBindDeviceName(t)
	pe := newPurgeEnv(t, newFakeTB(device))
	ctx := context.Background()
	customer := pe.newCustomer("purge-dbfail")
	pe.cleanupPurges(device)
	pe.seedDeviceIn(device, customer)
	_, staffID := pe.userIn(control.RoleCustomerUser, customer)
	pe.seedHistory(device, customer, customer, staffID)
	before := pe.historyRows(device)

	fn := "purge_test_refuse_" + fmt.Sprint(time.Now().UnixNano())
	if _, err := pe.pool.Exec(ctx, fmt.Sprintf(`
		CREATE FUNCTION %[1]s() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN IF OLD.device_name = '%[2]s' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN OLD; END $$;
		CREATE TRIGGER %[1]s BEFORE DELETE ON telemetry_hourly FOR EACH ROW EXECUTE FUNCTION %[1]s();`, fn, device)); err != nil {
		t.Fatal(err)
	}
	dropped := false
	drop := func() {
		if !dropped {
			pe.pool.Exec(context.Background(), fmt.Sprintf(`DROP TRIGGER IF EXISTS %[1]s ON telemetry_hourly; DROP FUNCTION IF EXISTS %[1]s();`, fn))
			dropped = true
		}
	}
	t.Cleanup(drop)

	body, _ := pe.purge(pe.admin, device, device, "db fault")
	pe.runner.Wait()
	failed := pe.getPurge(body.Purge.ID).Purge
	if failed.Status != purge.StatusFailed || failed.Step != purge.StepBusiness || failed.Error == nil {
		t.Fatalf("after injected failure: %+v", failed)
	}
	if n := pe.historyRows(device); n != before {
		t.Errorf("a failed business step left %d of %d rows: it must roll back as a whole", n, before)
	}

	drop()
	if _, code := pe.purge(pe.admin, device, device, "db fault"); code != http.StatusOK {
		t.Fatalf("retry: %d", code)
	}
	pe.runner.Wait()
	done := pe.getPurge(body.Purge.ID).Purge
	if done.Status != purge.StatusCompleted || done.Counts["telemetryHourly"] != 3 || done.Counts["tbDevice"] != 1 {
		t.Fatalf("after retry: %+v", done)
	}
	if n := pe.historyRows(device); n != 0 {
		t.Errorf("%d row(s) left", n)
	}
}
