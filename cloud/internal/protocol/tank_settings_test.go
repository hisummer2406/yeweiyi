package protocol_test

import (
	"encoding/json"
	"errors"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

// attrsFromJSON turns a hand-written ThingsBoard-style attribute object into Attributes, all stamped
// with ts. Decoding with encoding/json gives float64 numbers, exactly what the REST client yields.
func attrsFromJSON(t *testing.T, raw string, ts int64) []protocol.Attribute {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	var out []protocol.Attribute
	for k, v := range m {
		out = append(out, protocol.Attribute{Key: k, Value: v, TS: ts})
	}
	return out
}

func TestParseTankSettings_DeviceShape(t *testing.T) {
	attrs := attrsFromJSON(t, `{
	  "tank1.tankHeightMm": 2500, "tank1.sensorLengthMm": 2600.5, "tank1.tankShape": 0,
	  "tank1.oilType": "92#", "tank1.oilTypeIndex": 3,
	  "tank1.alarmHighHighLevelMm": 2300, "tank1.alarmHighLevelMm": 2200,
	  "tank1.alarmLowLevelMm": 300, "tank1.alarmLowLowLevelMm": 0,
	  "tank1.alarmHighWaterMm": 50, "tank1.alarmHighTempC": 0, "tank1.alarmLowTempC": -10,
	  "tank1.levelCorrectionMm": -1.5, "tank1.waterCorrectionMm": 0, "tank1.tempCorrectionC": 0.5,
	  "tank1.density": 0.56,
	  "tank1.probeAddress": "01", "tank1.capacityL": 30000.0,
	  "tankCount": 2, "tank2.tankShape": 1
	}`, 1000)
	for i := range attrs {
		if attrs[i].Key == "tank1.density" {
			attrs[i].TS = 5000 // newest key decides reportedAt
		}
	}
	got, skipped := protocol.ParseTankSettings(attrs)
	if len(skipped) != 0 {
		t.Fatalf("skipped = %v", skipped)
	}
	if len(got) != 2 || got[0].Tank != 1 || got[1].Tank != 2 {
		t.Fatalf("tanks = %+v", got)
	}
	t1 := got[0]
	if *t1.TankHeightMm != 2500 || *t1.SensorLengthMm != 2600.5 || *t1.TankShape != 0 ||
		*t1.OilType != "92#" || *t1.OilTypeIndex != 3 || *t1.AlarmLowLowLevelMm != 0 ||
		*t1.AlarmLowTempC != -10 || *t1.Density != 0.56 || *t1.LevelCorrectionMm != -1.5 {
		t.Errorf("tank1 = %+v", t1)
	}
	if t1.ReportedAtMs != 5000 {
		t.Errorf("reportedAt = %d, want newest key ts 5000", t1.ReportedAtMs)
	}
	if got[1].TankHeightMm != nil || *got[1].TankShape != 1 {
		t.Errorf("an unreported key must stay nil: %+v", got[1])
	}
}

func TestParseTankSettings_BadValuesAreSkippedIndividually(t *testing.T) {
	attrs := attrsFromJSON(t, `{
	  "tank1.tankHeightMm": 2500.5, "tank1.density": "0.56", "tank1.oilType": 7,
	  "tank1.tankShape": 1,
	  "tank17.tankShape": 1, "tank0.tankShape": 1, "tankX.tankShape": 1
	}`, 1)
	got, skipped := protocol.ParseTankSettings(attrs)
	if len(got) != 1 || got[0].TankHeightMm != nil || got[0].Density != nil || got[0].OilType != nil {
		t.Fatalf("got = %+v", got)
	}
	if got[0].TankShape == nil || *got[0].TankShape != 1 {
		t.Errorf("one bad key must not poison the others: %+v", got[0])
	}
	// tank17, tank0 are out of range and reported; tankX is not a tank key at all.
	if len(skipped) != 5 {
		t.Errorf("skipped = %v", skipped)
	}
}

// TestParseTankSettings_SharedFixture reads the shared client_attributes_tank_settings.json, the file
// the device repo is expected to consume too, so a key rename on either side is caught here.
func TestParseTankSettings_SharedFixture(t *testing.T) {
	var m map[string]any
	if err := json.Unmarshal(readFixture(t, "client_attributes_tank_settings.json"), &m); err != nil {
		t.Fatal(err)
	}
	delete(m, "_comment")
	var attrs []protocol.Attribute
	for k, v := range m {
		attrs = append(attrs, protocol.Attribute{Key: k, Value: v, TS: 42})
	}
	got, skipped := protocol.ParseTankSettings(attrs)
	if len(skipped) != 0 {
		t.Fatalf("a fixture key was not understood: %v", skipped)
	}
	if len(got) != 2 {
		t.Fatalf("tanks = %+v", got)
	}
	t1 := got[0]
	if t1.TankHeightMm == nil || t1.SensorLengthMm == nil || t1.TankShape == nil || t1.OilType == nil ||
		t1.OilTypeIndex == nil || t1.AlarmHighHighLevelMm == nil || t1.AlarmHighLevelMm == nil ||
		t1.AlarmLowLevelMm == nil || t1.AlarmLowLowLevelMm == nil || t1.AlarmHighWaterMm == nil ||
		t1.AlarmHighTempC == nil || t1.AlarmLowTempC == nil || t1.LevelCorrectionMm == nil ||
		t1.WaterCorrectionMm == nil || t1.TempCorrectionC == nil || t1.Density == nil || t1.CapacityL == nil || t1.OilColor == nil {
		t.Errorf("every documented tank1 key should parse to a value: %+v", t1)
	}
	if *got[1].OilTypeIndex != 19 || *got[1].Density != 0.84 {
		t.Errorf("tank2 = %+v", got[1])
	}
}

func argsErr(t *testing.T, method, args string) *protocol.ArgsError {
	t.Helper()
	err := protocol.ValidateArgs(method, json.RawMessage(args))
	var ae *protocol.ArgsError
	if err == nil || !errors.As(err, &ae) {
		t.Fatalf("%s %s: err = %v, want *ArgsError", method, args, err)
	}
	return ae
}

func TestValidateArgs_SharedRequestFixtureIsValid(t *testing.T) {
	var req struct {
		Method string `json:"method"`
		Params struct {
			Args json.RawMessage `json:"args"`
		} `json:"params"`
	}
	if err := json.Unmarshal(readFixture(t, "rpc_request_set_tank_settings.json"), &req); err != nil {
		t.Fatal(err)
	}
	if req.Method != protocol.MethodConfigTankSettings {
		t.Fatalf("method = %q", req.Method)
	}
	if err := protocol.ValidateArgs(req.Method, req.Params.Args); err != nil {
		t.Errorf("the shared request fixture must pass the cloud's own check: %v", err)
	}
}

func TestValidateArgs_TankSettings(t *testing.T) {
	const m = protocol.MethodConfigTankSettings
	for _, ok := range []string{
		`{"tank":1,"alarmHighLevelMm":650}`,
		`{"tank":16,"density":2}`,
		`{"tank":1,"alarmLowLowLevelMm":0,"alarmLowLevelMm":0,"alarmHighLevelMm":500,"alarmHighHighLevelMm":600}`, // 0 = off, no order
		`{"tank":2,"alarmHighTempC":-50,"levelCorrectionMm":-100,"tempCorrectionC":10,"oilTypeIndex":39,"capacityL":0}`,
		`{"tank":1,"levelCorrectionMm":1000,"waterCorrectionMm":-1000.5,"tempCorrectionC":-100.5}`, // #28: 修正值不设上下限
		`{"tank":1,"oilTypeIndex":32,"customOilName":"二厂盐酸","customOilNameEn":"HCl"}`,
		`{"tank":1,"alarmHighLevelMm":650.0}`, // integral value written with a fraction is still an integer
	} {
		if err := protocol.ValidateArgs(m, json.RawMessage(ok)); err != nil {
			t.Errorf("%s: %v", ok, err)
		}
	}

	cases := []struct{ args, field, code string }{
		{`{"density":0.5}`, "tank", protocol.ArgMissingTank},
		{`{"tank":0,"density":0.5}`, "tank", protocol.ArgMissingTank},
		{`{"tank":17,"density":0.5}`, "tank", protocol.ArgMissingTank},
		{`{"tank":1.5,"density":0.5}`, "tank", protocol.ArgMissingTank},
		{`{"tank":1}`, "args", protocol.ArgNothingToChange},
		{`[]`, "args", protocol.ArgInvalidJSON},
		{`null`, "args", protocol.ArgInvalidJSON},
		{`{"tank":1,"alarmLowTempC":-5}`, "alarmLowTempC", protocol.ArgFieldNotEditable},
		{`{"tank":1,"tankHeightMm":3000}`, "tankHeightMm", protocol.ArgFieldNotEditable},
		{`{"tank":1,"probeAddress":"02"}`, "probeAddress", protocol.ArgFieldNotEditable},
		{`{"tank":1,"tankShape":1}`, "tankShape", protocol.ArgFieldNotEditable},
		{`{"tank":1,"sensorLengthMm":10}`, "sensorLengthMm", protocol.ArgFieldNotEditable},
		{`{"tank":1,"densty":0.5}`, "densty", protocol.ArgUnknownField},
		{`{"tank":1,"density":0}`, "density", protocol.ArgInvalidValue},
		{`{"tank":1,"density":2.01}`, "density", protocol.ArgInvalidValue},
		{`{"tank":1,"density":"0.5"}`, "density", protocol.ArgInvalidValue},
		{`{"tank":1,"alarmHighLevelMm":650.5}`, "alarmHighLevelMm", protocol.ArgInvalidValue},
		{`{"tank":1,"alarmHighLevelMm":-1}`, "alarmHighLevelMm", protocol.ArgInvalidValue},
		{`{"tank":1,"alarmHighTempC":101}`, "alarmHighTempC", protocol.ArgInvalidValue},
		{`{"tank":1,"tempCorrectionC":"1"}`, "tempCorrectionC", protocol.ArgInvalidValue},
		{`{"tank":1,"levelCorrectionMm":null}`, "levelCorrectionMm", protocol.ArgInvalidValue},
		{`{"tank":1,"oilTypeIndex":5,"customOilName":"盐酸"}`, "customOilName", protocol.ArgInvalidValue},
		{`{"tank":1,"density":0.5,"customOilNameEn":"HCl"}`, "customOilNameEn", protocol.ArgInvalidValue},
		{`{"tank":1,"oilTypeIndex":32,"customOilName":""}`, "customOilName", protocol.ArgInvalidValue},
		{`{"tank":1,"oilTypeIndex":40}`, "oilTypeIndex", protocol.ArgInvalidValue},
		{`{"tank":1,"capacityL":1000001}`, "capacityL", protocol.ArgInvalidValue},
		{`{"tank":1,"alarmHighLevelMm":null}`, "alarmHighLevelMm", protocol.ArgInvalidValue},
		{`{"tank":1,"alarmLowLevelMm":900,"alarmHighLevelMm":800}`, "alarmLevels", protocol.ArgLevelOrder},
		{`{"tank":1,"alarmLowLevelMm":800,"alarmHighLevelMm":800}`, "alarmLevels", protocol.ArgLevelOrder},
	}
	for _, c := range cases {
		ae := argsErr(t, m, c.args)
		found := false
		for _, f := range ae.Fields {
			found = found || (f.Field == c.field && f.Code == c.code)
		}
		if !found {
			t.Errorf("%s: got %+v, want %s/%s", c.args, ae.Fields, c.field, c.code)
		}
	}
}

func TestValidateArgs_DeviceSettingsAndOtherMethods(t *testing.T) {
	const m = protocol.MethodConfigDeviceSettings
	if err := protocol.ValidateArgs(m, json.RawMessage(`{"outStockHeightThresholdMm":20,"outStockTimeThresholdSec":60}`)); err != nil {
		t.Errorf("valid device settings: %v", err)
	}
	for _, bad := range []string{
		`{}`, `{"inStockHeightThresholdMm":0}`, `{"inStockTimeThresholdSec":86401}`,
		`{"outStockHeightThresholdMm":10001}`, `{"outStockHeightThresholdMm":1.5}`, `{"typo":1}`,
	} {
		argsErr(t, m, bad)
	}
	// Methods without a defined shape pass through untouched, whatever the args look like.
	for _, method := range []string{protocol.MethodAlarmMute, protocol.MethodConfigAlarmThreshold, protocol.MethodConfigTank} {
		if err := protocol.ValidateArgs(method, json.RawMessage(`{"anything":[1,2]}`)); err != nil {
			t.Errorf("%s: %v", method, err)
		}
	}
}

func TestSharedCmdResultFixture_SetTankSettings(t *testing.T) {
	var env struct {
		Values map[string]json.RawMessage `json:"values"`
	}
	if err := json.Unmarshal(readFixture(t, "cmd_result_set_tank_settings.json"), &env); err != nil {
		t.Fatal(err)
	}
	var res protocol.CmdResult
	if err := json.Unmarshal(env.Values["cmdResult"], &res); err != nil {
		t.Fatal(err)
	}
	var detail struct {
		Tank    int      `json:"tank"`
		Updated []string `json:"updated"`
		Ignored []string `json:"ignored"`
	}
	if err := json.Unmarshal(res.Detail, &detail); err != nil {
		t.Fatal(err)
	}
	if res.Status != protocol.CmdStatusSucceeded || detail.Tank != 1 || len(detail.Updated) != 4 || len(detail.Ignored) != 1 {
		t.Errorf("result = %+v detail = %+v", res, detail)
	}
}
