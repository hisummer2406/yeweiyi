package protocol_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

// These are contract tests against fixtures/protocol/v1/, the cross-repo artifact issue #7 calls
// for. The Android side is expected to run its own tests against the very same files, so a rename
// or a unit change on either side fails a build on both instead of being discovered on a real
// device.
//
// Two directions are checked for every fixture that has a Go type:
//
//	decode  the fixture parses into exactly the expected struct — catches a reader drifting;
//	encode  our own marshalled output matches the fixture key-for-key — catches a writer drifting.
//
// Encode comparison is semantic (both sides decoded to any) rather than byte-exact, because JSON
// float formatting and key order are not part of the contract and pinning them would produce
// failures that mean nothing.

const fixtureDir = "../../fixtures/protocol/v1"

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(fixtureDir, name))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

// stripComments removes the "_comment" documentation keys the fixtures carry for human readers.
// They are not part of the protocol; a real device never sends them, and per the compatibility
// policy a reader ignores them anyway.
func stripComments(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, val := range t {
			if strings.HasPrefix(k, "_") {
				continue
			}
			out[k] = stripComments(val)
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, val := range t {
			out[i] = stripComments(val)
		}
		return out
	default:
		return v
	}
}

// assertSameJSON compares two JSON documents structurally after dropping "_" keys.
func assertSameJSON(t *testing.T, got, want []byte) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("unmarshal got: %v", err)
	}
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatalf("unmarshal want: %v", err)
	}
	g, w = stripComments(g), stripComments(w)
	if !reflect.DeepEqual(g, w) {
		gb, _ := json.MarshalIndent(g, "", "  ")
		wb, _ := json.MarshalIndent(w, "", "  ")
		t.Errorf("JSON mismatch\n--- got ---\n%s\n--- want ---\n%s", gb, wb)
	}
}

// wantSnapshot is the single expected decoding of telemetry_snapshot.json and, modulo shape, of
// telemetry_snapshot_rest.json. Writing it out longhand rather than deriving it from the fixture is
// deliberate: a test that recomputed the expectation from the input would pass no matter what the
// fixture said.
var wantSnapshot = protocol.Snapshot{
	TS:          1756713600000,
	SnapshotSeq: 4271,
	Tanks: []protocol.Tank{
		{
			Index: 1, OilType: "0号柴油",
			OilLevelMm: 1842.5, WaterLevelMm: 18.3, TempC: 23.6,
			OilVolumeL: 28450.2, WaterVolumeL: 210.4, OilWeightKg: 23901.7, NullVolumeL: 1549.8,
			OilRatioPct: 94.8, WaterRatioPct: 0.7,
			AlarmState: protocol.AlarmStateNormal, ProbeConnected: true,
		},
		{
			Index: 2, OilType: "92号汽油",
			OilLevelMm: 402.0, WaterLevelMm: 0, TempC: 22.1,
			OilVolumeL: 6030.0, WaterVolumeL: 0, OilWeightKg: 4522.5, NullVolumeL: 23970.0,
			OilRatioPct: 20.1, WaterRatioPct: 0,
			AlarmState: protocol.AlarmStateFault, ProbeConnected: false,
		},
	},
}

func TestSnapshot_DecodesDeviceFixture(t *testing.T) {
	got, err := protocol.UnmarshalTelemetry(readFixture(t, "telemetry_snapshot.json"))
	if err != nil {
		t.Fatalf("UnmarshalTelemetry: %v", err)
	}
	if !reflect.DeepEqual(got, wantSnapshot) {
		t.Errorf("snapshot mismatch\ngot:  %+v\nwant: %+v", got, wantSnapshot)
	}
}

func TestSnapshot_EncodesToDeviceFixture(t *testing.T) {
	got, err := wantSnapshot.MarshalTelemetry()
	if err != nil {
		t.Fatalf("MarshalTelemetry: %v", err)
	}
	assertSameJSON(t, got, readFixture(t, "telemetry_snapshot.json"))
}

// TestSnapshot_DecodesThingsBoardRESTFixture is the one that would have caught the trap in this
// integration: ThingsBoard hands every timeseries value back as a JSON *string*, so the exact same
// snapshot that decodes cleanly off MQTT arrives as {"tank1.oilLevelMm": "1842.5"} when read back
// through its REST API. Both must produce the identical Snapshot.
func TestSnapshot_DecodesThingsBoardRESTFixture(t *testing.T) {
	// Decoded key-by-key rather than into one typed map, so the fixture's "_comment" (a plain
	// string, not a series) can be skipped before anything is typed.
	var rest map[string]json.RawMessage
	if err := json.Unmarshal(readFixture(t, "telemetry_snapshot_rest.json"), &rest); err != nil {
		t.Fatalf("unmarshal REST fixture: %v", err)
	}

	values := map[string]any{}
	var ts int64
	for key, raw := range rest {
		if strings.HasPrefix(key, "_") {
			continue
		}
		var points []struct {
			TS    int64  `json:"ts"`
			Value string `json:"value"`
		}
		if err := json.Unmarshal(raw, &points); err != nil {
			t.Fatalf("key %q: %v", key, err)
		}
		if len(points) == 0 {
			continue
		}
		values[key] = points[0].Value
		ts = points[0].TS
	}

	got, err := protocol.ParseSnapshot(ts, values)
	if err != nil {
		t.Fatalf("ParseSnapshot: %v", err)
	}
	if !reflect.DeepEqual(got, wantSnapshot) {
		t.Errorf("snapshot from REST shape differs from the device shape\ngot:  %+v\nwant: %+v", got, wantSnapshot)
	}
}

// TestSnapshot_IgnoresUnknownKeys pins the forward-compatibility rule in docs/protocol-v1.md §2: an
// unrecognised key is skipped, never an error.
func TestSnapshot_IgnoresUnknownKeys(t *testing.T) {
	got, err := protocol.UnmarshalTelemetry(readFixture(t, "telemetry_snapshot_forward_compat.json"))
	if err != nil {
		t.Fatalf("a V1 reader must accept unknown keys, got error: %v", err)
	}
	if len(got.Tanks) != 1 {
		t.Fatalf("tanks = %d, want 1", len(got.Tanks))
	}
	if got.SnapshotSeq != 4272 || got.Tanks[0].OilLevelMm != 1842.5 {
		t.Errorf("known fields lost while skipping unknown ones: %+v", got)
	}
}

func TestSnapshotKeys_CoversEveryFixtureKey(t *testing.T) {
	var fixture struct {
		Values map[string]any `json:"values"`
	}
	if err := json.Unmarshal(readFixture(t, "telemetry_snapshot.json"), &fixture); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	declared := map[string]bool{}
	for _, k := range protocol.SnapshotKeys(2) {
		declared[k] = true
	}
	for k := range fixture.Values {
		if !declared[k] {
			t.Errorf("fixture key %q is not returned by SnapshotKeys — a subscriber would never ask ThingsBoard for it", k)
		}
	}
	if len(declared) != len(fixture.Values) {
		t.Errorf("SnapshotKeys(2) returned %d keys, fixture has %d", len(declared), len(fixture.Values))
	}
}

func TestAlarmEvent_RoundTripsFixtures(t *testing.T) {
	cases := []struct {
		file string
		want protocol.AlarmEvent
	}{
		{"alarm_active.json", protocol.AlarmEvent{
			AlarmID: "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa", Tank: 1,
			Type: protocol.AlarmHighHighLevel, Severity: protocol.SeverityCritical,
			Status: protocol.AlarmStatusActive, Text: "1号罐高高液位报警",
			Value: 1980.4, TSMs: 1756713612345,
		}},
		{"alarm_cleared.json", protocol.AlarmEvent{
			AlarmID: "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa", Tank: 1,
			Type: protocol.AlarmHighHighLevel, Severity: protocol.SeverityCritical,
			Status: protocol.AlarmStatusCleared, Text: "1号罐高高液位报警恢复",
			Value: 1842.5, TSMs: 1756713890123,
		}},
	}

	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			raw := readFixture(t, tc.file)

			var env struct {
				Values map[string]any `json:"values"`
			}
			if err := json.Unmarshal(raw, &env); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			got, err := protocol.ParseAlarmEvent(env.Values[protocol.KeyAlarm])
			if err != nil {
				t.Fatalf("ParseAlarmEvent: %v", err)
			}
			if got != tc.want {
				t.Errorf("decode mismatch\ngot:  %+v\nwant: %+v", got, tc.want)
			}

			encoded, err := tc.want.MarshalTelemetry()
			if err != nil {
				t.Fatalf("MarshalTelemetry: %v", err)
			}
			assertSameJSON(t, encoded, raw)
		})
	}
}

// TestAlarmEvent_ParsesStringifiedValue covers the ThingsBoard read-back path for a JSON-typed
// telemetry key, where the whole alarm object comes back as one JSON string.
func TestAlarmEvent_ParsesStringifiedValue(t *testing.T) {
	var env struct {
		Values map[string]json.RawMessage `json:"values"`
	}
	if err := json.Unmarshal(readFixture(t, "alarm_active.json"), &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	stringified := string(env.Values[protocol.KeyAlarm])

	got, err := protocol.ParseAlarmEvent(stringified)
	if err != nil {
		t.Fatalf("ParseAlarmEvent on a stringified value: %v", err)
	}
	if got.AlarmID != "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa" || got.Status != protocol.AlarmStatusActive {
		t.Errorf("got %+v", got)
	}
}

func TestCmdResult_RoundTripsFixtures(t *testing.T) {
	cases := []struct {
		file      string
		status    string
		errorCode string
		hasDetail bool
	}{
		{"cmd_result_succeeded.json", protocol.CmdStatusSucceeded, "", true},
		{"cmd_result_failed.json", protocol.CmdStatusFailed, protocol.ErrHardwareFault, false},
	}

	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			raw := readFixture(t, tc.file)
			var env struct {
				Values map[string]any `json:"values"`
			}
			if err := json.Unmarshal(raw, &env); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			got, err := protocol.ParseCmdResult(env.Values[protocol.KeyCmdResult])
			if err != nil {
				t.Fatalf("ParseCmdResult: %v", err)
			}
			if got.CommandID != "c1a7e3d8-5b62-4d19-8f30-1e9a4c77b201" {
				t.Errorf("commandId = %q", got.CommandID)
			}
			if got.Status != tc.status || got.ErrorCode != tc.errorCode {
				t.Errorf("status/errorCode = %q/%q, want %q/%q", got.Status, got.ErrorCode, tc.status, tc.errorCode)
			}
			if tc.hasDetail != (len(got.Detail) > 0) {
				t.Errorf("detail presence = %v, want %v", len(got.Detail) > 0, tc.hasDetail)
			}
			if !protocol.IsTerminal(got.Status) {
				t.Errorf("%q should be a terminal status", got.Status)
			}

			encoded, err := got.MarshalTelemetry()
			if err != nil {
				t.Fatalf("MarshalTelemetry: %v", err)
			}
			assertSameJSON(t, encoded, raw)
		})
	}
}

func TestRPCRequest_RoundTripsFixture(t *testing.T) {
	raw := readFixture(t, "rpc_request.json")

	var got protocol.Request
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Method != protocol.MethodPumpStart {
		t.Errorf("method = %q", got.Method)
	}
	if got.Params.ProtocolVersion != protocol.Version {
		t.Errorf("protocolVersion = %d, want %d", got.Params.ProtocolVersion, protocol.Version)
	}
	if got.Params.Reason == "" {
		t.Error("reason must survive the round trip — it is the audited 二次确认 justification")
	}
	if err := protocol.ValidateEnvelope(got.Method, got.Params); err != nil {
		t.Errorf("the reference request fixture must validate: %v", err)
	}

	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	assertSameJSON(t, encoded, raw)
}

func TestAck_DecodesFixtures(t *testing.T) {
	cases := []struct {
		file      string
		status    string
		errorCode string
		duplicate bool
	}{
		{"rpc_ack_received.json", protocol.CmdStatusReceived, "", false},
		{"rpc_ack_rejected.json", protocol.CmdStatusRejected, protocol.ErrInterlockBlock, false},
		{"rpc_ack_duplicate.json", protocol.CmdStatusReceived, "", true},
	}

	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			got, err := protocol.ParseAck(readFixture(t, tc.file))
			if err != nil {
				t.Fatalf("ParseAck: %v", err)
			}
			if got.Status != tc.status || got.ErrorCode != tc.errorCode || got.Duplicate != tc.duplicate {
				t.Errorf("got status=%q errorCode=%q duplicate=%v, want %q/%q/%v",
					got.Status, got.ErrorCode, got.Duplicate, tc.status, tc.errorCode, tc.duplicate)
			}
			// An ACK is never a terminal outcome except for a rejection: "received" means the
			// device took the request, not that it did the thing.
			if protocol.IsTerminal(got.Status) != (tc.status == protocol.CmdStatusRejected) {
				t.Errorf("IsTerminal(%q) = %v", got.Status, protocol.IsTerminal(got.Status))
			}
		})
	}
}

func TestClientAttributes_FixtureUsesDeclaredKeys(t *testing.T) {
	var attrs map[string]any
	if err := json.Unmarshal(readFixture(t, "client_attributes.json"), &attrs); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	declared := map[string]bool{
		protocol.AttrProtocolVersion:    true,
		protocol.AttrDeviceModel:        true,
		protocol.AttrHardwareVersion:    true,
		protocol.AttrTankCount:          true,
		protocol.AttrLocalControlMode:   true,
		protocol.ClientCurrentFwTitle:   true,
		protocol.ClientCurrentFwVersion: true,
	}
	for i := 1; i <= 2; i++ {
		declared[protocol.TankKey(i, protocol.AttrProbeAddress)] = true
		declared[protocol.TankKey(i, protocol.AttrProbeType)] = true
		declared[protocol.TankKey(i, protocol.AttrCapacityL)] = true
	}

	for k := range attrs {
		if strings.HasPrefix(k, "_") {
			continue
		}
		if !declared[k] {
			t.Errorf("fixture declares attribute %q that internal/protocol has no constant for", k)
		}
	}
	if v, _ := attrs[protocol.AttrProtocolVersion].(float64); int(v) != protocol.Version {
		t.Errorf("fixture protocolVersion = %v, want %d", attrs[protocol.AttrProtocolVersion], protocol.Version)
	}
}

func TestValidateEnvelope_RejectsMalformedCommands(t *testing.T) {
	base := protocol.Envelope{
		CommandID:       "c1a7e3d8-5b62-4d19-8f30-1e9a4c77b201",
		ProtocolVersion: protocol.Version,
		IssuedAtMs:      1756713600000,
		ExpiresAtMs:     1756713660000,
		Operator:        "zhangsan@customer-a",
		Reason:          "手动补油，值班室确认",
	}
	if err := protocol.ValidateEnvelope(protocol.MethodPumpStart, base); err != nil {
		t.Fatalf("baseline envelope should validate: %v", err)
	}

	cases := map[string]func(e *protocol.Envelope) string{
		"unknown method":      func(e *protocol.Envelope) string { return "pump.explode" },
		"missing commandId":   func(e *protocol.Envelope) string { e.CommandID = ""; return protocol.MethodPumpStart },
		"wrong version":       func(e *protocol.Envelope) string { e.ProtocolVersion = 99; return protocol.MethodPumpStart },
		"expiry before issue": func(e *protocol.Envelope) string { e.ExpiresAtMs = e.IssuedAtMs - 1; return protocol.MethodPumpStart },
		"missing 二次确认 reason": func(e *protocol.Envelope) string { e.Reason = ""; return protocol.MethodPumpStart },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			e := base
			method := mutate(&e)
			if err := protocol.ValidateEnvelope(method, e); err == nil {
				t.Error("expected a validation error, got nil")
			}
		})
	}
}

// TestMethods_PhysicalActionsAreFlagged pins the safety classification issue #1 depends on: the
// backend gates physical methods behind the device-side interlock work being finished, so a method
// silently flipping to Physical=false would quietly open a pump start to production.
func TestMethods_PhysicalActionsAreFlagged(t *testing.T) {
	wantPhysical := map[string]bool{
		protocol.MethodPumpStart:    true,
		protocol.MethodPumpStop:     true,
		protocol.MethodValveOpen:    true,
		protocol.MethodValveClose:   true,
		protocol.MethodDeviceReboot: true,
	}
	for method, want := range wantPhysical {
		spec, ok := protocol.Methods[method]
		if !ok {
			t.Fatalf("method %q missing from the registry", method)
		}
		if spec.Physical != want {
			t.Errorf("%s: Physical = %v, want %v", method, spec.Physical, want)
		}
	}
	for _, m := range []string{protocol.MethodConfigAlarmThreshold, protocol.MethodConfigTank, protocol.MethodConfigProbe} {
		if protocol.Methods[m].Permission != protocol.PermAdmin {
			t.Errorf("%s must require admin permission", m)
		}
	}
}
