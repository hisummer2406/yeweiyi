package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// Tests for the remote-control REST surface. ThingsBoard is stood in for by a fake Sender, so every
// branch — including a platform outage and a device refusing — is deterministic. The same flow
// against a real ThingsBoard and a real device is covered by internal/ingest's e2e tests.

type stubSender struct {
	ack     protocol.Ack
	sendErr error
	getErr  error
	calls   int
}

func (s *stubSender) GetDeviceByName(_ context.Context, name string) (tbclient.Device, error) {
	if s.getErr != nil {
		return tbclient.Device{}, s.getErr
	}
	return tbclient.Device{ID: tbclient.EntityID{ID: "stub-id", EntityType: "DEVICE"}, Name: name}, nil
}

func (s *stubSender) SendCommand(_ context.Context, _ string, _ string, env protocol.Envelope, _ int64) (protocol.Ack, error) {
	s.calls++
	if s.sendErr != nil {
		return protocol.Ack{}, s.sendErr
	}
	ack := s.ack
	if ack.CommandID == "" {
		ack.CommandID = env.CommandID
	}
	if ack.Status == "" {
		ack.Status = protocol.CmdStatusReceived
	}
	return ack, nil
}

// newControlEnv builds an environment with control wired to a stub ThingsBoard.
func newControlEnv(t *testing.T, cfg control.Config) (*testEnv, *stubSender) {
	t.Helper()
	sender := &stubSender{}
	env := newTestEnvWith(t, func(d *api.Deps) {
		d.Control = control.New(d.Queries, sender, cfg)
	})
	t.Cleanup(func() {
		env.pool.Exec(context.Background(), `DELETE FROM audit_log WHERE actor = $1`, env.username)
		env.pool.Exec(context.Background(), `DELETE FROM control_commands WHERE operator = $1`, env.username)
	})
	return env, sender
}

func issueBody(t *testing.T, method, reason string) []byte {
	t.Helper()
	return jsonBody(t, map[string]any{
		"method": method,
		"args":   map[string]any{"tank": 1},
		"reason": reason,
	})
}

func TestIssueCommand_Returns202AndReceivedStatus(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	device := "ctl-api-ok"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "值班室二次确认"))

	// 202, not 200: the response says how far the command got synchronously, not that it worked.
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}

	var got struct {
		CommandID string `json:"commandId"`
		Status    string `json:"status"`
		Terminal  bool   `json:"terminal"`
		Reason    string `json:"reason"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Status != protocol.CmdStatusReceived {
		t.Errorf("status = %q, want %q", got.Status, protocol.CmdStatusReceived)
	}
	if got.Terminal {
		t.Error("an ACK is not terminal; a client must know to keep polling")
	}
	if got.CommandID == "" || len(got.CommandID) != 36 {
		t.Errorf("commandId = %q, want a UUID", got.CommandID)
	}
	if got.Reason != "值班室二次确认" {
		t.Errorf("reason = %q", got.Reason)
	}
	if sender.calls != 1 {
		t.Errorf("ThingsBoard calls = %d, want 1", sender.calls)
	}
}

// TestIssueCommand_PhysicalBlockedReturns409 pins the interlock gate at the HTTP layer.
func TestIssueCommand_PhysicalBlockedReturns409(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{}) // physical commands disabled by default
	device := "ctl-api-physical"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodPumpStart, "补油"))

	// 409, not 403: the caller may well be entitled; it is the deployment that refuses.
	if rec.Code != http.StatusConflict {
		t.Fatalf("expected 409, got %d: %s", rec.Code, rec.Body.String())
	}
	if sender.calls != 0 {
		t.Error("a blocked command must never reach ThingsBoard")
	}
}

func TestIssueCommand_PhysicalAllowedWhenExplicitlyEnabled(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{AllowPhysicalCommands: true})
	device := "ctl-api-physical-on"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodPumpStart, "补油"))
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}
	if sender.calls != 1 {
		t.Errorf("ThingsBoard calls = %d, want 1", sender.calls)
	}
}

func TestIssueCommand_RequiresReason(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	device := "ctl-api-noreason"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	// No reason field at all: gin's binding:"required" rejects it before control is reached.
	body := jsonBody(t, map[string]any{"method": protocol.MethodAlarmMute})
	rec := env.authed("POST", "/api/devices/"+device+"/commands", body)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
	if sender.calls != 0 {
		t.Error("a command with no reason must never reach ThingsBoard")
	}
}

func TestIssueCommand_UnknownMethodIs400(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-unknown"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, "pump.explode", "试试"))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestIssueCommand_ForbiddenForUnownedDevice covers device-scope authorization, and that the
// response does not distinguish "someone else's device" from "no such device".
func TestIssueCommand_ForbiddenForUnownedDevice(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	env.setRole(control.RoleCustomerAdmin)

	unowned := env.authed("POST", "/api/devices/ctl-api-not-mine/commands", issueBody(t, protocol.MethodAlarmMute, "越权"))
	nonexistent := env.authed("POST", "/api/devices/ctl-api-does-not-exist-at-all/commands", issueBody(t, protocol.MethodAlarmMute, "越权"))

	if unowned.Code != http.StatusForbidden {
		t.Fatalf("unowned device: expected 403, got %d: %s", unowned.Code, unowned.Body.String())
	}
	if nonexistent.Code != unowned.Code {
		t.Errorf("a nonexistent device answered %d but an unowned one answered %d — "+
			"differing codes let a caller enumerate real device names",
			nonexistent.Code, unowned.Code)
	}
	if sender.calls != 0 {
		t.Error("an unauthorized command must never reach ThingsBoard")
	}
}

// TestIssueCommand_RoleGatesAdminMethods: a plain customer user may not change alarm thresholds.
func TestIssueCommand_RoleGatesAdminMethods(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-role"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerUser)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodConfigAlarmThreshold, "调阈值"))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for a customer_user, got %d: %s", rec.Code, rec.Body.String())
	}

	// An employee is view-only (cloud#63): a grant on one tank must not let them act on the whole
	// device, so even a control-tier method is refused.
	if rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "消音")); rec.Code != http.StatusForbidden {
		t.Errorf("customer_user muting: got %d: %s, want 403", rec.Code, rec.Body.String())
	}
	// A customer admin still can.
	env.setRole(control.RoleCustomerAdmin)
	if ok := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "消音")); ok.Code != http.StatusAccepted {
		t.Errorf("customer_admin should be allowed to mute: got %d: %s", ok.Code, ok.Body.String())
	}
}

// TestIssueCommand_PlatformOutageIs503 pins the degradation behaviour issue #10 requires: a
// ThingsBoard outage must be distinguishable from a bad request or a server bug.
func TestIssueCommand_PlatformOutageIs503(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	device := "ctl-api-outage"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	sender.sendErr = &tbclient.Error{StatusCode: 503, Method: "POST", Path: "/api/rpc/twoway/x", Body: "down"}

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "消音"))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestIssueCommand_DeviceRejectionIs202WithRejectedStatus: a refusal is information the operator
// must see, delivered as a normal response rather than an HTTP error.
func TestIssueCommand_DeviceRejectionIs202WithRejectedStatus(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	device := "ctl-api-reject"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)
	sender.ack = protocol.Ack{
		Status:    protocol.CmdStatusRejected,
		ErrorCode: protocol.ErrInterlockBlock,
		Reason:    "液位低于下限，禁止启泵",
	}

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "消音"))
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}

	var got struct {
		Status      string `json:"status"`
		Terminal    bool   `json:"terminal"`
		ErrorCode   string `json:"errorCode"`
		ErrorReason string `json:"errorReason"`
	}
	json.Unmarshal(rec.Body.Bytes(), &got)

	if got.Status != protocol.CmdStatusRejected {
		t.Errorf("status = %q, want %q", got.Status, protocol.CmdStatusRejected)
	}
	if !got.Terminal {
		t.Error("a rejection is terminal; a client polling forever would be a bug")
	}
	if got.ErrorCode != protocol.ErrInterlockBlock || got.ErrorReason == "" {
		t.Errorf("the device's refusal reason must reach the operator: %+v", got)
	}
}

func TestIssueCommand_ReturnsUnavailableWhenControlNotConfigured(t *testing.T) {
	env := newTestEnv(t) // no Control wired
	device := "ctl-api-unconfigured"
	env.ownDevice(device)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "消音"))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListDeviceCommands_ReturnsHistoryMostRecentFirst(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-list"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	for _, reason := range []string{"第一条", "第二条", "第三条"} {
		if rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, reason)); rec.Code != http.StatusAccepted {
			t.Fatalf("issue %s: %d: %s", reason, rec.Code, rec.Body.String())
		}
	}

	rec := env.authed("GET", "/api/devices/"+device+"/commands", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var got struct {
		Commands []struct {
			Reason string          `json:"reason"`
			Args   json.RawMessage `json:"args"`
			Status string          `json:"status"`
		} `json:"commands"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got.Commands) != 3 {
		t.Fatalf("got %d commands, want 3", len(got.Commands))
	}
	if got.Commands[0].Reason != "第三条" {
		t.Errorf("most recent first: got %q", got.Commands[0].Reason)
	}
	// args must be readable JSON, not base64 — the sqlc row's []byte would encode that way.
	if string(got.Commands[0].Args) != `{"tank":1}` {
		t.Errorf("args = %s, want readable JSON", got.Commands[0].Args)
	}
}

// TestListDeviceCommands_PlatformAdminCanPollUnboundDevice is the regression test for the P1 found
// in review of PR #11: internal/control.Service.authorize already lets a platform admin operate on
// any device — scoped to all of them by definition — but requireDeviceOwnership (devices.go), which
// every device-scoped READ handler calls including this status-polling endpoint, only ever checked
// user_devices with no exception for role. A platform admin could therefore successfully issue a
// command against a device nobody had bound them to, and then get 403 from the only endpoint that
// lets them poll for that command's asynchronous result.
func TestListDeviceCommands_PlatformAdminCanPollUnboundDevice(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-admin-unbound"
	env.seedDevice(device) // exists, but deliberately never bound to this user via user_devices
	env.setRole(control.RolePlatformAdmin)

	issued := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "平台管理员跨设备处置"))
	if issued.Code != http.StatusAccepted {
		t.Fatalf("platform admin issuing against an unbound device: expected 202, got %d: %s", issued.Code, issued.Body.String())
	}

	rec := env.authed("GET", "/api/devices/"+device+"/commands", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("platform admin polling an unbound device's commands: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var got struct {
		Commands []struct {
			Reason string `json:"reason"`
		} `json:"commands"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got.Commands) != 1 || got.Commands[0].Reason != "平台管理员跨设备处置" {
		t.Errorf("got %+v, want the just-issued command", got.Commands)
	}

	// The fix lives in the shared requireDeviceOwnership helper, not a one-off patch on this
	// handler — spot-check one more of its callers so a narrower fix would fail here too.
	audit := env.authed("GET", "/api/audit?device="+device, nil)
	if audit.Code != http.StatusOK {
		t.Errorf("platform admin querying /api/audit for an unbound device: expected 200, got %d: %s", audit.Code, audit.Body.String())
	}
}

func TestListDeviceCommands_ForbiddenForUnownedDevice(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	if rec := env.authed("GET", "/api/devices/ctl-api-list-not-mine/commands", nil); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestListAudit_RecordsTheWholeCommandLifecycle checks the audit endpoint returns the trail an
// operator or auditor needs.
func TestListAudit_RecordsTheWholeCommandLifecycle(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-audit"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	if rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "审计验证")); rec.Code != http.StatusAccepted {
		t.Fatalf("issue: %d: %s", rec.Code, rec.Body.String())
	}

	rec := env.authed("GET", "/api/audit?device="+device, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var got struct {
		Audit []struct {
			Actor   string `json:"actor"`
			Action  string `json:"action"`
			Outcome string `json:"outcome"`
		} `json:"audit"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}

	seen := map[string]bool{}
	for _, a := range got.Audit {
		if a.Actor == env.username {
			seen[a.Action] = true
		}
	}
	for _, want := range []string{"control.issue", "control.ack"} {
		if !seen[want] {
			t.Errorf("audit is missing %q (have %v)", want, seen)
		}
	}
}

// TestListAudit_ScopeByRole: the audit log spans customers, so only a platform admin gets an
// unfiltered listing. A customer admin may list their own customer's trail without naming a device;
// a customer user must name one they can access.
func TestListAudit_ScopeByRole(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})

	env.setRole(control.RoleCustomerUser)
	// 403 is kept for compatibility, but the body must not be the bare "not authorized" (clients log
	// out on that) and must carry a code so the client can show something better than a fallback (#79).
	rec := env.authed("GET", "/api/audit", nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("a customer user without a device filter: expected 403, got %d: %s", rec.Code, rec.Body.String())
	}
	var body struct{ Error, Code string }
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v: %s", err, rec.Body.String())
	}
	if body.Code != "device_required" || body.Error == "not authorized" {
		t.Errorf("a customer user without a device filter: want code device_required and a non-session body, got %s", rec.Body.String())
	}

	env.setRole(control.RoleCustomerAdmin)
	if rec := env.authed("GET", "/api/audit", nil); rec.Code != http.StatusOK {
		t.Errorf("a customer admin may list their own customer's audit trail: got %d: %s", rec.Code, rec.Body.String())
	}

	env.setRole(control.RolePlatformAdmin)
	if rec := env.authed("GET", "/api/audit", nil); rec.Code != http.StatusOK {
		t.Errorf("a platform admin may list across devices: got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListAudit_ForbiddenForUnownedDevice(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	env.setRole(control.RoleCustomerAdmin)

	if rec := env.authed("GET", "/api/audit?device=ctl-api-audit-not-mine", nil); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestControlRoutes_RequireAuthentication(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})

	for _, tc := range []struct{ method, path string }{
		{"POST", "/api/devices/x/commands"},
		{"GET", "/api/devices/x/commands"},
		{"GET", "/api/audit"},
		{"GET", "/api/devices/x/telemetry/latest"},
	} {
		if rec := env.do(tc.method, tc.path, nil, nil); rec.Code != http.StatusUnauthorized {
			t.Errorf("%s %s: expected 401, got %d", tc.method, tc.path, rec.Code)
		}
	}
}

// TestLatestTelemetry_ReturnsUnavailableWhenThingsBoardNotConfigured covers the boot-without-
// ThingsBoard mode: the route degrades explicitly instead of panicking on a nil client.
func TestLatestTelemetry_ReturnsUnavailableWhenThingsBoardNotConfigured(t *testing.T) {
	env := newTestEnv(t)
	device := "ctl-api-telemetry-unconfigured"
	env.ownDevice(device)

	rec := env.authed("GET", "/api/devices/"+device+"/telemetry/latest", nil)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d: %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"code":"device_platform_unavailable"`) {
		t.Fatalf("503 must carry device_platform_unavailable: %s", rec.Body.String())
	}
}

// TestCommandStatusReflectsAsynchronousResult ties the REST surface to the state machine: the
// status a client polls must change once the device reports its outcome.
func TestCommandStatusReflectsAsynchronousResult(t *testing.T) {
	env, _ := newControlEnv(t, control.Config{})
	device := "ctl-api-async"
	env.ownDevice(device)
	env.setRole(control.RoleCustomerAdmin)

	rec := env.authed("POST", "/api/devices/"+device+"/commands", issueBody(t, protocol.MethodAlarmMute, "异步结果验证"))
	if rec.Code != http.StatusAccepted {
		t.Fatalf("issue: %d: %s", rec.Code, rec.Body.String())
	}
	var issued struct {
		CommandID string `json:"commandId"`
		Status    string `json:"status"`
	}
	json.Unmarshal(rec.Body.Bytes(), &issued)
	if issued.Status != protocol.CmdStatusReceived {
		t.Fatalf("status = %q, want received", issued.Status)
	}

	// Simulate what internal/ingest does when the device's cmdResult arrives.
	svc := control.New(env.queries, &stubSender{}, control.Config{})
	if _, applied, err := svc.ApplyResult(context.Background(), device, protocol.CmdResult{
		CommandID: issued.CommandID,
		Status:    protocol.CmdStatusSucceeded,
	}); err != nil || !applied {
		t.Fatalf("apply result: applied=%v err=%v", applied, err)
	}

	list := env.authed("GET", "/api/devices/"+device+"/commands", nil)
	var got struct {
		Commands []struct {
			CommandID string `json:"commandId"`
			Status    string `json:"status"`
			Terminal  bool   `json:"terminal"`
		} `json:"commands"`
	}
	json.Unmarshal(list.Body.Bytes(), &got)

	var found bool
	for _, cmd := range got.Commands {
		if cmd.CommandID == issued.CommandID {
			found = true
			if cmd.Status != protocol.CmdStatusSucceeded {
				t.Errorf("status = %q, want %q", cmd.Status, protocol.CmdStatusSucceeded)
			}
			if !cmd.Terminal {
				t.Error("succeeded is terminal")
			}
		}
	}
	if !found {
		t.Error("the issued command is missing from the listing")
	}
	_ = db.ControlCommand{}
}

func configBody(t *testing.T, method string, args map[string]any) []byte {
	t.Helper()
	return jsonBody(t, map[string]any{"method": method, "args": args, "reason": "调整配置"})
}

// cloud#71: config.setTankSettings is open to customer administrators, config.setDeviceSettings only to
// platform administrators, bad args are a precise 400 and never reach ThingsBoard.
func TestIssueCommand_ConfigSettings(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{AllowConfigCommands: true})
	device := "ctl-api-config"
	env.ownDevice(device)
	url := "/api/devices/" + device + "/commands"

	env.setRole(control.RoleCustomerAdmin)
	rec := env.authed("POST", url, configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "alarmHighLevelMm": 650}))
	if rec.Code != http.StatusAccepted {
		t.Fatalf("customer admin setTankSettings: %d %s", rec.Code, rec.Body.String())
	}
	calls := sender.calls

	// Device-wide settings are platform-only: the customer administrator gets the usual 403.
	rec = env.authed("POST", url, configBody(t, protocol.MethodConfigDeviceSettings, map[string]any{"outStockHeightThresholdMm": 30}))
	if rec.Code != http.StatusForbidden {
		t.Errorf("customer admin setDeviceSettings: %d %s, want 403", rec.Code, rec.Body.String())
	}

	// Bad args: 400 with a machine-readable code and per-field errors, nothing sent.
	rec = env.authed("POST", url, configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "tankShape": 1, "density": 0}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad args: %d %s, want 400", rec.Code, rec.Body.String())
	}
	var body struct {
		Code   string `json:"code"`
		Fields []struct {
			Field string `json:"field"`
			Code  string `json:"code"`
		} `json:"fields"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil || body.Code != "invalid_args" || len(body.Fields) != 2 {
		t.Errorf("400 body = %s (%v)", rec.Body.String(), err)
	}
	if sender.calls != calls {
		t.Error("invalid args must never reach ThingsBoard")
	}

	// An employee is refused by role before the args are looked at.
	env.setRole(control.RoleCustomerUser)
	rec = env.authed("POST", url, configBody(t, protocol.MethodConfigTankSettings, map[string]any{"tank": 1, "tankShape": 1}))
	if rec.Code != http.StatusForbidden {
		t.Errorf("customer_user: %d %s, want 403 (not 400: arg problems are not shown to the unauthorized)", rec.Code, rec.Body.String())
	}

	env.setRole(control.RolePlatformAdmin)
	rec = env.authed("POST", url, configBody(t, protocol.MethodConfigDeviceSettings, map[string]any{"outStockHeightThresholdMm": 30}))
	if rec.Code != http.StatusAccepted {
		t.Errorf("platform admin setDeviceSettings: %d %s", rec.Code, rec.Body.String())
	}
}

// The config gate is closed by default: both methods answer 409 config_commands_disabled to every role,
// and nothing reaches ThingsBoard.
func TestIssueCommand_ConfigSettingsDisabledByDefault(t *testing.T) {
	env, sender := newControlEnv(t, control.Config{})
	device := "ctl-api-config-off"
	env.ownDevice(device)
	url := "/api/devices/" + device + "/commands"

	env.setRole(control.RolePlatformAdmin)
	for _, m := range []string{protocol.MethodConfigTankSettings, protocol.MethodConfigDeviceSettings} {
		rec := env.authed("POST", url, configBody(t, m, map[string]any{"tank": 1, "outStockHeightThresholdMm": 30}))
		if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "config_commands_disabled") {
			t.Errorf("%s: %d %s, want 409 config_commands_disabled", m, rec.Code, rec.Body.String())
		}
	}
	if sender.calls != 0 {
		t.Error("a blocked config command must never reach ThingsBoard")
	}
}
