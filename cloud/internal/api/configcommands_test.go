package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
)

// config-command-delivery (#3, docs/config-command-contract.md): the REST surface of the submission
// protection. The rules themselves are tested against the database in internal/control.

func decode(t *testing.T, body []byte, v any) {
	t.Helper()
	if err := json.Unmarshal(body, v); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
}

func TestConfigCommands_ReplayIs200AndLookupsFindTheCommand(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-replay"
	env.ownDevice(device)
	env.openConfigGate(device)
	env.setRole(control.RoleCustomerAdmin)
	url := "/api/devices/" + device + "/commands"

	body := jsonBody(t, map[string]any{"method": protocol.MethodConfigTankSettings, "args": map[string]any{"tank": 1, "alarmHighLevelMm": 610},
		"reason": "调整", "requestId": "replay-1", "expectedStationId": nil})
	rec := env.authed("POST", url, body)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("first: %d %s", rec.Code, rec.Body.String())
	}
	var first struct {
		CommandID         string  `json:"commandId"`
		RequestID         *string `json:"requestId"`
		ConfigTarget      *string `json:"configTarget"`
		ExpectedStationID *int64  `json:"expectedStationId"`
	}
	decode(t, rec.Body.Bytes(), &first)
	if first.RequestID == nil || *first.RequestID != "replay-1" || first.ConfigTarget == nil || *first.ConfigTarget != "tank:1" || first.ExpectedStationID != nil {
		t.Errorf("submission context in response: %s", rec.Body.String())
	}

	rec = env.authed("POST", url, body)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), first.CommandID) {
		t.Fatalf("replay: %d %s, want 200 with the same command", rec.Code, rec.Body.String())
	}
	if sender.calls != 1 {
		t.Errorf("sender calls = %d", sender.calls)
	}

	// By id.
	rec = env.authed("GET", url+"/"+first.CommandID, nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"terminal":false`) {
		t.Errorf("by id: %d %s", rec.Code, rec.Body.String())
	}
	// Not a UUID, unknown, or another device's path: one 404.
	other := "ctl-api-replay-other"
	env.ownDevice(other)
	for _, path := range []string{url + "/not-a-uuid", url + "/00000000-0000-0000-0000-000000000000", "/api/devices/" + other + "/commands/" + first.CommandID} {
		if rec := env.authed("GET", path, nil); rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "command_not_found") {
			t.Errorf("%s: %d %s, want 404 command_not_found", path, rec.Code, rec.Body.String())
		}
	}
	// By requestId: own submission only, list shape.
	rec = env.authed("GET", url+"?requestId=replay-1", nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), first.CommandID) {
		t.Errorf("by requestId: %d %s", rec.Code, rec.Body.String())
	}
	otherAdmin := env.otherUserToken(control.RoleCustomerAdmin)
	rec = env.do("GET", url+"?requestId=replay-1", []byte(otherAdmin), nil)
	if rec.Code != http.StatusOK || strings.TrimSpace(rec.Body.String()) != `{"commands":[]}` {
		t.Errorf("another admin by my requestId: %d %s, want an empty list", rec.Code, rec.Body.String())
	}

	// Changed content under the same requestId.
	changed := jsonBody(t, map[string]any{"method": protocol.MethodConfigTankSettings, "args": map[string]any{"tank": 1, "alarmHighLevelMm": 620},
		"reason": "调整", "requestId": "replay-1", "expectedStationId": nil})
	if rec := env.authed("POST", url, changed); rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "request_id_reused") {
		t.Errorf("reused: %d %s", rec.Code, rec.Body.String())
	}
	// Same tank, new requestId: the first is still in flight.
	busy := jsonBody(t, map[string]any{"method": protocol.MethodConfigTankSettings, "args": map[string]any{"tank": 1, "alarmHighLevelMm": 620},
		"reason": "调整", "requestId": "replay-2", "expectedStationId": nil})
	rec = env.authed("POST", url, busy)
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "config_command_in_flight") || !strings.Contains(rec.Body.String(), first.CommandID) {
		t.Errorf("in flight: %d %s", rec.Code, rec.Body.String())
	}
}

func TestConfigCommands_SubmissionFieldErrors(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-fields"
	env.ownDevice(device)
	env.openConfigGate(device)
	env.setRole(control.RoleCustomerAdmin)
	url := "/api/devices/" + device + "/commands"
	base := func() map[string]any {
		return map[string]any{"method": protocol.MethodConfigTankSettings, "args": map[string]any{"tank": 1, "alarmHighLevelMm": 610}, "reason": "r"}
	}

	b := base()
	b["expectedStationId"] = nil
	if rec := env.authed("POST", url, jsonBody(t, b)); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "request_id_required") {
		t.Errorf("no requestId: %d %s", rec.Code, rec.Body.String())
	}
	b = base()
	b["requestId"] = "x1"
	if rec := env.authed("POST", url, jsonBody(t, b)); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "expected_station_required") {
		t.Errorf("no expectedStationId: %d %s", rec.Code, rec.Body.String())
	}
	b["expectedStationId"] = "abc"
	if rec := env.authed("POST", url, jsonBody(t, b)); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "expected_station_invalid") {
		t.Errorf("bad expectedStationId: %d %s", rec.Code, rec.Body.String())
	}
	// A station the device is not on.
	b["expectedStationId"] = 999999999
	rec := env.authed("POST", url, jsonBody(t, b))
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), `"code":"device_station_changed"`) || !strings.Contains(rec.Body.String(), `"currentStationId":null`) {
		t.Errorf("station changed: %d %s", rec.Code, rec.Body.String())
	}
}

func TestConfigCommands_DeviceGate(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-gate"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	cmdURL := "/api/devices/" + device + "/commands"
	gateURL := "/api/devices/" + device + "/config-commands"

	rec := env.authed("POST", cmdURL, configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "alarmHighLevelMm": 610}))
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), `"scope":"device"`) {
		t.Fatalf("closed device: %d %s", rec.Code, rec.Body.String())
	}

	// A customer administrator cannot open it; the refusal comes before the body is read.
	if rec := env.authed("PUT", gateURL, []byte(`not json`)); rec.Code != http.StatusForbidden {
		t.Errorf("customer admin opening the gate: %d %s", rec.Code, rec.Body.String())
	}

	platform := env.otherUserToken(control.RolePlatformAdmin)
	if rec := env.do("PUT", gateURL, []byte(platform), []byte(`{"enabled":true}`)); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "reason_required") {
		t.Errorf("no reason: %d %s", rec.Code, rec.Body.String())
	}
	for i := 0; i < 2; i++ { // idempotent
		rec := env.do("PUT", gateURL, []byte(platform), []byte(`{"enabled":true,"reason":"真机验证"}`))
		if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"enabled":true`) {
			t.Fatalf("open %d: %d %s", i, rec.Code, rec.Body.String())
		}
	}
	if rec := env.do("PUT", "/api/devices/no-such-device-xyz/config-commands", []byte(platform), []byte(`{"enabled":true,"reason":"x"}`)); rec.Code != http.StatusNotFound {
		t.Errorf("unknown device: %d %s", rec.Code, rec.Body.String())
	}
	var audits int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM audit_log WHERE device_name = $1 AND action = 'device.config_commands' AND outcome = 'allowed'`, device).Scan(&audits)
	if audits != 2 {
		t.Errorf("gate audit rows = %d, want 2", audits)
	}

	// The device list tells administrators, not employees.
	rec = env.authed("GET", "/api/devices", nil)
	if !strings.Contains(rec.Body.String(), `"config_commands_enabled":true`) {
		t.Errorf("admin device list: %s", rec.Body.String())
	}
	if rec := env.authed("POST", cmdURL, configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "alarmHighLevelMm": 610})); rec.Code != http.StatusAccepted {
		t.Errorf("open device: %d %s", rec.Code, rec.Body.String())
	}

	// Moving the device to another customer closes it.
	other := env.newCustomer("gate")
	t.Cleanup(func() { // before the customer's own cleanup (LIFO)
		env.pool.Exec(context.Background(), `UPDATE devices SET customer_id = `+defaultCustomerSQL+` WHERE device_name = $1`, device)
	})
	if _, err := env.pool.Exec(context.Background(), `DELETE FROM user_tank_grants WHERE device_name = $1`, device); err != nil {
		t.Fatal(err)
	}
	if rec := env.do("PUT", "/api/devices/"+device+"/customer", []byte(platform), jsonBody(t, map[string]any{"customerId": other})); rec.Code != http.StatusOK && rec.Code != http.StatusNoContent {
		t.Fatalf("transfer: %d %s", rec.Code, rec.Body.String())
	}
	var open bool
	env.pool.QueryRow(context.Background(), `SELECT config_commands_enabled_at IS NOT NULL FROM devices WHERE device_name = $1`, device).Scan(&open)
	if open {
		t.Error("the gate survived a customer change")
	}
	env.setRole(control.RoleCustomerUser)
	if rec := env.authed("GET", "/api/devices", nil); strings.Contains(rec.Body.String(), "config_commands_enabled") {
		t.Errorf("employee device list carries the gate: %s", rec.Body.String())
	}
}

func TestConfigCommands_DeviceSettingsMirror(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-devsettings"
	env.ownDevice(device)
	url := "/api/devices/" + device + "/device-settings"

	env.setRole(control.RoleCustomerAdmin)
	if rec := env.authed("GET", url, nil); rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "device_settings_not_reported") {
		t.Errorf("never reported: %d %s", rec.Code, rec.Body.String())
	}
	if _, err := env.pool.Exec(context.Background(),
		`INSERT INTO device_settings (device_name, in_stock_height_threshold_mm, out_stock_time_threshold_sec, reported_at) VALUES ($1, 20, 0, now())`, device); err != nil {
		t.Fatal(err)
	}
	rec := env.authed("GET", url, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("admin: %d %s", rec.Code, rec.Body.String())
	}
	var got map[string]any
	decode(t, rec.Body.Bytes(), &got)
	if got["inStockHeightThresholdMm"] != float64(20) || got["outStockTimeThresholdSec"] != float64(0) || got["inStockTimeThresholdSec"] != nil {
		t.Errorf("mirror: %s (0 must stay 0, unreported must be null)", rec.Body.String())
	}

	env.setRole(control.RoleCustomerUser)
	if rec := env.authed("GET", url, nil); rec.Code != http.StatusForbidden {
		t.Errorf("employee: %d %s, want 403", rec.Code, rec.Body.String())
	}
}
