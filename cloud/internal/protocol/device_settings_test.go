package protocol_test

import (
	"encoding/json"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

// TestParseDeviceSettings_SharedFixture reads the shared client_attributes_device_settings.json, so a key
// rename on either side is caught here (config-command-delivery).
func TestParseDeviceSettings_SharedFixture(t *testing.T) {
	var m map[string]any
	if err := json.Unmarshal(readFixture(t, "client_attributes_device_settings.json"), &m); err != nil {
		t.Fatal(err)
	}
	delete(m, "_comment")
	var attrs []protocol.Attribute
	for k, v := range m {
		attrs = append(attrs, protocol.Attribute{Key: k, Value: v, TS: 42})
	}
	got, ok, skipped := protocol.ParseDeviceSettings(attrs)
	if !ok || len(skipped) != 0 {
		t.Fatalf("ok=%v skipped=%v", ok, skipped)
	}
	if got.InStockHeightThresholdMm == nil || *got.InStockHeightThresholdMm != 20 ||
		got.InStockTimeThresholdSec == nil || *got.InStockTimeThresholdSec != 60 ||
		got.OutStockHeightThresholdMm == nil || *got.OutStockHeightThresholdMm != 30 ||
		got.OutStockTimeThresholdSec == nil || *got.OutStockTimeThresholdSec != 0 {
		t.Errorf("parsed = %+v (0 must stay 0)", got)
	}
	if got.ReportedAtMs != 42 {
		t.Errorf("reportedAt = %d", got.ReportedAtMs)
	}
	// The same keys are what config.setDeviceSettings writes.
	for k := range m {
		args, _ := json.Marshal(map[string]any{k: 1})
		if err := protocol.ValidateArgs(protocol.MethodConfigDeviceSettings, args); err != nil {
			t.Errorf("fixture key %q is not a config.setDeviceSettings arg: %v", k, err)
		}
	}
}

func TestParseDeviceSettings_BadValuesAndAbsence(t *testing.T) {
	got, ok, skipped := protocol.ParseDeviceSettings([]protocol.Attribute{
		{Key: "inStockHeightThresholdMm", Value: "20", TS: 5},
		{Key: "outStockTimeThresholdSec", Value: 1.5, TS: 9},
		{Key: "outStockHeightThresholdMm", Value: float64(30), TS: 7},
		{Key: "tank1.tankHeightMm", Value: float64(2500), TS: 99},
	})
	if !ok || len(skipped) != 2 {
		t.Fatalf("ok=%v skipped=%v", ok, skipped)
	}
	if got.InStockHeightThresholdMm != nil || got.OutStockTimeThresholdSec != nil || got.OutStockHeightThresholdMm == nil {
		t.Errorf("parsed = %+v", got)
	}
	if got.ReportedAtMs != 9 {
		t.Errorf("reportedAt = %d, want the newest of the device-level keys only", got.ReportedAtMs)
	}
	if _, ok, _ := protocol.ParseDeviceSettings([]protocol.Attribute{{Key: "tank1.tankHeightMm", Value: float64(1)}}); ok {
		t.Error("no device-level key must read as not reported")
	}
}
