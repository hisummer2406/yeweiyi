package ingest_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// cloud#71: remote tank configuration against the device simulator that implements the device's
// config.setTankSettings / config.setDeviceSettings rules (internal/devicesim/settings.go). The whole
// loop runs on real ThingsBoard: issue -> RPC -> ACK -> cmdResult -> command row, and the device's
// re-published client attributes -> tank_settings mirror.

func (s *stack) issue(t *testing.T, actor control.Actor, method, args string) (db.ControlCommand, error) {
	t.Helper()
	return s.ctl.Issue(context.Background(), control.IssueRequest{
		DeviceName: s.device, Method: method, Args: json.RawMessage(args), Reason: "端到端验证", Actor: actor,
		RequestID: fmt.Sprintf("e2e-%d", time.Now().UnixNano()), ExpectedStationSet: true, // the test device is on no station
	})
}

func (s *stack) mirror(t *testing.T) db.TankSetting {
	t.Helper()
	// The device's attributes reach ThingsBoard asynchronously after it connects.
	deadline := time.Now().Add(15 * time.Second)
	for {
		s.ing.SyncTankSettingsForTest(context.Background(), s.device, s.tbDevID)
		rows, err := s.q.ListTankSettings(context.Background(), s.device)
		if err == nil && len(rows) == 1 {
			return rows[0]
		}
		if time.Now().After(deadline) {
			t.Fatalf("mirror rows = %d, err = %v", len(rows), err)
		}
		time.Sleep(300 * time.Millisecond)
	}
}

func (s *stack) platformActor(t *testing.T) control.Actor {
	t.Helper()
	name := fmt.Sprintf("plat-%d", time.Now().UnixNano())
	var id int64
	if err := s.pool.QueryRow(context.Background(),
		`INSERT INTO users (username, password_hash, role) VALUES ($1,'x','platform_admin') RETURNING id`, name).Scan(&id); err != nil {
		t.Fatalf("seed platform admin: %v", err)
	}
	t.Cleanup(func() {
		c := context.Background()
		s.pool.Exec(c, `DELETE FROM audit_log WHERE actor_id = $1`, id)
		s.pool.Exec(c, `DELETE FROM control_commands WHERE operator_id = $1`, id)
		s.pool.Exec(c, `DELETE FROM users WHERE id = $1`, id)
	})
	return control.Actor{UserID: id, Username: name, Role: control.RolePlatformAdmin}
}

func (s *stack) countCommands(t *testing.T) int {
	t.Helper()
	var n int
	if err := s.pool.QueryRow(context.Background(), `SELECT count(*) FROM control_commands WHERE device_name = $1`, s.device).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestConfigTankSettings_AppliedByDeviceAndMirroredFromItsReport(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})
	ctx := context.Background()

	before := s.mirror(t)
	if before.AlarmHighLevelMm.Int32 != 2600 || before.Density.Float64 != 0.56 {
		t.Fatalf("initial mirror = %+v", before)
	}

	cmd, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings,
		`{"tank":1,"alarmHighLevelMm":2700,"density":0.7,"capacityL":40000}`)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived {
		t.Fatalf("after ACK status = %q, want received (an ACK is not success)", cmd.Status)
	}

	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)
	final := awaitStatus(t, s, uuidText(cmd.CommandID.Bytes), 25*time.Second, protocol.CmdStatusSucceeded)
	var detail struct {
		Tank    int      `json:"tank"`
		Updated []string `json:"updated"`
		Ignored []string `json:"ignored"`
	}
	if err := json.Unmarshal(final.ResultDetail, &detail); err != nil || detail.Tank != 1 || len(detail.Updated) != 3 {
		t.Errorf("result detail = %s (%v)", final.ResultDetail, err)
	}
	if got, _ := s.sim.TankSettings(1); got.High != 2700 || got.Density != 0.7 || got.CapacityL != 40000 {
		t.Errorf("device state = %+v", got)
	}

	// The mirror follows the device's re-published attributes (about 2 s after the write), never the
	// command: wait for them. Wait for every changed field, not just one: the device publishes them in
	// one message, but ThingsBoard stores attributes key by key, so a sync pass can read some keys new
	// and others still old; the next pass completes the mirror.
	deadline := time.Now().Add(20 * time.Second)
	var after db.TankSetting
	for {
		after = s.mirror(t)
		caughtUp := after.AlarmHighLevelMm.Int32 == 2700 && after.Density.Float64 == 0.7 && after.CapacityL.Float64 == 40000
		if caughtUp || time.Now().After(deadline) {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if after.AlarmHighLevelMm.Int32 != 2700 || after.Density.Float64 != 0.7 || after.CapacityL.Float64 != 40000 {
		t.Fatalf("mirror never caught up with the device: %+v", after)
	}
	if !after.ReportedAt.Time.After(before.ReportedAt.Time) {
		t.Errorf("reported_at did not advance: %v -> %v", before.ReportedAt.Time, after.ReportedAt.Time)
	}
	if after.AlarmLowLevelMm.Int32 != before.AlarmLowLevelMm.Int32 {
		t.Error("a partial update must leave the other fields as they were")
	}
}

func TestConfigTankSettings_DeviceRefusalsSurfaceAsRejected(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})

	// The cloud only sees the levels it is sent; the device merges them with its stored ones. Low 2700
	// against a stored High of 2600 passes the cloud's check and the device refuses it.
	cmd, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings, `{"tank":1,"alarmLowLevelMm":2700}`)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusRejected || cmd.ErrorCode.String != protocol.ErrInvalidParams || cmd.ErrorReason.String == "" {
		t.Errorf("order violation: status=%q code=%q reason=%q", cmd.Status, cmd.ErrorCode.String, cmd.ErrorReason.String)
	}
	if got, _ := s.sim.TankSettings(1); got.Low != 300 {
		t.Errorf("a refused command wrote %d to the device", got.Low)
	}

	// 现场手动模式.
	s.sim.SetLocalControlMode(true)
	cmd, err = s.issue(t, s.actor, protocol.MethodConfigTankSettings, `{"tank":1,"density":0.8}`)
	if err != nil {
		t.Fatal(err)
	}
	if cmd.Status != protocol.CmdStatusRejected || cmd.ErrorCode.String != protocol.ErrLocalModeLocked {
		t.Errorf("local mode: status=%q code=%q", cmd.Status, cmd.ErrorCode.String)
	}
}

func TestConfigTankSettings_CloudRejectsBadArgsBeforeAnythingIsSent(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})
	before := s.countCommands(t)

	for name, args := range map[string]string{
		"closed field":   `{"tank":1,"tankShape":1}`,
		"unknown field":  `{"tank":1,"densty":0.5}`,
		"out of range":   `{"tank":1,"alarmHighTempC":101}`,
		"density zero":   `{"tank":1,"density":0}`,
		"fractional int": `{"tank":1,"alarmHighLevelMm":650.5}`,
		"string number":  `{"tank":1,"alarmHighLevelMm":"650"}`,
		"no tank":        `{"density":0.5}`,
		"tank 17":        `{"tank":17,"density":0.5}`,
		"nothing to set": `{"tank":1}`,
		"level order":    `{"tank":1,"alarmLowLevelMm":900,"alarmHighLevelMm":800}`,
	} {
		_, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings, args)
		var ae *protocol.ArgsError
		if !errors.Is(err, control.ErrInvalidArgs) || !errors.As(err, &ae) || len(ae.Fields) == 0 {
			t.Errorf("%s: err = %v, want ErrInvalidArgs with field errors", name, err)
		}
	}
	if n := s.countCommands(t); n != before {
		t.Errorf("rejected args left %d command rows behind", n-before)
	}
}

func TestConfigDeviceSettings_PlatformAdminOnly(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})
	args := `{"outStockHeightThresholdMm":30,"outStockTimeThresholdSec":90}`

	if _, err := s.issue(t, s.actor, protocol.MethodConfigDeviceSettings, args); !errors.Is(err, control.ErrForbidden) {
		t.Fatalf("customer admin: err = %v, want ErrForbidden", err)
	}

	cmd, err := s.issue(t, s.platformActor(t), protocol.MethodConfigDeviceSettings, args)
	if err != nil {
		t.Fatalf("platform admin: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived {
		t.Fatalf("status = %q", cmd.Status)
	}
	if got := s.sim.DeviceThresholds(); got.OutHeightMm != 30 || got.OutTimeSec != 90 || got.InHeightMm != 20 {
		t.Errorf("device thresholds = %+v", got)
	}

	// config-command-delivery: the device re-publishes its attributes after the write and the
	// device-level mirror follows what the device reports (never what the command asked for).
	deadline := time.Now().Add(15 * time.Second)
	for {
		s.ing.SyncTankSettingsForTest(context.Background(), s.device, s.tbDevID)
		got, err := s.q.GetDeviceSettings(context.Background(), s.device)
		if err == nil && got.OutStockHeightThresholdMm.Int32 == 30 && got.OutStockTimeThresholdSec.Int32 == 90 {
			if !got.InStockHeightThresholdMm.Valid || got.InStockHeightThresholdMm.Int32 != 20 {
				t.Errorf("in-stock height in mirror = %+v, want 20 (untouched)", got.InStockHeightThresholdMm)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("device settings mirror = %+v, err = %v; want outStock 30 mm / 90 s", got, err)
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// #42: field firmware ACKs 'received' before validating and then reports the refusal as cmdResult
// 'rejected' / INVALID_PARAMS. That must close the command as rejected with the device's reason —
// not leave it to expire with the ACK's "已受理" as the only explanation — and write nothing.
func TestConfigTankSettings_LegacyRejectAfterAckClosesAsRejected(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)
	s.sim.SetLegacyRejectAfterAck(true)

	// A multi-field request with one out-of-range field is refused whole.
	cmd, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings, `{"tank":1,"levelCorrectionMm":200,"waterCorrectionMm":0}`)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived && cmd.Status != protocol.CmdStatusRejected {
		t.Fatalf("after ACK status = %q", cmd.Status)
	}
	final := awaitStatus(t, s, uuidText(cmd.CommandID.Bytes), 25*time.Second, protocol.CmdStatusRejected)
	if final.ErrorCode.String != protocol.ErrInvalidParams || final.ErrorReason.String == "" || final.ErrorReason.String == "已受理" {
		t.Errorf("code=%q reason=%q, want INVALID_PARAMS with the device's reason", final.ErrorCode.String, final.ErrorReason.String)
	}
	if !final.FinishedAt.Valid {
		t.Error("rejected must set finished_at")
	}
	if got, _ := s.sim.TankSettings(1); got.LevelCorrMm != 0 || got.WaterCorrMm != 0 {
		t.Errorf("a refused command wrote level=%v water=%v", got.LevelCorrMm, got.WaterCorrMm)
	}

	// The tank is free again: the next command is not blocked by config_command_in_flight.
	if _, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings, `{"tank":1,"levelCorrectionMm":50}`); err != nil {
		t.Fatalf("follow-up command: %v", err)
	}
}

// #42: an unusable cmdResult point must not stop the read-back of later points. The scan reads oldest
// first, so a malformed point older than a valid result used to abort the scan before the valid one
// and leave its command to expire.
func TestReconcileCommands_SkipsInvalidResultPoints(t *testing.T) {
	s := newStack(t, control.Config{AllowConfigCommands: true})
	s.sim.SetLegacyRejectAfterAck(true) // no live subscription: only the reconcile pass can apply it

	cmd, err := s.issue(t, s.actor, protocol.MethodConfigTankSettings, `{"tank":1,"levelCorrectionMm":200}`)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	commandID := uuidText(cmd.CommandID.Bytes)
	// Older than the device's result but inside the scan window (issue time minus the skew).
	bad := map[string]any{protocol.KeyCmdResult: map[string]any{"commandId": "not-a-uuid", "status": "succeeded"}}
	if err := s.sim.PublishRawTelemetry(cmd.IssuedAt.Time.Add(-time.Second).UnixMilli(), bad); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(20 * time.Second)
	for {
		s.ing.ReconcileCommands(context.Background())
		stored, err := s.q.GetControlCommand(context.Background(), cmd.CommandID)
		if err == nil && stored.Status == protocol.CmdStatusRejected {
			if stored.ErrorCode.String != protocol.ErrInvalidParams {
				t.Errorf("code = %q", stored.ErrorCode.String)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("command %s still %q: the malformed point blocked the read-back", commandID, stored.Status)
		}
		time.Sleep(500 * time.Millisecond)
	}
}
