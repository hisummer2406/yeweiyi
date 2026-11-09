package protocol_test

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

// Shared fixtures for 交班/库存 (cloud#85), drafted by the device side and adopted here. Expectations
// are written out by hand rather than derived from the fixtures, so a fixture edit that changes a
// value or drops a key fails here.

func f64(v float64) *float64 { return &v }
func i64(v int64) *int64     { return &v }
func iptr(v int) *int        { return &v }

// fixtureValue returns the envelope ts and the raw value under key.
func fixtureValue(t *testing.T, file, key string) (int64, any) {
	t.Helper()
	var env struct {
		TS     int64          `json:"ts"`
		Values map[string]any `json:"values"`
	}
	if err := json.Unmarshal(readFixture(t, file), &env); err != nil {
		t.Fatalf("unmarshal %s: %v", file, err)
	}
	v, ok := env.Values[key]
	if !ok {
		t.Fatalf("%s has no values.%s", file, key)
	}
	return env.TS, v
}

var (
	shiftTank1Open = protocol.ShiftTank{Tank: 1, OilType: "92#", OilTypeIndex: iptr(5), BeginLevelMm: f64(1520.0), BeginVolumeL: f64(18230.5)}
	shiftTank2Open = protocol.ShiftTank{Tank: 2, OilType: "0#", OilTypeIndex: iptr(1), BeginLevelMm: f64(980.0), BeginVolumeL: f64(11020.0)}
	shiftTank1Done = protocol.ShiftTank{Tank: 1, OilType: "92#", OilTypeIndex: iptr(5), BeginLevelMm: f64(1520.0), BeginVolumeL: f64(18230.5),
		EndLevelMm: f64(1310.0), EndVolumeL: f64(15710.5), OutVolumeL: f64(2520.0), OutWeightKg: f64(1864.8)}
	shiftTank2Done = protocol.ShiftTank{Tank: 2, OilType: "0#", OilTypeIndex: iptr(1), BeginLevelMm: f64(980.0), BeginVolumeL: f64(11020.0),
		EndLevelMm: f64(905.0), EndVolumeL: f64(10180.0), OutVolumeL: f64(840.0), OutWeightKg: f64(705.6)}
)

func TestShiftEvent_RoundTripsFixtures(t *testing.T) {
	const id, begin, end = "shift-1762146400000", int64(1762146400000), int64(1762175200000)
	cases := []struct {
		file  string
		envTS int64
		want  protocol.ShiftEvent
	}{
		{"shift_event_open.json", begin, protocol.ShiftEvent{ShiftID: id, Status: protocol.ShiftOpen, BeginTimeMs: begin,
			Tanks: []protocol.ShiftTank{shiftTank1Open, shiftTank2Open}}},
		{"shift_event_closed.json", end, protocol.ShiftEvent{ShiftID: id, Status: protocol.ShiftClosed, BeginTimeMs: begin, EndTimeMs: i64(end),
			Tanks: []protocol.ShiftTank{shiftTank1Done, shiftTank2Done}}},
		// A tank added mid-shift: end figures only, and no oil type — never filled from current settings.
		{"shift_event_tank_added.json", end, protocol.ShiftEvent{ShiftID: id, Status: protocol.ShiftClosed, BeginTimeMs: begin, EndTimeMs: i64(end),
			Tanks: []protocol.ShiftTank{shiftTank1Done, {Tank: 3, EndLevelMm: f64(600.0), EndVolumeL: f64(6000.0)}}}},
		// A tank removed mid-shift keeps its begin figures and has no end figures.
		{"shift_event_tank_removed.json", end, protocol.ShiftEvent{ShiftID: id, Status: protocol.ShiftClosed, BeginTimeMs: begin, EndTimeMs: i64(end),
			Tanks: []protocol.ShiftTank{shiftTank1Done, shiftTank2Open}}},
	}
	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			envTS, v := fixtureValue(t, tc.file, protocol.KeyShiftEvent)
			if envTS != tc.envTS {
				t.Errorf("envelope ts = %d, want %d", envTS, tc.envTS)
			}
			got, err := protocol.ParseShiftEvent(v)
			if err != nil {
				t.Fatalf("ParseShiftEvent: %v", err)
			}
			if !reflect.DeepEqual(got, tc.want) {
				gb, _ := json.Marshal(got)
				wb, _ := json.Marshal(tc.want)
				t.Errorf("decode mismatch\ngot:  %s\nwant: %s", gb, wb)
			}
			encoded, err := tc.want.MarshalTelemetry(tc.envTS)
			if err != nil {
				t.Fatal(err)
			}
			assertSameJSON(t, encoded, readFixture(t, tc.file))
		})
	}
}

func TestShiftEvent_OpenShiftHasNoZeroEndFigures(t *testing.T) {
	// The device omits what it does not have. If the fixture ever grew "endLevelMm": 0 for an open
	// shift, that zero would be stored and shown as a measured level.
	raw := string(readFixture(t, "shift_event_open.json"))
	for _, key := range []string{"endTimeMs", "endLevelMm", "endVolumeL", "outVolumeL", "outWeightKg"} {
		if strings.Contains(raw, `"`+key+`"`) {
			t.Errorf("open shift fixture carries %q", key)
		}
	}
}

func TestStockRecord_RoundTripsFixture(t *testing.T) {
	want := protocol.StockRecord{
		RecordID: "stock-1762153200000", Trigger: protocol.StockTriggerScheduled, TSMs: 1762153200000,
		Tanks: []protocol.StockRecordTank{
			{Tank: 1, OilType: "92#", OilTypeIndex: iptr(5), LevelMm: f64(1498.0), VolumeL: f64(17960.2), WaterLevelMm: f64(12.0), TempC: f64(18.6), WeightKg: f64(13290.5)},
			{Tank: 2, OilType: "0#", OilTypeIndex: iptr(1), LevelMm: f64(960.0), VolumeL: f64(10790.0), WaterLevelMm: f64(0.0), TempC: f64(17.9), WeightKg: f64(9063.6)},
		},
	}
	envTS, v := fixtureValue(t, "stock_record.json", protocol.KeyStockRecord)
	got, err := protocol.ParseStockRecord(v)
	if err != nil {
		t.Fatalf("ParseStockRecord: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		gb, _ := json.Marshal(got)
		wb, _ := json.Marshal(want)
		t.Errorf("decode mismatch\ngot:  %s\nwant: %s", gb, wb)
	}
	if envTS != want.TSMs {
		t.Errorf("envelope ts %d != tsMs %d", envTS, want.TSMs)
	}
	// A real 0 water level is present and stays a value, not a missing one.
	if got.Tanks[1].WaterLevelMm == nil || *got.Tanks[1].WaterLevelMm != 0 {
		t.Errorf("tank 2 waterLevelMm = %v, want a present 0", got.Tanks[1].WaterLevelMm)
	}
	encoded, err := want.MarshalTelemetry()
	if err != nil {
		t.Fatal(err)
	}
	assertSameJSON(t, encoded, readFixture(t, "stock_record.json"))
}

func TestStockEvent_DeliveredWeightAndOilTypeIndex(t *testing.T) {
	_, v := fixtureValue(t, "stock_event_in_delivered_weight.json", protocol.KeyStockEvent)
	got, err := protocol.ParseStockEvent(v)
	if err != nil {
		t.Fatal(err)
	}
	if got.DeliveredWeightKg == nil || *got.DeliveredWeightKg != 15472.8 {
		t.Errorf("deliveredWeightKg = %v, want 15472.8", got.DeliveredWeightKg)
	}
	if got.OilTypeIndex == nil || *got.OilTypeIndex != 1 {
		t.Errorf("oilTypeIndex = %v, want 1", got.OilTypeIndex)
	}
	encoded, err := got.MarshalTelemetry()
	if err != nil {
		t.Fatal(err)
	}
	assertSameJSON(t, encoded, readFixture(t, "stock_event_in_delivered_weight.json"))

	// Older firmware: both absent, not zero.
	_, old := fixtureValue(t, "stock_event_in.json", protocol.KeyStockEvent)
	prev, err := protocol.ParseStockEvent(old)
	if err != nil {
		t.Fatal(err)
	}
	if prev.DeliveredWeightKg != nil || prev.OilTypeIndex != nil {
		t.Errorf("stock_event_in.json: new fields should be absent, got %v / %v", prev.DeliveredWeightKg, prev.OilTypeIndex)
	}
}

func TestAlarmEvent_OilType(t *testing.T) {
	want := protocol.AlarmEvent{
		AlarmID: "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa", Tank: 1,
		Type: protocol.AlarmHighHighLevel, Severity: protocol.SeverityCritical,
		Status: protocol.AlarmStatusActive, Text: "1号罐高高液位报警",
		OilType: "0#", OilTypeIndex: iptr(1),
		Value: 1980.4, TSMs: 1756713612345,
	}
	_, v := fixtureValue(t, "alarm_active_oil_type.json", protocol.KeyAlarm)
	got, err := protocol.ParseAlarmEvent(v)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("decode mismatch\ngot:  %+v\nwant: %+v", got, want)
	}
	encoded, err := want.MarshalTelemetry()
	if err != nil {
		t.Fatal(err)
	}
	assertSameJSON(t, encoded, readFixture(t, "alarm_active_oil_type.json"))
}

// TestOilTypeIndexTable pins the dictionary's layout: clients localize from it, so a renumbering on
// the device must show up here.
func TestOilTypeIndexTable(t *testing.T) {
	var table struct {
		Unset   int                          `json:"unset"`
		Builtin map[string]map[string]string `json:"builtin"`
		Custom  struct{ From, To int }       `json:"custom"`
	}
	if err := json.Unmarshal(readFixture(t, "oil_type_index.json"), &table); err != nil {
		t.Fatal(err)
	}
	if table.Unset != 0 || len(table.Builtin) != 31 || table.Custom.From != 32 || table.Custom.To != 39 {
		t.Errorf("layout changed: unset=%d builtin=%d custom=%d..%d", table.Unset, len(table.Builtin), table.Custom.From, table.Custom.To)
	}
	for idx, zh := range map[string]string{"1": "0#", "5": "92#", "17": "溶剂油", "18": "丙二醇", "31": "西排水"} {
		if got := table.Builtin[idx]["zh"]; got != zh {
			t.Errorf("builtin[%s].zh = %q, want %q", idx, got, zh)
		}
	}
}

func TestParseShiftEvent_Rejects(t *testing.T) {
	for name, raw := range map[string]string{
		"no id":          `{"status":"OPEN","beginTimeMs":1,"tanks":[{"tank":1}]}`,
		"bad status":     `{"shiftId":"s","status":"DONE","beginTimeMs":1,"tanks":[{"tank":1}]}`,
		"no begin":       `{"shiftId":"s","status":"OPEN","tanks":[{"tank":1}]}`,
		"closed, no end": `{"shiftId":"s","status":"CLOSED","beginTimeMs":1,"tanks":[{"tank":1}]}`,
		"no tanks":       `{"shiftId":"s","status":"OPEN","beginTimeMs":1,"tanks":[]}`,
		"tank 0":         `{"shiftId":"s","status":"OPEN","beginTimeMs":1,"tanks":[{"tank":0}]}`,
		"repeated tank":  `{"shiftId":"s","status":"OPEN","beginTimeMs":1,"tanks":[{"tank":1},{"tank":1}]}`,
		"not json":       `CommitWork{id=1}`,
	} {
		if _, err := protocol.ParseShiftEvent(raw); err == nil {
			t.Errorf("%s: accepted %s", name, raw)
		}
	}
}

func TestParseStockRecord_Rejects(t *testing.T) {
	for name, raw := range map[string]string{
		"no id":         `{"tsMs":1,"tanks":[{"tank":1}]}`,
		"no ts":         `{"recordId":"r","tanks":[{"tank":1}]}`,
		"bad trigger":   `{"recordId":"r","tsMs":1,"trigger":"AUTO","tanks":[{"tank":1}]}`,
		"no tanks":      `{"recordId":"r","tsMs":1}`,
		"tank too big":  `{"recordId":"r","tsMs":1,"tanks":[{"tank":65}]}`,
		"repeated tank": `{"recordId":"r","tsMs":1,"tanks":[{"tank":2},{"tank":2}]}`,
	} {
		if _, err := protocol.ParseStockRecord(raw); err == nil {
			t.Errorf("%s: accepted %s", name, raw)
		}
	}
}
