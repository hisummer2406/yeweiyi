// Package devicesim is the reference implementation of 设备协议 V1's device half.
//
// It exists for two reasons, and the second is why it is a library rather than only a command:
//
//  1. It is the executable spec the Android side can read to see exactly what a conforming device
//     does — publish a snapshot on a timer, push alarms the instant they happen, ACK an RPC and
//     report the real outcome separately, and never repeat a physical action for a commandId it has
//     already seen.
//  2. internal/tbclient's end-to-end tests drive it in-process, so the full
//     device → ThingsBoard → backend and backend → ThingsBoard → device loops are exercised by
//     `go test` instead of by hand.
//
// It connects to ThingsBoard's native MQTT transport and nothing else. There is no Alink topic, no
// productKey, and no second broker anywhere in this package.
package devicesim

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"strconv"
	"strings"
	"sync"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"

	"yeweiyi-cloud/internal/protocol"
)

// Config describes one simulated device.
type Config struct {
	// BrokerURL is ThingsBoard's MQTT transport, e.g. tcp://localhost:1884. Production is
	// ssl://host:8883 — the protocol is identical, only the transport differs.
	BrokerURL string
	// AccessToken is the ThingsBoard device credential. It goes in the MQTT *username* field with
	// no password, which is ThingsBoard's own convention. This is the one-device-one-secret
	// property: the token identifies the device, so nothing in any topic or payload has to.
	AccessToken string
	// ClientID for the MQTT session; generated when empty.
	ClientID string
	// Tanks is how many tanks this device reports.
	Tanks int
	// SnapshotInterval overrides protocol.SnapshotIntervalSec. Tests set it low.
	SnapshotInterval time.Duration
	// OnCommand decides how the device answers a control request. When nil, DefaultPolicy is used.
	OnCommand Policy
	// Logf receives a line per protocol event; nil silences the device.
	Logf func(format string, args ...any)
}

// Policy decides what a simulated device does with a control request. Returning an Ack with status
// CmdStatusRejected refuses it outright; returning CmdStatusReceived accepts it, and the result
// returned alongside is published as the asynchronous outcome after execDelay.
//
// Splitting the decision this way is what lets a test exercise every branch of the command state
// machine — the interlock refusal, the accepted-then-succeeded path, and the
// accepted-then-failed-in-the-field path — without needing real hardware to misbehave.
type Policy func(method string, env protocol.Envelope) (ack protocol.Ack, result *protocol.CmdResult, execDelay time.Duration)

// DefaultPolicy accepts every known method and reports success after a short delay, except that it
// enforces the expiry rule every conforming device must enforce.
func DefaultPolicy(method string, env protocol.Envelope) (protocol.Ack, *protocol.CmdResult, time.Duration) {
	now := time.Now().UnixMilli()
	ack := protocol.Ack{CommandID: env.CommandID, TSMs: now}

	if _, known := protocol.Methods[method]; !known {
		ack.Status = protocol.CmdStatusRejected
		ack.ErrorCode = protocol.ErrUnknownMethod
		ack.Reason = "本设备不支持该方法: " + method
		return ack, nil, 0
	}
	// A command that sat in a queue through an outage must never fire late. This check belongs on
	// the device because only the device knows when it actually got the request.
	if env.ExpiresAtMs > 0 && now > env.ExpiresAtMs {
		ack.Status = protocol.CmdStatusRejected
		ack.ErrorCode = protocol.ErrExpired
		ack.Reason = "命令已超过有效期，拒绝执行"
		return ack, nil, 0
	}

	ack.Status = protocol.CmdStatusReceived
	result := &protocol.CmdResult{
		CommandID: env.CommandID,
		Status:    protocol.CmdStatusSucceeded,
		TSMs:      now,
	}
	return ack, result, 200 * time.Millisecond
}

// RejectPolicy refuses everything with an interlock error — for exercising the rejection path.
func RejectPolicy(reason string) Policy {
	return func(method string, env protocol.Envelope) (protocol.Ack, *protocol.CmdResult, time.Duration) {
		return protocol.Ack{
			CommandID: env.CommandID,
			Status:    protocol.CmdStatusRejected,
			ErrorCode: protocol.ErrInterlockBlock,
			Reason:    reason,
			TSMs:      time.Now().UnixMilli(),
		}, nil, 0
	}
}

// FailPolicy accepts a command, then reports that it failed in the field. This is the case that
// makes an ACK-only design wrong: the device said yes and the action still did not happen.
func FailPolicy(errorCode, reason string) Policy {
	return func(method string, env protocol.Envelope) (protocol.Ack, *protocol.CmdResult, time.Duration) {
		now := time.Now().UnixMilli()
		return protocol.Ack{
				CommandID: env.CommandID,
				Status:    protocol.CmdStatusReceived,
				TSMs:      now,
			}, &protocol.CmdResult{
				CommandID: env.CommandID,
				Status:    protocol.CmdStatusFailed,
				ErrorCode: errorCode,
				Reason:    reason,
				TSMs:      now,
			}, 200 * time.Millisecond
	}
}

// Device is one simulated liquid-level terminal.
type Device struct {
	cfg    Config
	client paho.Client

	mu       sync.Mutex
	seq      int64
	tanks    []protocol.Tank
	commands map[string]protocol.Ack // commandId -> the ACK originally returned, for idempotency
}

// New builds a device. It does not connect; call Connect.
func New(cfg Config) *Device {
	if cfg.Tanks <= 0 {
		cfg.Tanks = 1
	}
	if cfg.SnapshotInterval <= 0 {
		cfg.SnapshotInterval = protocol.SnapshotIntervalSec * time.Second
	}
	if cfg.OnCommand == nil {
		cfg.OnCommand = DefaultPolicy
	}
	if cfg.ClientID == "" {
		cfg.ClientID = fmt.Sprintf("devicesim-%d", time.Now().UnixNano())
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}

	d := &Device{cfg: cfg, commands: map[string]protocol.Ack{}}
	d.tanks = make([]protocol.Tank, cfg.Tanks)
	for i := range d.tanks {
		d.tanks[i] = newTank(i + 1)
	}
	return d
}

func newTank(index int) protocol.Tank {
	return protocol.Tank{
		Index:          index,
		OilType:        []string{"0号柴油", "92号汽油", "95号汽油"}[(index-1)%3],
		OilLevelMm:     1500 + float64(index)*50,
		WaterLevelMm:   12.5,
		TempC:          22.0 + float64(index),
		OilVolumeL:     24000 + float64(index)*500,
		WaterVolumeL:   180,
		OilWeightKg:    20160 + float64(index)*420,
		NullVolumeL:    6000,
		OilRatioPct:    80.0,
		WaterRatioPct:  0.6,
		AlarmState:     protocol.AlarmStateNormal,
		ProbeConnected: true,
	}
}

// Connect opens the MQTT session, publishes the client attributes and subscribes for RPC requests.
func (d *Device) Connect(ctx context.Context) error {
	opts := paho.NewClientOptions().
		AddBroker(d.cfg.BrokerURL).
		SetClientID(d.cfg.ClientID).
		// ThingsBoard authenticates a device by putting its access token in the username field and
		// leaving the password empty.
		SetUsername(d.cfg.AccessToken).
		SetAutoReconnect(true).
		// Bounded backoff, matching the reconnect rule in docs/protocol-v1.md §9. A fleet coming
		// back after a network outage must not retry in lockstep.
		SetConnectRetryInterval(2 * time.Second).
		SetMaxReconnectInterval(60 * time.Second).
		SetCleanSession(true).
		SetConnectTimeout(15 * time.Second)

	d.client = paho.NewClient(opts)
	token := d.client.Connect()
	if !token.WaitTimeout(20 * time.Second) {
		return fmt.Errorf("devicesim: connect to %s timed out", d.cfg.BrokerURL)
	}
	if err := token.Error(); err != nil {
		return fmt.Errorf("devicesim: connect to %s: %w", d.cfg.BrokerURL, err)
	}

	if err := d.subscribeRPC(); err != nil {
		return err
	}
	return d.PublishAttributes()
}

// Disconnect closes the session.
func (d *Device) Disconnect() {
	if d.client != nil {
		d.client.Disconnect(250)
	}
}

func (d *Device) publish(topic string, payload []byte) error {
	// QoS 1 throughout: telemetry and events must not be silently lost, and the protocol is
	// idempotent by design (snapshots carry their own ts, commands carry a commandId), so
	// at-least-once redelivery is harmless.
	t := d.client.Publish(topic, 1, false, payload)
	if !t.WaitTimeout(10 * time.Second) {
		return fmt.Errorf("devicesim: publish to %s timed out", topic)
	}
	return t.Error()
}

// PublishAttributes reports the device's client attributes — what it is, as opposed to what it is
// measuring.
func (d *Device) PublishAttributes() error {
	attrs := map[string]any{
		protocol.AttrProtocolVersion:    protocol.Version,
		protocol.AttrDeviceModel:        "SANSEN-YWY-SIM",
		protocol.AttrHardwareVersion:    "sim-1.0",
		protocol.AttrTankCount:          d.cfg.Tanks,
		protocol.AttrLocalControlMode:   false,
		protocol.ClientCurrentFwTitle:   "yeweiyi-android",
		protocol.ClientCurrentFwVersion: "1.4.2",
	}
	for i := 1; i <= d.cfg.Tanks; i++ {
		attrs[protocol.TankKey(i, protocol.AttrProbeAddress)] = fmt.Sprintf("%02d", i)
		attrs[protocol.TankKey(i, protocol.AttrProbeType)] = "磁致伸缩"
		attrs[protocol.TankKey(i, protocol.AttrCapacityL)] = 30000.0
	}

	payload, err := json.Marshal(attrs)
	if err != nil {
		return err
	}
	d.cfg.Logf("→ attributes: %s", payload)
	return d.publish(protocol.AttributesTopic, payload)
}

// PublishSnapshot sends one full tank snapshot and returns what it sent, so a test can assert on
// the exact values that went out rather than on values it assumed.
func (d *Device) PublishSnapshot() (protocol.Snapshot, error) {
	d.mu.Lock()
	d.seq++
	// Drift the readings slightly so consecutive snapshots differ — otherwise a test cannot tell a
	// fresh snapshot from a stale one.
	for i := range d.tanks {
		d.tanks[i].OilLevelMm += (rand.Float64() - 0.5) * 2
		d.tanks[i].TempC += (rand.Float64() - 0.5) * 0.2
	}
	snap := protocol.Snapshot{
		TS:          time.Now().UnixMilli(),
		SnapshotSeq: d.seq,
		Tanks:       append([]protocol.Tank(nil), d.tanks...),
	}
	d.mu.Unlock()

	payload, err := snap.MarshalTelemetry()
	if err != nil {
		return protocol.Snapshot{}, err
	}
	d.cfg.Logf("→ snapshot seq=%d tanks=%d", snap.SnapshotSeq, len(snap.Tanks))
	return snap, d.publish(protocol.TelemetryTopic, payload)
}

// RunSnapshots publishes a snapshot immediately and then on the configured interval until ctx ends.
func (d *Device) RunSnapshots(ctx context.Context) error {
	if _, err := d.PublishSnapshot(); err != nil {
		return err
	}
	ticker := time.NewTicker(d.cfg.SnapshotInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if _, err := d.PublishSnapshot(); err != nil {
				d.cfg.Logf("snapshot failed: %v", err)
			}
		}
	}
}

// RaiseAlarm publishes an ACTIVE alarm immediately, without waiting for the snapshot timer — the
// "即时事件" requirement. It returns the alarmId, which ClearAlarm needs in order to pair the
// recovery with this occurrence.
func (d *Device) RaiseAlarm(tank int, alarmType, severity, text string, value float64) (protocol.AlarmEvent, error) {
	ev := protocol.AlarmEvent{
		AlarmID:  newAlarmID(),
		Tank:     tank,
		Type:     alarmType,
		Severity: severity,
		Status:   protocol.AlarmStatusActive,
		Text:     text,
		Value:    value,
		TSMs:     time.Now().UnixMilli(),
	}
	d.setTankAlarmState(tank, alarmStateFor(alarmType))
	return ev, d.publishAlarm(ev)
}

// ClearAlarm publishes the recovery of an alarm previously raised, reusing its alarmId.
func (d *Device) ClearAlarm(active protocol.AlarmEvent, text string, value float64) (protocol.AlarmEvent, error) {
	ev := active
	ev.Status = protocol.AlarmStatusCleared
	ev.Text = text
	ev.Value = value
	ev.TSMs = time.Now().UnixMilli()

	d.setTankAlarmState(active.Tank, protocol.AlarmStateNormal)
	return ev, d.publishAlarm(ev)
}

func (d *Device) publishAlarm(ev protocol.AlarmEvent) error {
	payload, err := ev.MarshalTelemetry()
	if err != nil {
		return err
	}
	d.cfg.Logf("→ alarm %s %s tank=%d", ev.Type, ev.Status, ev.Tank)
	return d.publish(protocol.TelemetryTopic, payload)
}

func alarmStateFor(alarmType string) string {
	if alarmType == protocol.AlarmProbeDisconnected {
		return protocol.AlarmStateFault
	}
	return protocol.AlarmStateAlarm
}

func (d *Device) setTankAlarmState(tank int, state string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if tank >= 1 && tank <= len(d.tanks) {
		d.tanks[tank-1].AlarmState = state
	}
}

func (d *Device) subscribeRPC() error {
	t := d.client.Subscribe(protocol.RPCRequestTopicFilter, 1, d.handleRPC)
	if !t.WaitTimeout(10 * time.Second) {
		return fmt.Errorf("devicesim: subscribing to %s timed out", protocol.RPCRequestTopicFilter)
	}
	return t.Error()
}

func (d *Device) handleRPC(_ paho.Client, msg paho.Message) {
	requestID := msg.Topic()[strings.LastIndexByte(msg.Topic(), '/')+1:]
	if _, err := strconv.Atoi(requestID); err != nil {
		d.cfg.Logf("← rpc on unparseable topic %s", msg.Topic())
		return
	}

	var req struct {
		Method string            `json:"method"`
		Params protocol.Envelope `json:"params"`
	}
	if err := json.Unmarshal(msg.Payload(), &req); err != nil {
		d.cfg.Logf("← rpc with undecodable body: %v", err)
		return
	}
	d.cfg.Logf("← rpc %s commandId=%s", req.Method, req.Params.CommandID)

	// Idempotency: a redelivered commandId replays the original answer and performs no second
	// physical action. This is the guarantee that makes it safe for the backend to retry a command
	// it is unsure was delivered.
	d.mu.Lock()
	prior, seen := d.commands[req.Params.CommandID]
	d.mu.Unlock()
	if seen {
		replay := prior
		replay.Duplicate = true
		replay.TSMs = time.Now().UnixMilli()
		d.cfg.Logf("  duplicate commandId, replaying %s without re-acting", replay.Status)
		d.respondRPC(requestID, replay)
		return
	}

	ack, result, execDelay := d.cfg.OnCommand(req.Method, req.Params)
	if ack.CommandID == "" {
		ack.CommandID = req.Params.CommandID
	}
	if ack.TSMs == 0 {
		ack.TSMs = time.Now().UnixMilli()
	}

	d.mu.Lock()
	d.commands[req.Params.CommandID] = ack
	d.mu.Unlock()

	d.respondRPC(requestID, ack)

	// The outcome is reported separately and later. A rejection is terminal at ACK time and gets
	// no result.
	if result == nil || ack.Status == protocol.CmdStatusRejected {
		return
	}
	go func() {
		time.Sleep(execDelay)
		res := *result
		res.TSMs = time.Now().UnixMilli()
		payload, err := res.MarshalTelemetry()
		if err != nil {
			d.cfg.Logf("cmdResult encode failed: %v", err)
			return
		}
		d.cfg.Logf("→ cmdResult %s commandId=%s", res.Status, res.CommandID)
		if err := d.publish(protocol.TelemetryTopic, payload); err != nil {
			d.cfg.Logf("cmdResult publish failed: %v", err)
		}
	}()
}

func (d *Device) respondRPC(requestID string, ack protocol.Ack) {
	payload, err := json.Marshal(ack)
	if err != nil {
		d.cfg.Logf("ack encode failed: %v", err)
		return
	}
	d.cfg.Logf("→ ack %s commandId=%s duplicate=%v", ack.Status, ack.CommandID, ack.Duplicate)
	if err := d.publish(protocol.RPCResponseTopicPfx+requestID, payload); err != nil {
		d.cfg.Logf("ack publish failed: %v", err)
	}
}

func newAlarmID() string {
	b := make([]byte, 16)
	for i := range b {
		b[i] = byte(rand.Intn(256))
	}
	// RFC 4122 v4 layout — good enough for a simulator, and it makes the fixtures and the live
	// data look alike.
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
