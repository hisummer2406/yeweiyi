package rollup

import "testing"

func TestSplitTankKeyRejectsOutOfRangeTank(t *testing.T) {
	if n, f, ok := splitTankKey("tank2.oilLevelMm"); !ok || n != 2 || f != "oilLevelMm" {
		t.Fatalf("tank2.oilLevelMm = (%d, %q, %v)", n, f, ok)
	}
	// 4294967297 would wrap to tank 1 as int32.
	for _, key := range []string{"tank0.x", "tank65.x", "tank4294967297.x"} {
		if _, _, ok := splitTankKey(key); ok {
			t.Errorf("splitTankKey(%q) accepted an out-of-range tank", key)
		}
	}
}
