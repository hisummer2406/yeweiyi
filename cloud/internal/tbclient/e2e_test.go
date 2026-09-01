package tbclient_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"testing"
	"time"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// These are the end-to-end checks GitHub issue #9 is accepted against. They run the real thing —
// a real ThingsBoard CE container, a real MQTT session, real REST and WebSocket calls — because the
// whole point of the spike is to find out whether ThingsBoard CE actually supports this
// architecture, and a mocked ThingsBoard could only ever confirm what we already assumed.
//
// Bring the platform up first:
//
//	docker compose -f docker-compose.thingsboard.yml up -d
//
// Every test provisions its own device and deletes it afterwards, so they can run repeatedly and in
// any order against the same instance.

const (
	defaultTBURL     = "http://localhost:8081"
	defaultTBMQTT    = "tcp://localhost:1884"
	defaultTBUser    = "tenant@thingsboard.org"
	defaultTBPass    = "tenant"
	settleTimeout    = 20 * time.Second
	settlePollPeriod = 250 * time.Millisecond
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func tbBaseURL() string { return envOr("TB_BASE_URL", defaultTBURL) }
func tbMQTTURL() string { return envOr("TB_MQTT_URL", defaultTBMQTT) }

// newTestClient logs in, skipping the whole test when no ThingsBoard is running. Skipping rather
// than failing keeps `go test ./...` green for someone working on internal/api who has not started
// the heavy container — but the skip message says exactly what to run.
func newTestClient(t *testing.T) *tbclient.Client {
	t.Helper()

	host := "localhost:8081"
	if u := os.Getenv("TB_BASE_URL"); u != "" {
		host = ""
	}
	if host != "" {
		conn, err := net.DialTimeout("tcp", host, 2*time.Second)
		if err != nil {
			t.Skipf("ThingsBoard is not reachable at %s (%v).\n"+
				"Start it with: docker compose -f docker-compose.thingsboard.yml up -d", host, err)
		}
		conn.Close()
	}

	c, err := tbclient.New(tbclient.Config{
		BaseURL:  tbBaseURL(),
		Username: envOr("TB_USERNAME", defaultTBUser),
		Password: envOr("TB_PASSWORD", defaultTBPass),
	})
	if err != nil {
		t.Fatalf("tbclient.New: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := c.Login(ctx); err != nil {
		t.Skipf("ThingsBoard login failed (%v).\n"+
			"Start it with: docker compose -f docker-compose.thingsboard.yml up -d", err)
	}
	return c
}

// provisionDevice registers a uniquely-named device and returns it with its MQTT access token,
// cleaning it up when the test ends. This is the provisioning half of issue #9: one device, one
// ThingsBoard-issued secret, created through the public API with no console clicking.
func provisionDevice(t *testing.T, c *tbclient.Client) (tbclient.Device, string) {
	t.Helper()
	ctx := context.Background()

	name := fmt.Sprintf("e2e-%s-%d", t.Name(), time.Now().UnixNano())
	dev, err := c.EnsureDevice(ctx, name, tbclient.DefaultDeviceProfile)
	if err != nil {
		t.Fatalf("EnsureDevice(%s): %v", name, err)
	}
	t.Cleanup(func() {
		if err := c.DeleteDevice(context.Background(), dev.ID.ID); err != nil {
			t.Logf("cleanup: delete device %s: %v", dev.ID.ID, err)
		}
	})

	creds, err := c.GetDeviceCredentials(ctx, dev.ID.ID)
	if err != nil {
		t.Fatalf("GetDeviceCredentials: %v", err)
	}
	if creds.Type != "ACCESS_TOKEN" || creds.CredentialsID == "" {
		t.Fatalf("unexpected credentials: type=%q id=%q", creds.Type, creds.CredentialsID)
	}
	return dev, creds.CredentialsID
}

func connectSim(t *testing.T, cfg devicesim.Config) *devicesim.Device {
	t.Helper()
	if cfg.Logf == nil {
		cfg.Logf = t.Logf
	}
	cfg.BrokerURL = tbMQTTURL()

	sim := devicesim.New(cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := sim.Connect(ctx); err != nil {
		t.Fatalf("device could not connect to ThingsBoard MQTT at %s: %v", cfg.BrokerURL, err)
	}
	t.Cleanup(sim.Disconnect)
	return sim
}

// eventually polls until fn succeeds or the deadline passes, returning fn's last error. ThingsBoard
// persists telemetry asynchronously, so a read immediately after a publish legitimately misses it;
// the alternative to polling would be an arbitrary sleep that is either flaky or slow.
func eventually(t *testing.T, timeout time.Duration, what string, fn func() error) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var last error
	for time.Now().Before(deadline) {
		if last = fn(); last == nil {
			return
		}
		time.Sleep(settlePollPeriod)
	}
	t.Fatalf("timed out after %s waiting for %s: %v", timeout, what, last)
}

// TestUplink_SnapshotReachesBackend is acceptance criterion 1: 模拟设备完成"遥测 → ThingsBoard → Go读取"闭环.
func TestUplink_SnapshotReachesBackend(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{AccessToken: token, Tanks: 2})

	sent, err := sim.PublishSnapshot()
	if err != nil {
		t.Fatalf("PublishSnapshot: %v", err)
	}

	var got protocol.Snapshot
	eventually(t, settleTimeout, "the snapshot to be readable through ThingsBoard's REST API", func() error {
		ts, values, err := c.LatestValues(context.Background(), dev.ID.ID, protocol.SnapshotKeys(2))
		if err != nil {
			return err
		}
		if len(values) == 0 {
			return errors.New("no telemetry stored yet")
		}
		snap, err := protocol.ParseSnapshot(ts, values)
		if err != nil {
			return err
		}
		if len(snap.Tanks) != 2 {
			return fmt.Errorf("got %d tanks, want 2", len(snap.Tanks))
		}
		if snap.SnapshotSeq != sent.SnapshotSeq {
			return fmt.Errorf("snapshotSeq %d, want %d", snap.SnapshotSeq, sent.SnapshotSeq)
		}
		got = snap
		return nil
	})

	// The values must survive the round trip through ThingsBoard's stringifying storage exactly —
	// this is what proves internal/protocol's REST-shape handling is right against the real server,
	// not just against the fixture.
	for i, want := range sent.Tanks {
		g := got.Tanks[i]
		if g.Index != want.Index || g.OilType != want.OilType {
			t.Errorf("tank %d identity: got index=%d oilType=%q, want index=%d oilType=%q",
				i+1, g.Index, g.OilType, want.Index, want.OilType)
		}
		if g.OilLevelMm != want.OilLevelMm || g.TempC != want.TempC || g.OilVolumeL != want.OilVolumeL {
			t.Errorf("tank %d readings: got %+v, want %+v", i+1, g, want)
		}
		if g.ProbeConnected != want.ProbeConnected {
			t.Errorf("tank %d probeConnected: got %v, want %v", i+1, g.ProbeConnected, want.ProbeConnected)
		}
	}
	if got.TS != sent.TS {
		t.Errorf("device timestamp not preserved: got %d, want %d", got.TS, sent.TS)
	}
}

// TestUplink_ClientAttributesReachBackend covers the attributes half of the data model — the
// configuration a device reports once, rather than on the snapshot cadence.
func TestUplink_ClientAttributesReachBackend(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	connectSim(t, devicesim.Config{AccessToken: token, Tanks: 2}) // Connect publishes attributes

	eventually(t, settleTimeout, "client attributes to appear", func() error {
		attrs, err := c.Attributes(context.Background(), dev.ID.ID, tbclient.ScopeClient, nil)
		if err != nil {
			return err
		}
		if len(attrs) == 0 {
			return errors.New("no client attributes yet")
		}
		if v, ok := attrs[protocol.AttrProtocolVersion]; !ok {
			return errors.New("protocolVersion missing")
		} else if n, _ := v.(float64); int(n) != protocol.Version {
			return fmt.Errorf("protocolVersion = %v, want %d", v, protocol.Version)
		}
		if v, _ := attrs[protocol.AttrTankCount].(float64); int(v) != 2 {
			return fmt.Errorf("tankCount = %v, want 2", attrs[protocol.AttrTankCount])
		}
		// Unlike telemetry, attributes keep their JSON types — worth pinning, since the two
		// ThingsBoard APIs behaving differently is exactly the kind of thing that bites later.
		if _, ok := attrs[protocol.AttrLocalControlMode].(bool); !ok {
			return fmt.Errorf("localControlMode came back as %T, want bool", attrs[protocol.AttrLocalControlMode])
		}
		return nil
	})
}

// TestDownlink_SharedAttributeReachesDevice covers the configuration-push direction, which
// deliberately does not go through the command state machine.
func TestDownlink_SharedAttributeReachesDevice(t *testing.T) {
	c := newTestClient(t)
	dev, _ := provisionDevice(t, c)

	want := map[string]any{protocol.SharedReportIntervalSec: 15}
	if err := c.SetSharedAttributes(context.Background(), dev.ID.ID, want); err != nil {
		t.Fatalf("SetSharedAttributes: %v", err)
	}

	eventually(t, settleTimeout, "the shared attribute to be stored", func() error {
		attrs, err := c.Attributes(context.Background(), dev.ID.ID, tbclient.ScopeShared, nil)
		if err != nil {
			return err
		}
		v, ok := attrs[protocol.SharedReportIntervalSec]
		if !ok {
			return errors.New("reportIntervalSec missing")
		}
		if n, _ := v.(float64); int(n) != 15 {
			return fmt.Errorf("reportIntervalSec = %v, want 15", v)
		}
		return nil
	})
}

// TestAlarm_ArrivesWithoutWaitingForSnapshot is acceptance criterion 3: 告警产生与恢复不等待30秒周期.
//
// The device is deliberately configured with a 10-minute snapshot interval, so if the alarm were
// riding on the snapshot timer it could not possibly arrive inside this test's budget. Both the
// raise and the recovery are checked, over the live WebSocket rather than by polling REST, because
// the WebSocket is the path the real backend will use to fan alarms out to operators.
func TestAlarm_ArrivesWithoutWaitingForSnapshot(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{
		AccessToken:      token,
		Tanks:            1,
		SnapshotInterval: 10 * time.Minute,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stream, err := c.SubscribeTelemetry(ctx, dev.ID.ID, nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()
	drainInitial(stream)

	start := time.Now()
	active, err := sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "1号罐高高液位报警", 1980.4)
	if err != nil {
		t.Fatalf("RaiseAlarm: %v", err)
	}

	gotActive := awaitAlarm(t, stream, active.AlarmID, protocol.AlarmStatusActive, 15*time.Second)
	raiseLatency := time.Since(start)
	if raiseLatency >= protocol.SnapshotIntervalSec*time.Second {
		t.Errorf("alarm took %s to arrive — that is not faster than the %ds snapshot cycle",
			raiseLatency, protocol.SnapshotIntervalSec)
	}
	if gotActive.Type != protocol.AlarmHighHighLevel || gotActive.Severity != protocol.SeverityCritical {
		t.Errorf("alarm content mangled in transit: %+v", gotActive)
	}
	if gotActive.Text != "1号罐高高液位报警" {
		t.Errorf("alarm text = %q, want the original Chinese text", gotActive.Text)
	}
	t.Logf("alarm raise propagated device→ThingsBoard→backend in %s", raiseLatency)

	start = time.Now()
	cleared, err := sim.ClearAlarm(active, "1号罐高高液位报警恢复", 1842.5)
	if err != nil {
		t.Fatalf("ClearAlarm: %v", err)
	}
	gotCleared := awaitAlarm(t, stream, cleared.AlarmID, protocol.AlarmStatusCleared, 15*time.Second)
	clearLatency := time.Since(start)
	if clearLatency >= protocol.SnapshotIntervalSec*time.Second {
		t.Errorf("alarm recovery took %s — not faster than the %ds snapshot cycle",
			clearLatency, protocol.SnapshotIntervalSec)
	}
	// Pairing the recovery to the occurrence by alarmId is the property the cloud relies on to
	// close the right alarm; without it, two overlapping occurrences of the same type on the same
	// tank could not be told apart.
	if gotCleared.AlarmID != gotActive.AlarmID {
		t.Errorf("recovery alarmId %q does not match the raise %q", gotCleared.AlarmID, gotActive.AlarmID)
	}
	t.Logf("alarm recovery propagated in %s", clearLatency)
}

// TestControl_FullCommandLifecycle is acceptance criterion 2:
// "Go发RPC → ThingsBoard → 设备ACK → Go得到最终结果"闭环.
//
// It asserts the distinction the whole control design rests on: the ACK and the outcome are two
// different messages arriving at two different times, and only the second one means the pump ran.
func TestControl_FullCommandLifecycle(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{AccessToken: token, Tanks: 1, SnapshotInterval: 10 * time.Minute})
	_ = sim

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stream, err := c.SubscribeTelemetry(ctx, dev.ID.ID, nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()
	drainInitial(stream)

	env := newEnvelope(t, `{"pump":1,"tank":1}`)
	ack, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("SendCommand: %v", err)
	}

	if ack.Status != protocol.CmdStatusReceived {
		t.Fatalf("ack status = %q (%s: %s), want %q", ack.Status, ack.ErrorCode, ack.Reason, protocol.CmdStatusReceived)
	}
	if ack.CommandID != env.CommandID {
		t.Fatalf("ack commandId = %q, want %q", ack.CommandID, env.CommandID)
	}
	// The ACK must NOT be a terminal state: at this point the pump has not been reported started.
	if protocol.IsTerminal(ack.Status) {
		t.Errorf("%q must not be terminal — treating an ACK as success is the bug this design exists to prevent", ack.Status)
	}

	result := awaitCmdResult(t, stream, env.CommandID, 20*time.Second)
	if result.Status != protocol.CmdStatusSucceeded {
		t.Errorf("final status = %q (%s: %s), want %q", result.Status, result.ErrorCode, result.Reason, protocol.CmdStatusSucceeded)
	}
	if !protocol.IsTerminal(result.Status) {
		t.Errorf("%q should be terminal", result.Status)
	}
	t.Logf("command %s: ack=%s → final=%s", env.CommandID, ack.Status, result.Status)
}

// TestControl_DeviceRejectsViaInterlock covers the branch where the device is the safety arbiter
// and refuses. A rejection is terminal at ACK time and produces no outcome message.
func TestControl_DeviceRejectsViaInterlock(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	connectSim(t, devicesim.Config{
		AccessToken:      token,
		Tanks:            1,
		SnapshotInterval: 10 * time.Minute,
		OnCommand:        devicesim.RejectPolicy("1号罐液位低于下限，设备端联锁禁止启泵"),
	})

	env := newEnvelope(t, `{"pump":1}`)
	ack, err := c.SendCommand(context.Background(), dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("SendCommand: %v", err)
	}

	if ack.Status != protocol.CmdStatusRejected {
		t.Fatalf("status = %q, want %q", ack.Status, protocol.CmdStatusRejected)
	}
	if ack.ErrorCode != protocol.ErrInterlockBlock {
		t.Errorf("errorCode = %q, want %q", ack.ErrorCode, protocol.ErrInterlockBlock)
	}
	if ack.Reason == "" {
		t.Error("a rejection must carry a reason the operator can read")
	}
	if !protocol.IsTerminal(ack.Status) {
		t.Error("a rejection is terminal")
	}
}

// TestControl_ReportsFailureAfterAccepting is the case that makes an ACK-only design unsafe: the
// device accepted the command and the action still did not happen.
func TestControl_ReportsFailureAfterAccepting(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	connectSim(t, devicesim.Config{
		AccessToken:      token,
		Tanks:            1,
		SnapshotInterval: 10 * time.Minute,
		OnCommand:        devicesim.FailPolicy(protocol.ErrHardwareFault, "泵启动后未检测到流量，已自动停泵"),
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream, err := c.SubscribeTelemetry(ctx, dev.ID.ID, nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()
	drainInitial(stream)

	env := newEnvelope(t, `{"pump":1}`)
	ack, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("SendCommand: %v", err)
	}
	if ack.Status != protocol.CmdStatusReceived {
		t.Fatalf("ack status = %q, want %q", ack.Status, protocol.CmdStatusReceived)
	}

	result := awaitCmdResult(t, stream, env.CommandID, 20*time.Second)
	if result.Status != protocol.CmdStatusFailed {
		t.Errorf("final status = %q, want %q", result.Status, protocol.CmdStatusFailed)
	}
	if result.ErrorCode != protocol.ErrHardwareFault {
		t.Errorf("errorCode = %q, want %q", result.ErrorCode, protocol.ErrHardwareFault)
	}
}

// TestControl_DuplicateCommandIsNotReExecuted pins the idempotency requirement: redelivering a
// commandId must replay the original answer and cause no second physical action.
func TestControl_DuplicateCommandIsNotReExecuted(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	connectSim(t, devicesim.Config{AccessToken: token, Tanks: 1, SnapshotInterval: 10 * time.Minute})

	ctx := context.Background()
	env := newEnvelope(t, `{"pump":1}`)

	first, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("first SendCommand: %v", err)
	}
	if first.Duplicate {
		t.Fatal("the first delivery must not be flagged as a duplicate")
	}

	// Exactly the same envelope, including the commandId — what a backend retry looks like.
	second, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("redelivery: %v", err)
	}
	if !second.Duplicate {
		t.Error("redelivering the same commandId must be reported as a duplicate, or the backend cannot tell a retry from a second pump start")
	}
	if second.Status != first.Status {
		t.Errorf("duplicate replayed status %q, want the original %q", second.Status, first.Status)
	}
}

// TestControl_ExpiredCommandIsRejected pins the validity-window rule: a command that arrives after
// its expiry must be refused by the device rather than acted on late.
func TestControl_ExpiredCommandIsRejected(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	connectSim(t, devicesim.Config{AccessToken: token, Tanks: 1, SnapshotInterval: 10 * time.Minute})

	now := time.Now().UnixMilli()
	env := protocol.Envelope{
		CommandID:       newCommandID(),
		ProtocolVersion: protocol.Version,
		Args:            json.RawMessage(`{"pump":1}`),
		IssuedAtMs:      now - 120_000,
		ExpiresAtMs:     now - 60_000, // already expired when it arrives
		Operator:        "e2e@test",
		Reason:          "验证有效期",
	}

	ack, err := c.SendCommand(context.Background(), dev.ID.ID, protocol.MethodPumpStart, env, 10_000)
	if err != nil {
		t.Fatalf("SendCommand: %v", err)
	}
	if ack.Status != protocol.CmdStatusRejected || ack.ErrorCode != protocol.ErrExpired {
		t.Errorf("got status=%q errorCode=%q, want %q/%q", ack.Status, ack.ErrorCode,
			protocol.CmdStatusRejected, protocol.ErrExpired)
	}
}

// TestControl_OfflineDeviceIsReportedDistinctly checks the degradation behaviour issue #10 asks
// for: a command to a device with no session must be distinguishable from ThingsBoard being down,
// because one means "retry later" and the other means "the platform is broken".
func TestControl_OfflineDeviceIsReportedDistinctly(t *testing.T) {
	c := newTestClient(t)
	dev, _ := provisionDevice(t, c) // provisioned but never connected

	env := newEnvelope(t, `{"pump":1}`)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	_, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodPumpStart, env, 3_000)
	if err == nil {
		t.Fatal("expected an error for a device that has never connected")
	}
	if !errors.Is(err, tbclient.ErrDeviceOffline) {
		t.Fatalf("got %v, want ErrDeviceOffline", err)
	}
	if tbclient.Unavailable(err) {
		t.Error("an offline device must not be reported as ThingsBoard being unavailable")
	}
}

// TestClient_UnavailableThingsBoardIsDistinguishable covers the other half of that distinction,
// using an address nothing is listening on.
func TestClient_UnavailableThingsBoardIsDistinguishable(t *testing.T) {
	c, err := tbclient.New(tbclient.Config{
		BaseURL:  "http://127.0.0.1:1", // nothing listens here
		Username: "x@example.com",
		Password: "x",
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err = c.Login(ctx)
	if err == nil {
		t.Fatal("expected a connection error")
	}
	if !tbclient.Unavailable(err) {
		t.Errorf("Unavailable(%v) = false, want true", err)
	}
}

// TestDevice_UnknownNameIsNotFound pins the 404 mapping the provisioning flow branches on.
func TestDevice_UnknownNameIsNotFound(t *testing.T) {
	c := newTestClient(t)

	_, err := c.GetDeviceByName(context.Background(), fmt.Sprintf("no-such-device-%d", time.Now().UnixNano()))
	if !errors.Is(err, tbclient.ErrNotFound) {
		t.Fatalf("got %v, want ErrNotFound", err)
	}
	if tbclient.Unavailable(err) {
		t.Error("a missing device must not be reported as ThingsBoard being unavailable")
	}
}

// --- helpers ---

func newEnvelope(t *testing.T, args string) protocol.Envelope {
	t.Helper()
	now := time.Now().UnixMilli()
	return protocol.Envelope{
		CommandID:       newCommandID(),
		ProtocolVersion: protocol.Version,
		Args:            json.RawMessage(args),
		IssuedAtMs:      now,
		ExpiresAtMs:     now + 60_000,
		Operator:        "e2e@test",
		Reason:          "端到端验证",
	}
}

func newCommandID() string {
	return fmt.Sprintf("cmd-%d-%d", time.Now().UnixNano(), os.Getpid())
}

// drainInitial swallows the snapshot of existing telemetry ThingsBoard sends when a subscription
// opens, so a test only sees what happens after it subscribed.
func drainInitial(stream *tbclient.TelemetryStream) {
	deadline := time.After(2 * time.Second)
	for {
		select {
		case <-stream.Updates:
		case <-deadline:
			return
		}
	}
}

func awaitAlarm(t *testing.T, stream *tbclient.TelemetryStream, alarmID, status string, timeout time.Duration) protocol.AlarmEvent {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case u, ok := <-stream.Updates:
			if !ok {
				t.Fatal("telemetry stream closed while waiting for an alarm")
			}
			raw, present := u.Values[protocol.KeyAlarm]
			if !present {
				continue
			}
			ev, err := protocol.ParseAlarmEvent(raw)
			if err != nil {
				t.Fatalf("ParseAlarmEvent(%v): %v", raw, err)
			}
			if ev.AlarmID == alarmID && ev.Status == status {
				return ev
			}
		case err := <-stream.Err:
			t.Fatalf("telemetry stream failed: %v", err)
		case <-deadline:
			t.Fatalf("no %s alarm for %s within %s", status, alarmID, timeout)
		}
	}
}

func awaitCmdResult(t *testing.T, stream *tbclient.TelemetryStream, commandID string, timeout time.Duration) protocol.CmdResult {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case u, ok := <-stream.Updates:
			if !ok {
				t.Fatal("telemetry stream closed while waiting for a command result")
			}
			raw, present := u.Values[protocol.KeyCmdResult]
			if !present {
				continue
			}
			res, err := protocol.ParseCmdResult(raw)
			if err != nil {
				t.Fatalf("ParseCmdResult(%v): %v", raw, err)
			}
			if res.CommandID == commandID {
				return res
			}
		case err := <-stream.Err:
			t.Fatalf("telemetry stream failed: %v", err)
		case <-deadline:
			t.Fatalf("no result for command %s within %s", commandID, timeout)
		}
	}
}

// TestTimeseries_ReturnsHistoryInOrder covers the history query used for trend charts. It was added
// after a coverage run showed Timeseries had never once executed — its query parameters (orderBy,
// useStrictDataTypes, startTs/endTs) were written from documentation and never actually sent to a
// server, which is precisely the situation where a wrong parameter name fails silently by being
// ignored.
func TestTimeseries_ReturnsHistoryInOrder(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{AccessToken: token, Tanks: 1, SnapshotInterval: 10 * time.Minute})

	start := time.Now().Add(-time.Minute).UnixMilli()

	// Three snapshots, spaced enough that ThingsBoard stores three distinct points rather than
	// collapsing same-millisecond writes.
	var sent []protocol.Snapshot
	for i := 0; i < 3; i++ {
		snap, err := sim.PublishSnapshot()
		if err != nil {
			t.Fatalf("PublishSnapshot %d: %v", i, err)
		}
		sent = append(sent, snap)
		time.Sleep(300 * time.Millisecond)
	}

	key := protocol.TankKey(1, protocol.KeyOilLevelMm)
	var series []tbclient.TSValue
	eventually(t, settleTimeout, "three history points to be stored", func() error {
		end := time.Now().Add(time.Minute).UnixMilli()
		got, err := c.Timeseries(context.Background(), dev.ID.ID, []string{key}, start, end, 100)
		if err != nil {
			return err
		}
		if len(got[key]) < 3 {
			return fmt.Errorf("got %d points for %s, want 3", len(got[key]), key)
		}
		series = got[key]
		return nil
	})

	// orderBy=ASC must actually have been honoured — if the parameter name were wrong the server
	// would ignore it and default to DESC, which no other assertion would catch.
	for i := 1; i < len(series); i++ {
		if series[i].TS < series[i-1].TS {
			t.Fatalf("points are not in ascending time order: %d before %d", series[i-1].TS, series[i].TS)
		}
	}
	if series[0].TS != sent[0].TS {
		t.Errorf("first history point ts = %d, want the first snapshot's %d", series[0].TS, sent[0].TS)
	}
	if series[0].Value == "" {
		t.Error("history point has an empty value")
	}
	t.Logf("history: %d points, first=%s last=%s", len(series), series[0].Value, series[len(series)-1].Value)
}

// TestRunSnapshots_PublishesOnTheTimer covers the periodic loop the real device runs. Manual CLI
// runs had exercised it, but nothing in `go test` did.
func TestRunSnapshots_PublishesOnTheTimer(t *testing.T) {
	c := newTestClient(t)
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{
		AccessToken:      token,
		Tanks:            1,
		SnapshotInterval: 700 * time.Millisecond,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	done := make(chan error, 1)
	go func() { done <- sim.RunSnapshots(ctx) }()

	if err := <-done; err != nil {
		t.Fatalf("RunSnapshots: %v", err)
	}

	// The loop publishes once immediately and then on each tick, so ~3s at 700ms must yield
	// several distinct snapshotSeq values.
	eventually(t, settleTimeout, "the timer to have produced multiple snapshots", func() error {
		_, values, err := c.LatestValues(context.Background(), dev.ID.ID, []string{protocol.KeySnapshotSeq})
		if err != nil {
			return err
		}
		snap, err := protocol.ParseSnapshot(0, values)
		if err != nil {
			return err
		}
		if snap.SnapshotSeq < 3 {
			return fmt.Errorf("snapshotSeq = %d, want at least 3", snap.SnapshotSeq)
		}
		t.Logf("timer produced %d snapshots in 3s at a 700ms interval", snap.SnapshotSeq)
		return nil
	})
}
