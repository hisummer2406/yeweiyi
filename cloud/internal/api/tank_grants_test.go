package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/devicesim"
)

// cloud#63: an employee (customer_user) sees only the (device, tank) pairs a
// platform administrator
// granted (platform administrator only). These run against real PostgreSQL and a real ThingsBoard with a simulated 3-tank device,
// and read every path an employee could use: lists, latest, history, alarms, stock, WebSocket.

type grantEnv struct {
	env      *testEnv
	device   string
	token    string // device access token
	cust     int64
	admin    string
	adminID  int64
	platform string // platform administrator: the only role that writes grants
	emp      string
	empID    int64
	otherCus int64
}

func newGrantEnv(t *testing.T) *grantEnv {
	t.Helper()
	env, sim, device, token := newStockEnv(t)
	sim.Disconnect()
	three := devicesim.New(devicesim.Config{
		BrokerURL: tbEnvOr("TB_MQTT_URL", "tcp://localhost:1884"), AccessToken: token,
		Tanks: 3, SnapshotInterval: 10 * time.Minute, Logf: t.Logf,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := three.Connect(ctx); err != nil {
		t.Fatalf("connect 3-tank device: %v", err)
	}
	t.Cleanup(three.Disconnect)
	if _, err := three.PublishSnapshot(); err != nil {
		t.Fatalf("publish snapshot: %v", err)
	}

	g := &grantEnv{env: env, device: device, token: token, cust: env.defaultCustomerID()}
	g.admin, g.adminID = env.userIn(control.RoleCustomerAdmin, g.cust)
	g.platform, _ = env.userIn(control.RolePlatformAdmin, 0)
	g.emp, g.empID = env.userIn(control.RoleCustomerUser, g.cust)
	g.otherCus = env.newCustomer("other")

	// Wait for the cloud to see all three tanks (ThingsBoard ingestion is asynchronous).
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.do("GET", "/api/devices/"+device+"/tanks", []byte(g.admin), nil)
		if rec.Code == http.StatusOK && strings.Count(rec.Body.String(), `"index"`) == 3 {
			return g
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("the device's three tanks never became visible")
	return nil
}

func (g *grantEnv) call(t *testing.T, method, path, token string, body any) (int, string) {
	t.Helper()
	var b []byte
	if body != nil {
		b = jsonBody(t, body)
	}
	rec := g.env.do(method, path, []byte(token), b)
	return rec.Code, rec.Body.String()
}

func (g *grantEnv) set(t *testing.T, token string, user int64, device string, tanks ...int) (int, string) {
	t.Helper()
	if tanks == nil {
		tanks = []int{}
	}
	return g.call(t, "PUT", fmt.Sprintf("/api/users/%d/tank-grants/%s", user, device), token, map[string]any{"tanks": tanks})
}

func (g *grantEnv) seedAlarm(t *testing.T, id string, tank *int) {
	t.Helper()
	payload := map[string]any{"alarmId": id, "type": "HIGH_HIGH_LEVEL", "severity": "CRITICAL", "status": "ACTIVE",
		"text": "t", "value": 1.0, "tsMs": time.Now().Add(-time.Hour).UnixMilli()}
	if tank != nil {
		payload["tank"] = *tank
	}
	raw, _ := json.Marshal(payload)
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO alarm_events (device_name, alarm_id, status, occurred_at, env_ts, payload, customer_id)
		VALUES ($1, $2, 'ACTIVE', now() - interval '1 hour', $3, $4, $5)`,
		g.device, id, time.Now().UnixMilli(), raw, g.cust); err != nil {
		t.Fatalf("seed alarm: %v", err)
	}
}

func (g *grantEnv) seedStock(t *testing.T, id string, tank *int) {
	t.Helper()
	payload := map[string]any{"eventId": id, "direction": "IN", "oilType": "0号柴油",
		"beginTimeMs": time.Now().Add(-2 * time.Hour).UnixMilli(), "endTimeMs": time.Now().Add(-time.Hour).UnixMilli(),
		"beginLevelMm": 400.0, "endLevelMm": 1600.0, "beginVolumeL": 6000.0, "endVolumeL": 24000.0,
		"netVolumeL": 18000.0, "tsMs": time.Now().Add(-time.Hour).UnixMilli()}
	if tank != nil {
		payload["tank"] = *tank
	}
	raw, _ := json.Marshal(payload)
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO stock_events (device_name, event_id, occurred_at, env_ts, report_ts, payload, customer_id)
		VALUES ($1, $2, now() - interval '1 hour', $3, ARRAY[$3::bigint], $4, $5)`,
		g.device, id, time.Now().UnixMilli(), raw, g.cust); err != nil {
		t.Fatalf("seed stock: %v", err)
	}
}

func tankIdx(t *testing.T, body string) []int {
	t.Helper()
	var v struct {
		Tanks []struct {
			Index int `json:"index"`
		} `json:"tanks"`
	}
	if err := json.Unmarshal([]byte(body), &v); err != nil {
		t.Fatalf("decode %q: %v", body, err)
	}
	var out []int
	for _, x := range v.Tanks {
		out = append(out, x.Index)
	}
	return out
}

func sameInts(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func intp(v int) *int { return &v }

func TestTankGrants_EmployeeSeesOnlyGrantedTanks(t *testing.T) {
	g := newGrantEnv(t)
	d := g.device

	// Before any grant: the device is unreachable, and the list is empty — not "all tanks".
	if code, body := g.call(t, "GET", "/api/devices/"+d+"/telemetry/latest", g.emp, nil); code != http.StatusForbidden {
		t.Fatalf("employee without grants reads the device: %d %s, want 403", code, body)
	}
	if code, body := g.call(t, "GET", "/api/devices", g.emp, nil); code != http.StatusOK || strings.Contains(body, d) {
		t.Fatalf("employee without grants lists the device: %d %s", code, body)
	}

	if code, body := g.set(t, g.platform, g.empID, d, 1); code != http.StatusOK || !strings.Contains(body, `"changed":true`) {
		t.Fatalf("grant tank 1: %d %s", code, body)
	}

	// Latest: tank 1 only — whole-device snapshot is cut on the server.
	code, body := g.call(t, "GET", "/api/devices/"+d+"/telemetry/latest", g.emp, nil)
	if code != http.StatusOK || !sameInts(tankIdx(t, body), []int{1}) {
		t.Errorf("employee latest: %d %s, want exactly tank 1", code, body)
	}
	code, body = g.call(t, "GET", "/api/devices/"+d+"/telemetry/latest", g.admin, nil)
	if code != http.StatusOK || !sameInts(tankIdx(t, body), []int{1, 2, 3}) {
		t.Errorf("admin latest: %d %s, want tanks 1,2,3", code, body)
	}
	// The tank list and the device list say the same thing.
	if code, body := g.call(t, "GET", "/api/devices/"+d+"/tanks", g.emp, nil); code != http.StatusOK || !sameInts(tankIdx(t, body), []int{1}) {
		t.Errorf("employee tanks: %d %s", code, body)
	}
	code, body = g.call(t, "GET", "/api/devices", g.emp, nil)
	if code != http.StatusOK || !strings.Contains(body, `"granted_tanks":[1]`) {
		t.Errorf("employee device list: %d %s, want granted_tanks [1]", code, body)
	}

	// History: the granted tank answers; an ungranted or non-existent tank is the same 403.
	hist := func(tank int) (int, string) {
		return g.call(t, "GET", fmt.Sprintf("/api/devices/%s/telemetry/history?tank=%d&fields=oilLevelMm&interval=1h", d, tank), g.emp, nil)
	}
	if code, body := hist(1); code != http.StatusOK {
		t.Errorf("history of the granted tank: %d %s", code, body)
	}
	c2, b2 := hist(2)
	c9, b9 := hist(9)
	if c2 != http.StatusForbidden || c9 != c2 || b2 != b9 || !strings.Contains(b2, "tank_not_authorized") {
		t.Errorf("ungranted vs nonexistent tank differ: %d %s | %d %s", c2, b2, c9, b9)
	}

	// Alarms and stock are filtered in SQL: tank 1 is theirs; tank 2 and a record with no tank are not.
	g.seedAlarm(t, "al-t1", intp(1))
	g.seedAlarm(t, "al-t2", intp(2))
	g.seedAlarm(t, "al-none", nil)
	g.seedStock(t, "st-t1", intp(1))
	g.seedStock(t, "st-t2", intp(2))
	g.seedStock(t, "st-none", nil)
	count := func(path, token, key string) []string {
		code, body := g.call(t, "GET", path, token, nil)
		if code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, code, body)
		}
		var ids []string
		for _, id := range []string{"al-t1", "al-t2", "al-none", "st-t1", "st-t2", "st-none"} {
			if strings.Contains(body, id) || (key == "stock" && strings.Contains(body, id)) {
				ids = append(ids, id)
			}
		}
		return ids
	}
	if got := count("/api/alarms?device="+d, g.emp, "alarm"); !(len(got) == 1 && got[0] == "al-t1") {
		t.Errorf("employee alarms = %v, want only al-t1", got)
	}
	if got := count("/api/alarms?device="+d, g.admin, "alarm"); len(got) != 3 {
		t.Errorf("admin alarms = %v, want all three (including the tank-less one)", got)
	}
	if got := count("/api/oil-in-out?device="+d+stockWindow, g.emp, "stock"); !(len(got) == 1 && got[0] == "st-t1") {
		t.Errorf("employee stock = %v, want only st-t1", got)
	}
	if got := count("/api/oil-in-out?device="+d+stockWindow, g.admin, "stock"); len(got) != 3 {
		t.Errorf("admin stock = %v, want all three", got)
	}
	// Pagination follows the filter: limit 1 still returns the employee's only record.
	if got := count("/api/alarms?device="+d+"&limit=1", g.emp, "alarm"); len(got) != 1 || got[0] != "al-t1" {
		t.Errorf("limit=1 employee alarms = %v, want al-t1 (limit applies after the tank filter)", got)
	}

	// Commands and the audit trail are whole-device: not shown, not allowed.
	// (Issuing is refused by role in internal/control — TestIssue_CustomerUserCannotIssueAnyCommand —
	// and this environment has no command service wired, so only the read side is checked here.)
	if code, body := g.call(t, "GET", "/api/devices/"+d+"/commands", g.emp, nil); code == http.StatusOK && strings.Contains(body, `"method"`) {
		t.Errorf("employee read the command history: %s", body)
	}
	if code, body := g.call(t, "GET", "/api/audit?device="+d, g.emp, nil); code == http.StatusOK && strings.Contains(body, `"action"`) {
		t.Errorf("employee read the audit trail: %s", body)
	}

	// Revocation takes effect on the next request.
	if code, body := g.set(t, g.platform, g.empID, d); code != http.StatusOK {
		t.Fatalf("revoke: %d %s", code, body)
	}
	if code, _ := g.call(t, "GET", "/api/devices/"+d+"/telemetry/latest", g.emp, nil); code != http.StatusForbidden {
		t.Errorf("after revocation the employee still reads the device: %d", code)
	}
	if code, body := g.call(t, "GET", "/api/alarms?device="+d, g.emp, nil); code != http.StatusForbidden {
		t.Errorf("after revocation alarms: %d %s, want 403", code, body)
	}
}

func TestTankGrants_WebSocketOnlyCarriesGrantedTanks(t *testing.T) {
	g := newGrantEnv(t)
	if code, body := g.set(t, g.platform, g.empID, g.device, 1); code != http.StatusOK {
		t.Fatalf("grant: %d %s", code, body)
	}
	srv := httptest.NewServer(g.env.router)
	defer srv.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/ws/alarms?device="+g.device+"&token="+g.emp, nil)
	if err != nil {
		t.Fatalf("employee dial: %v", err)
	}
	defer conn.Close()

	publish := func(id string, tank int) {
		b, _ := json.Marshal(map[string]any{"deviceName": g.device, "alarmId": id, "tank": tank})
		for i := 0; i < 10; i++ { // the subscription may not be live yet; re-publish until it is
			g.env.rdb.Publish(context.Background(), "alarms:live", b)
			time.Sleep(150 * time.Millisecond)
		}
	}
	got := make(chan string, 64)
	go func() {
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			got <- string(msg)
		}
	}()
	publish("ws-tank2", 2)
	publish("ws-tank0", 0)
	publish("ws-tank1", 1)
	deadline := time.After(3 * time.Second)
	var seen []string
loop:
	for {
		select {
		case m := <-got:
			seen = append(seen, m)
		case <-deadline:
			break loop
		}
	}
	all := strings.Join(seen, "|")
	if !strings.Contains(all, "ws-tank1") {
		t.Errorf("the granted tank's alarm never arrived: %q", all)
	}
	if strings.Contains(all, "ws-tank2") || strings.Contains(all, "ws-tank0") {
		t.Errorf("a frame for an ungranted or whole-device alarm reached the employee: %q", all)
	}
}

func TestTankGrants_Authority(t *testing.T) {
	g := newGrantEnv(t)
	d := g.device
	otherAdmin, _ := g.env.userIn(control.RoleCustomerAdmin, g.otherCus)
	_, otherEmpID := g.env.userIn(control.RoleCustomerUser, g.otherCus)

	// Only a platform administrator writes grants. Every customer-side caller gets the role-level
	// 403, whatever they name, and nothing is written.
	for name, tok := range map[string]string{"employee": g.emp, "own customer admin": g.admin, "foreign customer admin": otherAdmin} {
		if code, body := g.set(t, tok, g.empID, d, 1); code != http.StatusForbidden || !strings.Contains(body, "not authorized") {
			t.Errorf("%s granting: %d %s, want 403 not authorized", name, code, body)
		}
		if code, _ := g.call(t, "GET", fmt.Sprintf("/api/users/%d/tank-grants", g.empID), tok, nil); code != http.StatusForbidden {
			t.Errorf("%s listing grants: %d, want 403", name, code)
		}
	}
	var denied int
	g.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = 'tank.grants.set' AND outcome = 'denied' AND actor_id = $1`, g.adminID).Scan(&denied)
	if denied == 0 {
		t.Errorf("a refused customer admin left no denied audit row")
	}
	// An employee of another customer than the device: a mismatch, never a grant.
	if code, body := g.set(t, g.platform, otherEmpID, d, 1); code != http.StatusConflict || !strings.Contains(body, "device_customer_mismatch") {
		t.Errorf("cross-customer grant: %d %s, want 409 device_customer_mismatch", code, body)
	}
	// An administrator is not a grant target.
	if code, body := g.set(t, g.platform, g.adminID, d, 1); code != http.StatusConflict || !strings.Contains(body, "grant_not_applicable") {
		t.Errorf("granting an administrator: %d %s, want 409 grant_not_applicable", code, body)
	}
	// Tank numbers the client invents are refused; missing key is a client bug, not "revoke all".
	for name, body := range map[string]map[string]any{
		"zero tank":   {"tanks": []int{0}},
		"huge tank":   {"tanks": []int{65}},
		"unreported":  {"tanks": []int{7}},
		"missing key": {},
		"not a list":  {"tanks": "1"},
		"null":        {"tanks": nil},
	} {
		code, resp := g.call(t, "PUT", fmt.Sprintf("/api/users/%d/tank-grants/%s", g.empID, d), g.platform, body)
		if code != http.StatusBadRequest {
			t.Errorf("%s: %d %s, want 400", name, code, resp)
		}
	}
	var n int
	g.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_tank_grants WHERE user_id = $1`, g.empID).Scan(&n)
	if n != 0 {
		t.Errorf("a refused request created %d grants", n)
	}

	// Idempotent.
	if code, body := g.set(t, g.platform, g.empID, d, 1, 2); code != http.StatusOK || !strings.Contains(body, `"changed":true`) {
		t.Fatalf("grant: %d %s", code, body)
	}
	if code, body := g.set(t, g.platform, g.empID, d, 2, 1); code != http.StatusOK || !strings.Contains(body, `"changed":false`) {
		t.Errorf("repeat: %d %s, want changed=false", code, body)
	}
	if code, body := g.call(t, "GET", fmt.Sprintf("/api/users/%d/tank-grants", g.empID), g.platform, nil); code != http.StatusOK ||
		!strings.Contains(body, `"tanks":[1,2]`) {
		t.Errorf("list: %d %s", code, body)
	}
	var audits int
	g.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = 'tank.grants.set' AND outcome = 'allowed' AND detail->>'userId' = $1`,
		fmt.Sprint(g.empID)).Scan(&audits)
	if audits != 1 {
		t.Errorf("grant audit rows = %d, want 1 (the repeat changes nothing)", audits)
	}

	// Concurrent writers of the same set converge and never 500.
	var wg sync.WaitGroup
	codes := make([]int, 8)
	for i := range codes {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			set := []int{1, 2, 3}
			if i%2 == 1 {
				set = []int{3}
			}
			codes[i], _ = g.set(t, g.platform, g.empID, d, set...)
		}(i)
	}
	wg.Wait()
	for _, c := range codes {
		if c != http.StatusOK {
			t.Errorf("concurrent grants: %v, want all 200", codes)
			break
		}
	}
	rows, _ := g.env.pool.Query(context.Background(), `SELECT tank FROM user_tank_grants WHERE user_id = $1 ORDER BY tank`, g.empID)
	var final []int
	for rows.Next() {
		var x int
		rows.Scan(&x)
		final = append(final, x)
	}
	rows.Close()
	if !(sameInts(final, []int{3}) || sameInts(final, []int{1, 2, 3})) {
		t.Errorf("concurrent grants left %v, want one of the two requested sets", final)
	}

	// Disabled or moved: grants stop working without being deleted first.
	if _, err := g.env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, g.empID); err != nil {
		t.Fatal(err)
	}
	if code, _ := g.call(t, "GET", "/api/devices/"+d+"/telemetry/latest", g.emp, nil); code != http.StatusForbidden {
		t.Errorf("a disabled employee reads the device: %d, want 403", code)
	}
}

// After a swap the new device carries nothing from the old one, whatever the station and tank numbers
// are, and grants for it can only be made once it has reported the tanks.
func TestTankGrants_ReplacementDeviceInheritsNothing(t *testing.T) {
	g := newGrantEnv(t)
	ctx := context.Background()
	if code, body := g.set(t, g.platform, g.empID, g.device, 1, 2); code != http.StatusOK {
		t.Fatalf("grant on A: %d %s", code, body)
	}
	newDev := fmt.Sprintf("tank-grant-b-%d", time.Now().UnixNano())
	g.env.seedDevice(newDev)
	if _, err := g.env.pool.Exec(ctx, `UPDATE devices SET customer_id = $1 WHERE device_name = $2`, g.cust, newDev); err != nil {
		t.Fatal(err)
	}
	var station int64
	code, body := g.call(t, "POST", "/api/stations", g.admin, map[string]any{"name": uniqueName("grant-swap")})
	if code != http.StatusCreated {
		t.Fatalf("station: %d %s", code, body)
	}
	var st struct {
		ID int64 `json:"id"`
	}
	json.Unmarshal([]byte(body), &st)
	station = st.ID
	t.Cleanup(func() {
		g.env.pool.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE station_id = $1`, station)
		g.env.pool.Exec(ctx, `DELETE FROM stations WHERE id = $1`, station)
	})
	if code, body := g.call(t, "PUT", "/api/devices/"+g.device+"/station", g.admin, map[string]any{"stationId": station}); code != http.StatusOK {
		t.Fatalf("A to station: %d %s", code, body)
	}
	if code, body := g.call(t, "POST", fmt.Sprintf("/api/stations/%d/replace-device", station), g.admin,
		map[string]any{"deviceName": newDev, "expectedCurrentDeviceName": g.device}); code != http.StatusOK {
		t.Fatalf("replace: %d %s", code, body)
	}

	// The employee's grants are on A and stay on A; B has none and cannot be reached.
	var onB, onA int
	g.env.pool.QueryRow(ctx, `SELECT count(*) FROM user_tank_grants WHERE device_name = $1`, newDev).Scan(&onB)
	g.env.pool.QueryRow(ctx, `SELECT count(*) FROM user_tank_grants WHERE device_name = $1 AND user_id = $2`, g.device, g.empID).Scan(&onA)
	if onB != 0 || onA != 2 {
		t.Errorf("grants: B=%d (want 0), A=%d (want 2: untouched, not deleted)", onB, onA)
	}
	if code, body := g.call(t, "GET", "/api/devices/"+newDev+"/telemetry/latest", g.emp, nil); code != http.StatusForbidden {
		t.Errorf("employee reads the new device: %d %s, want 403", code, body)
	}
	if code, body := g.call(t, "GET", "/api/devices", g.emp, nil); strings.Contains(body, newDev) || code != http.StatusOK {
		t.Errorf("employee lists the new device: %d %s", code, body)
	}
	// Re-assignment before the new device has reported any tank is refused: no invented tanks.
	if code, body := g.set(t, g.platform, g.empID, newDev, 1); code != http.StatusConflict && code != http.StatusNotFound && code != http.StatusServiceUnavailable {
		t.Errorf("grant on a device that never reported: %d %s, want a refusal", code, body)
	}
	if code, body := g.set(t, g.platform, g.empID, newDev, 1); code == http.StatusOK {
		t.Errorf("grant on a never-reported device succeeded: %s", body)
	}
}
