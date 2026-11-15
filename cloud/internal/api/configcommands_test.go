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

// No per-device opening (removed 2026-11-09): the old gate route is gone and device rows no longer carry it.
func TestConfigCommands_NoPerDeviceGate(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-gate"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	if rec := env.authed("POST", "/api/devices/"+device+"/commands", configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "alarmHighLevelMm": 610})); rec.Code != http.StatusAccepted {
		t.Errorf("config command without any opening: %d %s", rec.Code, rec.Body.String())
	}
	platform := env.otherUserToken(control.RolePlatformAdmin)
	if rec := env.do("PUT", "/api/devices/"+device+"/config-commands", []byte(platform), []byte(`{"enabled":true,"reason":"x"}`)); rec.Code != http.StatusNotFound {
		t.Errorf("old gate route: %d, want 404", rec.Code)
	}
	if rec := env.authed("GET", "/api/devices", nil); strings.Contains(rec.Body.String(), "config_commands_enabled") {
		t.Errorf("device list still carries the gate: %s", rec.Body.String())
	}
}

func TestConfigCommands_DeploymentSwitchClosed(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-gate-off"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	rec := env.authed("POST", "/api/devices/"+device+"/commands", configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "alarmHighLevelMm": 610}))
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), `"scope":"deployment"`) {
		t.Errorf("switch closed: %d %s", rec.Code, rec.Body.String())
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

// config.setTankTable (#49): a config write like setTankSettings — submission protection, 400 on bad args,
// and it shares the tank's in-flight slot with setTankSettings.
func TestConfigCommands_TankTable(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-tank-table"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	url := "/api/devices/" + device + "/commands"

	bad := configBody(t, protocol.MethodConfigTankTable, map[string]any{"tank": 1, "shape": "vertical", "heightMm": 2500})
	if rec := env.authed("POST", url, bad); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "diameterMm") {
		t.Errorf("missing dimension: %d %s", rec.Code, rec.Body.String())
	}
	noReq := jsonBody(t, map[string]any{"method": protocol.MethodConfigTankTable, "reason": "r", "expectedStationId": nil,
		"args": map[string]any{"tank": 1, "shape": "vertical", "heightMm": 2500, "diameterMm": 2000}})
	if rec := env.authed("POST", url, noReq); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "request_id_required") {
		t.Errorf("no requestId: %d %s", rec.Code, rec.Body.String())
	}

	ok := configBody(t, protocol.MethodConfigTankTable, map[string]any{"tank": 2, "shape": "horizontal", "diameterMm": 2600, "lengthMm": 8000, "headMm": 600})
	rec := env.authed("POST", url, ok)
	if rec.Code != http.StatusAccepted || !strings.Contains(rec.Body.String(), `"configTarget":"tank:2"`) {
		t.Fatalf("submit: %d %s", rec.Code, rec.Body.String())
	}
	if sender.calls != 1 {
		t.Errorf("sender calls = %d", sender.calls)
	}
	settings := configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 2, "alarmHighLevelMm": 610})
	if rec := env.authed("POST", url, settings); rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "config_command_in_flight") {
		t.Errorf("settings on the same tank while the table is in flight: %d %s", rec.Code, rec.Body.String())
	}
	other := configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 3, "alarmHighLevelMm": 610})
	if rec := env.authed("POST", url, other); rec.Code != http.StatusAccepted {
		t.Errorf("another tank: %d %s", rec.Code, rec.Body.String())
	}

	env.setRole(control.RoleCustomerUser)
	staff := configBody(t, protocol.MethodConfigTankTable, map[string]any{"tank": 4, "shape": "vertical", "heightMm": 2500, "diameterMm": 2000})
	if rec := env.authed("POST", url, staff); rec.Code != http.StatusForbidden {
		t.Errorf("employee: %d %s, want 403", rec.Code, rec.Body.String())
	}
}
