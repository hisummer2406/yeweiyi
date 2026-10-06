package api_test

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// The tests in this file are the acceptance criteria of issue #29: 出入库与报警 are business records
// whose long-term home is the business database, and ThingsBoard is only the transport. Each one
// drives the real pipeline — device -> ThingsBoard -> ingest -> PostgreSQL -> API — because a seeded
// row would skip exactly the part that can lose data.

// stockEnvelope is a hand-written wire payload (not MarshalTelemetry), so a wrong key name could not
// change producer and parser in lockstep.
func stockEnvelope(envTS int64, eventID string, tsMs int64, netL float64) []byte {
	id := ""
	if eventID != "" {
		id = fmt.Sprintf(`"eventId":%q,`, eventID)
	}
	return []byte(fmt.Sprintf(
		`{"ts":%d,"values":{"stockEvent":{%s"tank":1,"direction":"IN","beginTimeMs":%d,"endTimeMs":%d,`+
			`"netVolumeL":%v,"tsMs":%d}}}`, envTS, id, tsMs-3_600_000, tsMs, netL, tsMs))
}

func stockPath(device string) string { return "/api/oil-in-out?device=" + device + stockWindow }

// waitStockRows polls until the endpoint lists n movements.
func waitStockRows(t *testing.T, env *testEnv, device string, n int) stockBody {
	t.Helper()
	var b stockBody
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", stockPath(device), nil)
		if rec.Code == http.StatusOK {
			if b = decodeStock(t, rec.Body.Bytes()); len(b.Rows) == n {
				return b
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("never saw %d stock rows; last %+v", n, b)
	return b
}

// TestRecords_SurviveThingsBoardDeletion is the headline acceptance test: delete the device's data
// from ThingsBoard and both endpoints answer exactly as before. Deleting the ThingsBoard device
// removes its telemetry, which is what a TTL sweep does to the records.
func TestRecords_SurviveThingsBoardDeletion(t *testing.T) {
	var tb *tbclient.Client
	env, sim, device, token := newStockEnv(t, func(d *api.Deps) { tb = d.TB })

	now := time.Now().UnixMilli()
	postDeviceTelemetry(t, token, stockEnvelope(now, "in-1-keep", now, 18000))
	if _, err := sim.RaiseAlarm(1, protocol.AlarmHighLevel, protocol.SeverityMajor, "1号罐高液位", 1500); err != nil {
		t.Fatalf("RaiseAlarm: %v", err)
	}

	alarmsPath := "/api/alarms?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"
	beforeStock := waitStockRows(t, env, device, 1)
	deadline := time.Now().Add(30 * time.Second)
	for len(fetchAlarms(t, env, alarmsPath)) < 1 {
		if time.Now().After(deadline) {
			t.Fatal("the alarm never reached the business database")
		}
		time.Sleep(300 * time.Millisecond)
	}
	beforeAlarms := fetchAlarms(t, env, alarmsPath)

	ctx := context.Background()
	dev, err := tb.GetDeviceByName(ctx, device)
	if err != nil {
		t.Fatalf("GetDeviceByName: %v", err)
	}
	if err := tb.DeleteDevice(ctx, dev.ID.ID); err != nil {
		t.Fatalf("delete device in ThingsBoard: %v", err)
	}
	// Give the ingester's reconcile loop several passes against the now-missing device: it must not
	// touch what is stored.
	time.Sleep(2 * time.Second)

	afterStock := decodeStock(t, env.authed("GET", stockPath(device), nil).Body.Bytes())
	if fmt.Sprint(afterStock.Rows) != fmt.Sprint(beforeStock.Rows) {
		t.Errorf("stock rows changed after ThingsBoard deletion:\n before %v\n after  %v", beforeStock.Rows, afterStock.Rows)
	}
	if after := fetchAlarms(t, env, alarmsPath); fmt.Sprint(after) != fmt.Sprint(beforeAlarms) {
		t.Errorf("alarms changed after ThingsBoard deletion:\n before %v\n after  %v", beforeAlarms, after)
	}
}

// TestRecords_ReplayOrderAndMissingIDAreIdempotent covers duplicate delivery, out-of-order replay and
// records the device sent without an eventId: none may produce a second row, and the latest revision
// (greatest envelope ts) wins whichever order they arrive in.
func TestRecords_ReplayOrderAndMissingIDAreIdempotent(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	// The correction (later envelope ts) arrives FIRST, then the original it supersedes, then the
	// original again — the offline-replay shape.
	postDeviceTelemetry(t, token, stockEnvelope(now, "in-1-fold", now-60_000, 18500))
	postDeviceTelemetry(t, token, stockEnvelope(now-30_000, "in-1-fold", now-60_000, 18000))
	postDeviceTelemetry(t, token, stockEnvelope(now-30_000, "in-1-fold", now-60_000, 18000))
	// No eventId, same bytes twice at the same envelope ts: one row, and no id is invented for the client.
	postDeviceTelemetry(t, token, stockEnvelope(now-20_000, "", now-20_000, 700))
	postDeviceTelemetry(t, token, stockEnvelope(now-20_000, "", now-20_000, 700))

	b := waitStockRows(t, env, device, 2)
	// Several reconcile passes later (every 500ms) nothing may have changed.
	time.Sleep(2 * time.Second)
	if again := decodeStock(t, env.authed("GET", stockPath(device), nil).Body.Bytes()); len(again.Rows) != 2 {
		t.Fatalf("rows grew to %d after further reconcile passes: %v", len(again.Rows), again.Rows)
	}

	for _, r := range b.Rows {
		switch r["eventId"] {
		case "in-1-fold":
			if v, _ := r["netVolumeL"].(float64); v != 18500 {
				t.Errorf("netVolumeL = %v, want 18500 from the later revision", r["netVolumeL"])
			}
		case "":
			if v, _ := r["netVolumeL"].(float64); v != 700 {
				t.Errorf("record without eventId: netVolumeL = %v, want 700", r["netVolumeL"])
			}
		default:
			t.Errorf("unexpected eventId %v (a synthetic id must never reach the client)", r["eventId"])
		}
	}
	if b.Folded != 1 {
		t.Errorf("folded = %d, want 1 (two distinct reports of in-1-fold; replays of one report do not count)", b.Folded)
	}
}

// TestRecords_ConcurrentBackfillAndLiveIngestLoseNothing: a full backfill racing the live
// subscription and the reconcile loop must converge on exactly the published set.
func TestRecords_ConcurrentBackfillAndLiveIngestLoseNothing(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	const n = 40
	now := time.Now().UnixMilli()
	var wg sync.WaitGroup
	for w := 0; w < 4; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 5; i++ {
				if _, err := env.ing.BackfillDevice(context.Background(), device, 0); err != nil {
					t.Errorf("BackfillDevice: %v", err)
				}
				time.Sleep(100 * time.Millisecond)
			}
		}()
	}
	for i := 0; i < n; i++ {
		postDeviceTelemetry(t, token, stockEnvelope(now+int64(i), fmt.Sprintf("in-1-c%02d", i), now-int64(n-i)*1000, 1000))
	}
	wg.Wait()

	b := waitStockRows(t, env, device, n)
	seen := map[any]bool{}
	for _, r := range b.Rows {
		if seen[r["eventId"]] {
			t.Errorf("duplicate row for %v", r["eventId"])
		}
		seen[r["eventId"]] = true
	}
	if len(seen) != n {
		t.Errorf("got %d distinct events, want %d", len(seen), n)
	}
}

// TestRecords_TransferHidesThePreviousOwnersRecords: the records are business data of whoever owned the
// device when they were made, and ThingsBoard keeps no customer, so the endpoints clip to the moment
// the device joined its current customer — for platform admins too.
func TestRecords_TransferHidesThePreviousOwnersRecords(t *testing.T) {
	env, sim, device, token := newStockEnv(t)

	now := time.Now()
	postDeviceTelemetry(t, token, stockEnvelope(now.Add(-2*time.Hour).UnixMilli(), "in-1-old", now.Add(-2*time.Hour).UnixMilli(), 1000))
	postDeviceTelemetry(t, token, stockEnvelope(now.Add(-10*time.Minute).UnixMilli(), "in-1-new", now.Add(-10*time.Minute).UnixMilli(), 2000))
	waitStockRows(t, env, device, 2)

	// Moved to the current customer one hour ago. (Setting the column directly: the maintenance
	// trigger only fires when customer_id changes.)
	if _, err := env.pool.Exec(context.Background(),
		`UPDATE devices SET customer_assigned_at = $2 WHERE device_name = $1`, device, now.Add(-time.Hour)); err != nil {
		t.Fatalf("set customer_assigned_at: %v", err)
	}

	for _, role := range []string{"customer_admin", "platform_admin"} {
		env.setRole(role)
		b := decodeStock(t, env.authed("GET", stockPath(device), nil).Body.Bytes())
		if len(b.Rows) != 1 || b.Rows[0]["eventId"] != "in-1-new" {
			t.Errorf("%s sees %v, want only in-1-new (in-1-old predates the assignment)", role, b.Rows)
		}
	}

	// Alarms follow the same rule.
	env.setRole("platform_admin")
	raised, err := sim.RaiseAlarm(1, protocol.AlarmHighLevel, protocol.SeverityMajor, "新报警", 1500)
	if err != nil {
		t.Fatalf("RaiseAlarm: %v", err)
	}
	alarmsPath := "/api/alarms?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"
	deadline := time.Now().Add(30 * time.Second)
	for {
		got := fetchAlarms(t, env, alarmsPath)
		if len(got) == 1 && got[0]["alarmId"] == raised.AlarmID {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the alarm raised after the assignment is not listed; last %v", got)
		}
		time.Sleep(300 * time.Millisecond)
	}
	// Move the assignment past it: now nothing is visible.
	if _, err := env.pool.Exec(context.Background(),
		`UPDATE devices SET customer_assigned_at = now() WHERE device_name = $1`, device); err != nil {
		t.Fatalf("set customer_assigned_at: %v", err)
	}
	if got := fetchAlarms(t, env, alarmsPath); len(got) != 0 {
		t.Errorf("alarm from before the transfer is still visible: %v", got)
	}
}
