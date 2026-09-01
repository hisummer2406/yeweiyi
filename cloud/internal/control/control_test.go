package control_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// These tests run against the real local PostgreSQL (docker-compose.yml) but use a fake Sender in
// place of ThingsBoard, so every branch of the state machine — including the ones a healthy
// ThingsBoard will not produce on demand, like an outage — is reachable deterministically.
// The full path with a real ThingsBoard and a real device is covered by e2e_test.go.

func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		dsn = "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
	}
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		t.Skipf("no local postgres (%v); start it with: docker compose up -d", err)
	}
	if err := pool.Ping(context.Background()); err != nil {
		pool.Close()
		t.Skipf("no local postgres (%v); start it with: docker compose up -d", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// fakeSender stands in for ThingsBoard.
type fakeSender struct {
	device   tbclient.Device
	getErr   error
	ack      protocol.Ack
	sendErr  error
	lastEnv  protocol.Envelope
	lastMeth string
	calls    int
}

func (f *fakeSender) GetDeviceByName(_ context.Context, name string) (tbclient.Device, error) {
	if f.getErr != nil {
		return tbclient.Device{}, f.getErr
	}
	d := f.device
	d.Name = name
	if d.ID.ID == "" {
		d.ID = tbclient.EntityID{ID: "fake-device-id", EntityType: "DEVICE"}
	}
	return d, nil
}

func (f *fakeSender) SendCommand(_ context.Context, _ string, method string, env protocol.Envelope, _ int64) (protocol.Ack, error) {
	f.calls++
	f.lastMeth, f.lastEnv = method, env
	if f.sendErr != nil {
		return protocol.Ack{}, f.sendErr
	}
	ack := f.ack
	if ack.CommandID == "" {
		ack.CommandID = env.CommandID
	}
	if ack.Status == "" {
		ack.Status = protocol.CmdStatusReceived
	}
	return ack, nil
}

type fixture struct {
	svc    *control.Service
	q      *db.Queries
	sender *fakeSender
	device string
	actor  control.Actor
}

// newFixture seeds a user, a device and a binding, and cleans all of them up afterwards.
func newFixture(t *testing.T, role string, cfg control.Config) *fixture {
	t.Helper()
	pool := testPool(t)
	q := db.New(pool)
	ctx := context.Background()

	suffix := fmt.Sprintf("%s-%d", t.Name(), time.Now().UnixNano())
	if len(suffix) > 60 {
		suffix = suffix[len(suffix)-60:]
	}
	username := "ctl-" + suffix
	deviceName := "ctl-dev-" + suffix

	var userID int64
	err := pool.QueryRow(ctx,
		`INSERT INTO users (username, password_hash, role) VALUES ($1, 'x', $2) RETURNING id`,
		username, role).Scan(&userID)
	if err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if err := q.EnsureDevice(ctx, deviceName); err != nil {
		t.Fatalf("seed device: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO user_devices (user_id, device_name) VALUES ($1, $2)`, userID, deviceName); err != nil {
		t.Fatalf("bind device: %v", err)
	}

	t.Cleanup(func() {
		c := context.Background()
		pool.Exec(c, `DELETE FROM audit_log WHERE device_name = $1 OR actor = $2`, deviceName, username)
		pool.Exec(c, `DELETE FROM control_commands WHERE device_name = $1`, deviceName)
		pool.Exec(c, `DELETE FROM user_devices WHERE user_id = $1`, userID)
		pool.Exec(c, `DELETE FROM devices WHERE device_name = $1`, deviceName)
		pool.Exec(c, `DELETE FROM users WHERE id = $1`, userID)
	})

	sender := &fakeSender{}
	return &fixture{
		svc:    control.New(q, sender, cfg),
		q:      q,
		sender: sender,
		device: deviceName,
		actor:  control.Actor{UserID: userID, Username: username, Role: role},
	}
}

func issueReq(f *fixture, method string) control.IssueRequest {
	return control.IssueRequest{
		DeviceName: f.device,
		Method:     method,
		Args:       json.RawMessage(`{"tank":1}`),
		Reason:     "值班室二次确认",
		Actor:      f.actor,
	}
}

// TestIssue_PhysicalCommandsBlockedByDefault is the safety default that matters most: a deployment
// that configures nothing must not be able to start a pump, because the device-side interlocks are
// not finished (issue #1). A regression here would silently open physical actions to production.
func TestIssue_PhysicalCommandsBlockedByDefault(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{}) // zero config

	_, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodPumpStart))
	if !errors.Is(err, control.ErrPhysicalCommandsDisabled) {
		t.Fatalf("got %v, want ErrPhysicalCommandsDisabled", err)
	}
	if f.sender.calls != 0 {
		t.Error("a blocked command must never reach ThingsBoard")
	}

	// Even a platform admin cannot bypass it: the gate is about unverified hardware, not trust.
	f.actor.Role = control.RolePlatformAdmin
	if _, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodPumpStart)); !errors.Is(err, control.ErrPhysicalCommandsDisabled) {
		t.Errorf("platform admin bypassed the interlock gate: %v", err)
	}

	assertAudit(t, f, "control.issue", "denied")
}

func TestIssue_NonPhysicalCommandAllowedByDefault(t *testing.T) {
	f := newFixture(t, control.RoleCustomerUser, control.Config{})

	cmd, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived {
		t.Errorf("status = %q, want %q", cmd.Status, protocol.CmdStatusReceived)
	}
	if f.sender.lastMeth != protocol.MethodAlarmMute {
		t.Errorf("method sent = %q", f.sender.lastMeth)
	}
	// The operator's justification must reach the device, not just the database.
	if f.sender.lastEnv.Reason != "值班室二次确认" {
		t.Errorf("reason did not reach the device: %q", f.sender.lastEnv.Reason)
	}
	if f.sender.lastEnv.ExpiresAtMs <= f.sender.lastEnv.IssuedAtMs {
		t.Error("envelope carried no valid expiry window")
	}
}

func TestIssue_RequiresReason(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})

	req := issueReq(f, protocol.MethodAlarmMute)
	req.Reason = ""

	if _, err := f.svc.Issue(context.Background(), req); !errors.Is(err, control.ErrReasonRequired) {
		t.Fatalf("got %v, want ErrReasonRequired", err)
	}
	if f.sender.calls != 0 {
		t.Error("a command with no reason must never reach ThingsBoard")
	}
}

func TestIssue_RejectsUnknownMethod(t *testing.T) {
	f := newFixture(t, control.RolePlatformAdmin, control.Config{AllowPhysicalCommands: true})

	if _, err := f.svc.Issue(context.Background(), issueReq(f, "pump.explode")); !errors.Is(err, control.ErrUnknownMethod) {
		t.Fatalf("got %v, want ErrUnknownMethod", err)
	}
	if f.sender.calls != 0 {
		t.Error("an unknown method must be rejected before it reaches ThingsBoard")
	}
}

// TestIssue_RoleGatesAdminMethods pins the permission tiers.
func TestIssue_RoleGatesAdminMethods(t *testing.T) {
	cases := []struct {
		role    string
		allowed bool
	}{
		{control.RoleCustomerUser, false},
		{control.RoleCustomerAdmin, true},
		{control.RolePlatformAdmin, true},
	}

	for _, tc := range cases {
		t.Run(tc.role, func(t *testing.T) {
			f := newFixture(t, tc.role, control.Config{})
			_, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodConfigAlarmThreshold))

			if tc.allowed && err != nil {
				t.Fatalf("%s should be allowed to set thresholds: %v", tc.role, err)
			}
			if !tc.allowed && !errors.Is(err, control.ErrForbidden) {
				t.Fatalf("%s got %v, want ErrForbidden", tc.role, err)
			}
		})
	}
}

// TestIssue_ForbiddenForUnboundDevice covers device-scope authorization.
func TestIssue_ForbiddenForUnboundDevice(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})

	req := issueReq(f, protocol.MethodAlarmMute)
	req.DeviceName = "someone-elses-device"

	if _, err := f.svc.Issue(context.Background(), req); !errors.Is(err, control.ErrForbidden) {
		t.Fatalf("got %v, want ErrForbidden", err)
	}
	if f.sender.calls != 0 {
		t.Error("an unauthorized command must never reach ThingsBoard")
	}
	// A denied attempt against a device that may not even exist is exactly what the audit trail
	// is for, so it must have been recorded.
	assertAudit(t, f, "control.issue", "denied")
}

// TestIssue_DeviceRejectionIsRecordedNotErrored: a device refusing is information, not a failure.
func TestIssue_DeviceRejectionIsRecorded(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	f.sender.ack = protocol.Ack{
		Status:    protocol.CmdStatusRejected,
		ErrorCode: protocol.ErrInterlockBlock,
		Reason:    "液位低于下限，禁止启泵",
	}

	cmd, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("a device rejection must not be a Go error: %v", err)
	}
	if cmd.Status != protocol.CmdStatusRejected {
		t.Errorf("status = %q, want %q", cmd.Status, protocol.CmdStatusRejected)
	}
	if cmd.ErrorCode.String != protocol.ErrInterlockBlock {
		t.Errorf("errorCode = %q", cmd.ErrorCode.String)
	}
	if !cmd.FinishedAt.Valid {
		t.Error("a rejection is terminal and must set finished_at")
	}
}

// TestIssue_UnacknowledgedCommandIsNotMarkedFailed is the unsafe-direction guard. A device that
// does not answer may still have acted; marking such a command failed would tell an operator the
// pump did not start when it might have.
func TestIssue_UnacknowledgedCommandIsNotMarkedFailed(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	f.sender.sendErr = fmt.Errorf("%w: no session", tbclient.ErrDeviceOffline)

	cmd, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusSent {
		t.Fatalf("status = %q, want %q — an unacknowledged command must stay non-terminal", cmd.Status, protocol.CmdStatusSent)
	}
	if protocol.IsTerminal(cmd.Status) {
		t.Error("an unacknowledged command must not be in a terminal state")
	}
}

// TestIssue_PlatformOutageLeavesCommandPending: if ThingsBoard never took the request, the command
// was not handed over at all.
func TestIssue_PlatformOutageLeavesCommandPending(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	f.sender.sendErr = &tbclient.Error{StatusCode: 503, Method: "POST", Path: "/api/rpc/twoway/x", Body: "down"}

	cmd, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if !errors.Is(err, control.ErrPlatformUnavailable) {
		t.Fatalf("got %v, want ErrPlatformUnavailable", err)
	}
	if cmd.Status != protocol.CmdStatusPending {
		t.Errorf("status = %q, want %q", cmd.Status, protocol.CmdStatusPending)
	}
}

// TestApplyResult_ClosesTheStateMachine covers the asynchronous outcome path.
func TestApplyResult_ClosesTheStateMachine(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	ctx := context.Background()

	cmd, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	commandID := f.sender.lastEnv.CommandID

	updated, applied, err := f.svc.ApplyResult(ctx, f.device, protocol.CmdResult{
		CommandID: commandID,
		Status:    protocol.CmdStatusSucceeded,
		Detail:    json.RawMessage(`{"muted":true}`),
		TSMs:      time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("ApplyResult: %v", err)
	}
	if !applied {
		t.Fatal("the result should have applied")
	}
	if updated.Status != protocol.CmdStatusSucceeded {
		t.Errorf("status = %q, want %q", updated.Status, protocol.CmdStatusSucceeded)
	}
	if !updated.FinishedAt.Valid {
		t.Error("a terminal status must set finished_at")
	}
	_ = cmd
}

// TestApplyResult_DuplicateDoesNotRewriteTerminalState is the guard that makes redelivery safe.
func TestApplyResult_DuplicateDoesNotRewriteTerminalState(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	ctx := context.Background()

	if _, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute)); err != nil {
		t.Fatalf("Issue: %v", err)
	}
	commandID := f.sender.lastEnv.CommandID

	first := protocol.CmdResult{CommandID: commandID, Status: protocol.CmdStatusSucceeded, TSMs: time.Now().UnixMilli()}
	if _, applied, err := f.svc.ApplyResult(ctx, f.device, first); err != nil || !applied {
		t.Fatalf("first apply: applied=%v err=%v", applied, err)
	}

	// A redelivery claiming the opposite outcome must not overwrite the terminal record.
	second := protocol.CmdResult{CommandID: commandID, Status: protocol.CmdStatusFailed, ErrorCode: protocol.ErrHardwareFault, TSMs: time.Now().UnixMilli()}
	_, applied, err := f.svc.ApplyResult(ctx, f.device, second)
	if err != nil {
		t.Fatalf("second apply: %v", err)
	}
	if applied {
		t.Error("a second result must not apply to an already-terminal command")
	}

	stored := getCommand(t, f, commandID)
	if stored.Status != protocol.CmdStatusSucceeded {
		t.Errorf("terminal status was overwritten: %q", stored.Status)
	}
}

func TestApplyResult_UnknownCommandIsIgnored(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})

	_, applied, err := f.svc.ApplyResult(context.Background(), f.device, protocol.CmdResult{
		CommandID: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
		Status:    protocol.CmdStatusSucceeded,
	})
	if err != nil {
		t.Fatalf("an unknown commandId must not be an error: %v", err)
	}
	if applied {
		t.Error("nothing should have been applied")
	}
}

func TestApplyResult_RejectsStatusesOnlyTheBackendMayWrite(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})

	// A device must not be able to declare a command "expired" or "received" through the result
	// channel — those transitions belong to the backend and the ACK respectively.
	for _, status := range []string{protocol.CmdStatusExpired, protocol.CmdStatusReceived, protocol.CmdStatusSent, protocol.CmdStatusPending} {
		_, _, err := f.svc.ApplyResult(context.Background(), f.device, protocol.CmdResult{
			CommandID: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
			Status:    status,
		})
		if err == nil {
			t.Errorf("a device reporting status %q should be rejected", status)
		}
	}
}

// TestExpireStale_ClosesCommandsPastTheirWindow covers the cloud-side half of the validity window.
func TestExpireStale_ClosesCommandsPastTheirWindow(t *testing.T) {
	// A 1ms TTL so the command is already expired by the time the sweep runs.
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{CommandTTL: time.Millisecond})
	ctx := context.Background()
	f.sender.sendErr = fmt.Errorf("%w: no session", tbclient.ErrDeviceOffline)

	if _, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute)); err != nil {
		t.Fatalf("Issue: %v", err)
	}
	commandID := f.sender.lastEnv.CommandID

	time.Sleep(20 * time.Millisecond)
	if _, err := f.svc.ExpireStale(ctx); err != nil {
		t.Fatalf("ExpireStale: %v", err)
	}

	stored := getCommand(t, f, commandID)
	if stored.Status != protocol.CmdStatusExpired {
		t.Fatalf("status = %q, want %q", stored.Status, protocol.CmdStatusExpired)
	}

	// And a result arriving after expiry must not resurrect it.
	_, applied, err := f.svc.ApplyResult(ctx, f.device, protocol.CmdResult{
		CommandID: commandID, Status: protocol.CmdStatusSucceeded, TSMs: time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("late result: %v", err)
	}
	if applied {
		t.Error("a result arriving after expiry must not resurrect the command")
	}
	if got := getCommand(t, f, commandID); got.Status != protocol.CmdStatusExpired {
		t.Errorf("status after late result = %q, want expired", got.Status)
	}
}

// --- helpers ---

func getCommand(t *testing.T, f *fixture, commandID string) db.ControlCommand {
	t.Helper()
	cmds, err := f.q.ListControlCommandsForDevice(context.Background(), db.ListControlCommandsForDeviceParams{
		DeviceName: f.device, Limit: 50, Offset: 0,
	})
	if err != nil {
		t.Fatalf("list commands: %v", err)
	}
	for _, c := range cmds {
		if uuidText(c.CommandID.Bytes) == commandID {
			return c
		}
	}
	t.Fatalf("command %s not found among %d rows", commandID, len(cmds))
	return db.ControlCommand{}
}

func uuidText(b [16]byte) string {
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func assertAudit(t *testing.T, f *fixture, action, outcome string) {
	t.Helper()
	rows, err := f.q.ListAuditLog(context.Background(), db.ListAuditLogParams{
		DeviceName: pgtype.Text{}, // NULL: list across all devices
		BeginTime:  pgtype.Timestamptz{Time: time.Now().Add(-time.Minute), Valid: true},
		EndTime:    pgtype.Timestamptz{Time: time.Now().Add(time.Minute), Valid: true},
		Lim:        200,
		Off:        0,
	})
	if err != nil {
		t.Fatalf("list audit: %v", err)
	}
	for _, r := range rows {
		if r.Actor == f.actor.Username && r.Action == action && r.Outcome == outcome {
			return
		}
	}
	t.Errorf("no audit row for actor=%s action=%s outcome=%s", f.actor.Username, action, outcome)
}
