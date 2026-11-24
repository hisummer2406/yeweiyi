package devicesim

import (
	"encoding/json"
	"strings"
	"testing"
)

// #65: the tank height and the thresholds are checked after merging the command with the stored values.
func TestApplyTankSettings_HeightAgainstMergedThresholds(t *testing.T) {
	d := &Device{cfgTanks: []tankConfig{defaultTankConfig()}} // 罐高 3000，高高 2800，高 2600
	apply := func(args string) error {
		_, _, err := d.applyTankSettings(json.RawMessage(args))
		return err
	}
	if err := apply(`{"tank":1,"tankHeightMm":2700}`); err == nil || !strings.Contains(err.Error(), "高高液位(2800)") {
		t.Fatalf("lowering below 高高 = %v", err)
	}
	if got, _ := d.TankSettings(1); got.HeightMm != 3000 {
		t.Fatalf("refused change was written: %d", got.HeightMm)
	}
	if err := apply(`{"tank":1,"tankHeightMm":2700,"alarmHighHighLevelMm":2650}`); err != nil {
		t.Fatalf("fixing the conflict in the same command: %v", err)
	}
	for _, bad := range []string{`{"tank":1,"tankHeightMm":0}`, `{"tank":1,"tankHeightMm":2600.5}`,
		`{"tank":1,"alarmLowTempC":60}`, `{"tank":1,"alarmLowTempC":-51}`} {
		if apply(bad) == nil {
			t.Errorf("%s accepted", bad)
		}
	}
	if err := apply(`{"tank":1,"alarmLowTempC":-40}`); err != nil {
		t.Fatal(err)
	}
	if got, _ := d.TankSettings(1); got.HeightMm != 2700 || got.HighHigh != 2650 || got.LowTempC != -40 {
		t.Errorf("stored = %+v", got)
	}
}
