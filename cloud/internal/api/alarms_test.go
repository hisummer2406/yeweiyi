package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/ingest"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// /api/alarms is served from the business database, which only ingest writes, so testing it honestly
// needs a real device publishing a real alarm through ThingsBoard and the ingester — a seeded row would
// skip the pipeline the endpoint depends on.

func tbEnvOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// newAlarmEnv wires a router with a live ThingsBoard client and returns a connected simulated
// device bound to the test user.
func newAlarmEnv(t *testing.T) (*testEnv, *devicesim.Device, string) {
	env, sim, device, _ := newStockEnv(t)
	return env, sim, device
}

// newStockEnv is newAlarmEnv plus the device's access token, which tests that publish raw telemetry
// (rather than driving the simulator) need.
func newStockEnv(t *testing.T, opts ...func(*api.Deps)) (*testEnv, *devicesim.Device, string, string) {
	t.Helper()
	ctx := context.Background()

	if conn, err := net.DialTimeout("tcp", "localhost:8081", 2*time.Second); err != nil {
		t.Skipf("ThingsBoard not reachable (%v); start it with: docker compose -f docker-compose.thingsboard.yml up -d", err)
	} else {
		conn.Close()
	}

	tb, err := tbclient.New(tbclient.Config{
		BaseURL:  tbEnvOr("TB_BASE_URL", "http://localhost:8081"),
		Username: tbEnvOr("TB_USERNAME", "tenant@thingsboard.org"),
		Password: tbEnvOr("TB_PASSWORD", "tenant"),
	})
	if err != nil {
		t.Fatalf("tbclient.New: %v", err)
	}
	if err := tb.Login(ctx); err != nil {
		t.Skipf("ThingsBoard login failed: %v", err)
	}

	env := newTestEnvWith(t, func(d *api.Deps) {
		d.TB = tb
		for _, opt := range opts {
			opt(d)
		}
	})

	deviceName := fmt.Sprintf("api-alarm-%d", time.Now().UnixNano())
	tbDev, err := tb.EnsureDevice(ctx, deviceName, tbclient.DefaultDeviceProfile)
	if err != nil {
		t.Fatalf("provision device: %v", err)
	}
	t.Cleanup(func() { tb.DeleteDevice(context.Background(), tbDev.ID.ID) })

	creds, err := tb.GetDeviceCredentials(ctx, tbDev.ID.ID)
	if err != nil {
		t.Fatalf("credentials: %v", err)
	}

	env.ownDevice(deviceName)
	// ownDevice stamps customer_assigned_at = now, and the record endpoints hide anything older than
	// that (plus the transfer allowance) from the current owner. These tests publish records "now" and
	// in the past on a device that was just created, so clear the stamp; the clipping itself has its
	// own tests (TestRecords_*Transfer*) that set it deliberately. The UPDATE names no customer_id, so
	// the maintenance trigger does not fire.
	if _, err := env.pool.Exec(ctx, `UPDATE devices SET customer_assigned_at = NULL WHERE device_name = $1`, deviceName); err != nil {
		t.Fatalf("clear customer_assigned_at: %v", err)
	}

	// The business database is where these endpoints read from (issue #29), so the full pipeline is
	// under test: device -> ThingsBoard -> ingest -> business database -> API. A short reconcile
	// interval and a very long lookback stand in for the production 10m / 8d, so records published
	// with old timestamps (replay tests) are still picked up promptly.
	ingCtx, stopIngest := context.WithCancel(ctx)
	ing := ingest.New(ingest.Config{
		TB: tb, Queries: env.queries,
		RecordReconcileInterval: 500 * time.Millisecond,
		RecordReconcileLookback: 100 * 365 * 24 * time.Hour,
		ResubscribeDelay:        time.Second,
		Logf:                    t.Logf,
	})
	ing.StartDevice(ingCtx, deviceName)
	env.ing = ing
	t.Cleanup(stopIngest)
	t.Cleanup(func() {
		// Records are kept forever in production; the test device is not.
		for _, table := range []string{"stock_events", "alarm_events", "record_rejects"} {
			env.pool.Exec(context.Background(), `DELETE FROM `+table+` WHERE device_name = $1`, deviceName)
		}
	})

	sim := devicesim.New(devicesim.Config{
		BrokerURL:        tbEnvOr("TB_MQTT_URL", "tcp://localhost:1884"),
		AccessToken:      creds.CredentialsID,
		Tanks:            1,
		SnapshotInterval: 10 * time.Minute,
		Logf:             t.Logf,
	})
	connCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := sim.Connect(connCtx); err != nil {
		t.Fatalf("device connect: %v", err)
	}
	t.Cleanup(sim.Disconnect)

	return env, sim, deviceName, creds.CredentialsID
}

// postDeviceTelemetry publishes a raw telemetry payload as the device, via ThingsBoard's HTTP
// device API. Used where a test needs to send a payload the simulator does not produce — notably
// the malformed legacy stock record.
func postDeviceTelemetry(t *testing.T, accessToken string, payload []byte) {
	t.Helper()
	url := tbEnvOr("TB_BASE_URL", "http://localhost:8081") + "/api/v1/" + accessToken + "/telemetry"
	resp, err := http.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("publish telemetry: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		t.Fatalf("publish telemetry: HTTP %d", resp.StatusCode)
	}
}

func fetchAlarms(t *testing.T, env *testEnv, path string) []map[string]any {
	t.Helper()
	rec := env.authed("GET", path, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET %s: expected 200, got %d: %s", path, rec.Code, rec.Body.String())
	}
	var out []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode: %v (body %s)", err, rec.Body.String())
	}
	return out
}

// TestListAlarms_ServedFromBusinessDatabase is the replacement for the old seeded-row test.
func TestListAlarms_ServedFromBusinessDatabase(t *testing.T) {
	env, sim, device := newAlarmEnv(t)

	active, err := sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "1号罐高高液位报警", 1980.4)
	if err != nil {
		t.Fatalf("RaiseAlarm: %v", err)
	}

	path := "/api/alarms?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	var got []map[string]any
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		got = fetchAlarms(t, env, path)
		if len(got) > 0 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if len(got) == 0 {
		t.Fatal("the raised alarm never appeared through /api/alarms")
	}

	a := got[0]
	if a["alarmId"] != active.AlarmID {
		t.Errorf("alarmId = %v, want %s", a["alarmId"], active.AlarmID)
	}
	if a["type"] != protocol.AlarmHighHighLevel || a["severity"] != protocol.SeverityCritical {
		t.Errorf("alarm content mangled: %v", a)
	}
	if a["status"] != protocol.AlarmStatusActive {
		t.Errorf("status = %v, want ACTIVE", a["status"])
	}
	if a["text"] != "1号罐高高液位报警" {
		t.Errorf("text = %v", a["text"])
	}
	// time must be a real RFC3339 instant, not the epoch-millis integer the wire format uses.
	if _, err := time.Parse(time.RFC3339, fmt.Sprint(a["time"])); err != nil {
		t.Errorf("time = %v, not RFC3339: %v", a["time"], err)
	}

	assertNoLegacyTelemetryTables(t, env)
}

// TestListAlarms_SortReturnsTheNewestWhenLimited is the case that motivated pushing the sort
// direction down into ThingsBoard rather than reversing a page in Go: with a limit, ascending and
// descending select *different* alarms, not merely a different order.
func TestListAlarms_SortReturnsTheNewestWhenLimited(t *testing.T) {
	env, sim, device := newAlarmEnv(t)

	var raised []protocol.AlarmEvent
	for i := 0; i < 3; i++ {
		ev, err := sim.RaiseAlarm(1, protocol.AlarmHighLevel, protocol.SeverityMajor,
			fmt.Sprintf("报警 %d", i+1), float64(1800+i))
		if err != nil {
			t.Fatalf("RaiseAlarm %d: %v", i, err)
		}
		raised = append(raised, ev)
		time.Sleep(400 * time.Millisecond)
	}

	base := "/api/alarms?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	// Wait until all three are visible before asserting on limits.
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if len(fetchAlarms(t, env, base)) >= 3 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}

	newest := fetchAlarms(t, env, base+"&limit=1&sort=false")
	if len(newest) != 1 {
		t.Fatalf("sort=false&limit=1 returned %d alarms, want 1", len(newest))
	}
	if newest[0]["alarmId"] != raised[2].AlarmID {
		t.Errorf("descending+limit returned %v, want the NEWEST alarm %s — "+
			"this is the bug that appears if the sort is applied after the limit",
			newest[0]["alarmId"], raised[2].AlarmID)
	}

	oldest := fetchAlarms(t, env, base+"&limit=1&sort=true")
	if len(oldest) != 1 {
		t.Fatalf("sort=true&limit=1 returned %d alarms, want 1", len(oldest))
	}
	if oldest[0]["alarmId"] != raised[0].AlarmID {
		t.Errorf("ascending+limit returned %v, want the OLDEST alarm %s", oldest[0]["alarmId"], raised[0].AlarmID)
	}
}

// TestListAlarms_OffsetPages: the table pages by row offset, so a paginating client advances through
// the history instead of seeing the first page forever (ThingsBoard could only page by time window,
// and this endpoint used to answer 400 for offset because of it).
func TestListAlarms_OffsetPages(t *testing.T) {
	env, sim, device := newAlarmEnv(t)

	var ids []string
	for i := 0; i < 3; i++ {
		a, err := sim.RaiseAlarm(1, protocol.AlarmHighLevel, protocol.SeverityMajor, fmt.Sprintf("报警%d", i), 1500+float64(i))
		if err != nil {
			t.Fatalf("RaiseAlarm: %v", err)
		}
		ids = append(ids, a.AlarmID)
		time.Sleep(5 * time.Millisecond) // distinct occurrence times, so the order is not a tie-break
	}
	base := "/api/alarms?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z&sort=true"

	deadline := time.Now().Add(20 * time.Second)
	for len(fetchAlarms(t, env, base)) < 3 {
		if time.Now().After(deadline) {
			t.Fatal("the three alarms never all appeared")
		}
		time.Sleep(300 * time.Millisecond)
	}

	page := fetchAlarms(t, env, base+"&limit=1&offset=1")
	if len(page) != 1 || page[0]["alarmId"] != ids[1] {
		t.Fatalf("offset=1&limit=1 = %v, want the second-oldest alarm %s", page, ids[1])
	}
	if past := fetchAlarms(t, env, base+"&offset=3"); len(past) != 0 {
		t.Fatalf("offset past the end returned %v, want []", past)
	}
}

func TestListAlarms_EmptyForDeviceThatNeverAlarmed(t *testing.T) {
	env, _, device := newAlarmEnv(t)

	got := fetchAlarms(t, env, "/api/alarms?device="+device)
	if len(got) != 0 {
		t.Errorf("expected no alarms, got %d", len(got))
	}
}
