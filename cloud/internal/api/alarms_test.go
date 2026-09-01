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
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// /api/alarms now reads through to ThingsBoard, so testing it honestly needs a real device
// publishing a real alarm — a seeded database row would prove nothing, since nothing reads that
// table any more.

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
func newStockEnv(t *testing.T) (*testEnv, *devicesim.Device, string, string) {
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

	env := newTestEnvWith(t, func(d *api.Deps) { d.TB = tb })

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

// TestListAlarms_ReadsThroughToThingsBoard is the replacement for the old seeded-row test.
func TestListAlarms_ReadsThroughToThingsBoard(t *testing.T) {
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

// TestListAlarms_RejectsOffset: ThingsBoard pages by time window, not row offset. Silently ignoring
// offset would hand a paginating client the same page forever.
func TestListAlarms_RejectsOffset(t *testing.T) {
	env, _, device := newAlarmEnv(t)

	rec := env.authed("GET", "/api/alarms?device="+device+"&offset=10", nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for offset, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestListAlarms_EmptyForDeviceThatNeverAlarmed: an empty list, not a 404 — the device is fine, it
// simply has no alarms.
func TestListAlarms_EmptyForDeviceThatNeverAlarmed(t *testing.T) {
	env, _, device := newAlarmEnv(t)

	got := fetchAlarms(t, env, "/api/alarms?device="+device)
	if len(got) != 0 {
		t.Errorf("expected no alarms, got %d", len(got))
	}
}
