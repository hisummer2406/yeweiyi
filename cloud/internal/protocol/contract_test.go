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
	// Only tank1 has calibration attributes in the fixture — see its _comment for why that's
	// deliberate rather than an oversight.
	declared[protocol.TankKey(1, protocol.AttrCalibrationHeightMm)] = true
	declared[protocol.TankKey(1, protocol.AttrCalibrationVolumeL)] = true

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

// TestParseTankCalibration_FixtureRoundTrips covers both cases the client_attributes.json fixture
// deliberately carries: tank1 has a calibration curve, tank2 does not.
func TestParseTankCalibration_FixtureRoundTrips(t *testing.T) {
	var attrs map[string]any
	if err := json.Unmarshal(readFixture(t, "client_attributes.json"), &attrs); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	got, ok, err := protocol.ParseTankCalibration(attrs, 1)
	if err != nil {
		t.Fatalf("ParseTankCalibration(tank 1): %v", err)
	}
	if !ok {
		t.Fatal("tank 1 should have a calibration curve in the fixture")
	}
	wantHeights := []float64{0, 500, 1000}
	wantVolumes := []float64{0.0, 14225.1, 28450.2}
	if !reflect.DeepEqual(got.HeightMm, wantHeights) || !reflect.DeepEqual(got.VolumeL, wantVolumes) {
		t.Errorf("tank 1 calibration = %+v, want heights=%v volumes=%v", got, wantHeights, wantVolumes)
	}

	_, ok, err = protocol.ParseTankCalibration(attrs, 2)
	if err != nil {
		t.Fatalf("ParseTankCalibration(tank 2): %v", err)
	}
	if ok {
		t.Error("tank 2 has no calibration attributes in the fixture; ok should be false, not an error")
	}
}

// TestParseTankCalibration_ToleratesStringifiedArray covers the ThingsBoard REST/WebSocket read-back
// shape — see convert.go's doc comment on why every value in this package tolerates a JSON-encoded
// string in place of the native type.
func TestParseTankCalibration_ToleratesStringifiedArray(t *testing.T) {
	attrs := map[string]any{
		protocol.TankKey(1, protocol.AttrCalibrationHeightMm): "[0,500,1000]",
		protocol.TankKey(1, protocol.AttrCalibrationVolumeL):  "[0.0,14225.1,28450.2]",
	}
	got, ok, err := protocol.ParseTankCalibration(attrs, 1)
	if err != nil {
		t.Fatalf("ParseTankCalibration: %v", err)
	}
	if !ok {
		t.Fatal("expected ok=true")
	}
	if !reflect.DeepEqual(got.HeightMm, []float64{0, 500, 1000}) {
		t.Errorf("HeightMm = %v", got.HeightMm)
	}
}

// TestParseTankCalibration_RejectsMismatchedLength pins that a height/volume length mismatch is a
// parse error, not a silently truncated or zero-padded curve — a calibration curve with mismatched
// pairs is corrupt data, not a shape a caller should have to defend against downstream.
func TestParseTankCalibration_RejectsMismatchedLength(t *testing.T) {
	attrs := map[string]any{
		protocol.TankKey(1, protocol.AttrCalibrationHeightMm): []any{0.0, 500.0, 1000.0},
		protocol.TankKey(1, protocol.AttrCalibrationVolumeL):  []any{0.0, 14225.1},
	}
	if _, _, err := protocol.ParseTankCalibration(attrs, 1); err == nil {
		t.Error("expected a length-mismatch error, got nil")
	}
}

// TestParseTankCalibration_RejectsPartialPublish pins that publishing only one of the two paired
// attributes is an error, not a curve silently treated as absent — that would hide a real bug on the
// device side (or a ThingsBoard attribute write that partially failed) as "not configured yet".
func TestParseTankCalibration_RejectsPartialPublish(t *testing.T) {
	attrs := map[string]any{
		protocol.TankKey(1, protocol.AttrCalibrationHeightMm): []any{0.0, 500.0},
	}
	if _, _, err := protocol.ParseTankCalibration(attrs, 1); err == nil {
		t.Error("expected an error for a height-only publish, got nil")
	}
}

func TestParseTankCalibration_RejectsMalformedCurves(t *testing.T) {
	cases := map[string]struct{ h, v []any }{
		"descending heights": {[]any{0.0, 500.0, 400.0}, []any{0.0, 1.0, 2.0}},
		"repeated height":    {[]any{0.0, 500.0, 500.0}, []any{0.0, 1.0, 2.0}},
		"decreasing volume":  {[]any{0.0, 500.0, 1000.0}, []any{0.0, 9.0, 5.0}},
		"negative volume":    {[]any{0.0, 500.0}, []any{-1.0, 5.0}},
		"empty":              {[]any{}, []any{}},
		// null 元素不能被当成 0：否则 [0,null,500] 会静默变成合法曲线。
		"null height element": {[]any{0.0, nil, 500.0}, []any{0.0, 1.0, 2.0}},
		"null volume element": {[]any{0.0, 250.0, 500.0}, []any{0.0, nil, 2.0}},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			attrs := map[string]any{
				protocol.TankKey(1, protocol.AttrCalibrationHeightMm): c.h,
				protocol.TankKey(1, protocol.AttrCalibrationVolumeL):  c.v,
			}
			if _, ok, err := protocol.ParseTankCalibration(attrs, 1); err == nil || ok {
				t.Errorf("ok=%v err=%v, want a rejection", ok, err)
			}
		})
	}
}

func TestParseTankCalibration_NullAttributesAreAbsent(t *testing.T) {
	attrs := map[string]any{
		protocol.TankKey(1, protocol.AttrCalibrationHeightMm): nil,
		protocol.TankKey(1, protocol.AttrCalibrationVolumeL):  nil,
	}
	if _, ok, err := protocol.ParseTankCalibration(attrs, 1); ok || err != nil {
		t.Errorf("ok=%v err=%v, want absent without error", ok, err)
	}
}

func TestParseStockEvent_Source(t *testing.T) {
	for _, src := range []string{"", "AUTO", "MANUAL"} {
		raw := `{"eventId":"in-1-1","direction":"IN","source":"` + src + `"}`
		if _, err := protocol.ParseStockEvent(raw); err != nil {
			t.Errorf("source %q rejected: %v", src, err)
		}
	}
	if _, err := protocol.ParseStockEvent(`{"eventId":"in-1-1","direction":"IN","source":"manual"}`); err == nil {
		t.Error("lower-case/unknown source accepted; want a parse error")
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

func TestStockEvent_RoundTripsFixtures(t *testing.T) {
	cases := []struct {
		file           string
		direction      string
		netVolumeL     float64
		duringDelivery float64
	}{
		{"stock_event_in.json", protocol.StockIn, 18000.0, 420.0},
		{"stock_event_out.json", protocol.StockOut, -5000.0, 0},
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
			got, err := protocol.ParseStockEvent(env.Values[protocol.KeyStockEvent])
			if err != nil {
				t.Fatalf("ParseStockEvent: %v", err)
			}

			if got.Direction != tc.direction {
				t.Errorf("direction = %q, want %q", got.Direction, tc.direction)
			}
			if got.NetVolumeL != tc.netVolumeL {
				t.Errorf("netVolumeL = %v, want %v", got.NetVolumeL, tc.netVolumeL)
			}
			if got.DuringDeliveryVolumeL != tc.duringDelivery {
				t.Errorf("duringDeliveryVolumeL = %v, want %v", got.DuringDeliveryVolumeL, tc.duringDelivery)
			}
			if got.EventID == "" || got.Tank != 1 {
				t.Errorf("identity fields lost: %+v", got)
			}
			// The movement window must be distinct from the record-closed timestamp — a delivery
			// takes time, and reconciliation cares when it started.
			if got.BeginTimeMs >= got.EndTimeMs {
				t.Errorf("beginTimeMs %d should precede endTimeMs %d", got.BeginTimeMs, got.EndTimeMs)
			}

			encoded, err := got.MarshalTelemetry()
			if err != nil {
				t.Fatalf("MarshalTelemetry: %v", err)
			}
			assertSameJSON(t, encoded, raw)
		})
	}
}

// TestStockEvent_NetVolumeSignIsTankRelative pins the sign convention: netVolumeL is always the
// tank's own change, so a reader never has to branch on direction to interpret it.
func TestStockEvent_NetVolumeSignIsTankRelative(t *testing.T) {
	for _, f := range []string{"stock_event_in.json", "stock_event_out.json"} {
		var env struct {
			Values map[string]any `json:"values"`
		}
		if err := json.Unmarshal(readFixture(t, f), &env); err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		ev, err := protocol.ParseStockEvent(env.Values[protocol.KeyStockEvent])
		if err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		if want := ev.EndVolumeL - ev.BeginVolumeL; ev.NetVolumeL != want {
			t.Errorf("%s: netVolumeL = %v, but end-begin = %v; the sign convention is tank-relative",
				f, ev.NetVolumeL, want)
		}
	}
}

// TestStockEvent_ParsesStringifiedValue covers the ThingsBoard read-back path, where a JSON-typed
// telemetry value comes back as one JSON string.
func TestStockEvent_ParsesStringifiedValue(t *testing.T) {
	var env struct {
		Values map[string]json.RawMessage `json:"values"`
	}
	if err := json.Unmarshal(readFixture(t, "stock_event_in.json"), &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	got, err := protocol.ParseStockEvent(string(env.Values[protocol.KeyStockEvent]))
	if err != nil {
		t.Fatalf("ParseStockEvent on a stringified value: %v", err)
	}
	if got.EventID != "in-1-1756713600000" || got.OilType != "0号柴油" {
		t.Errorf("got %+v", got)
	}
}
