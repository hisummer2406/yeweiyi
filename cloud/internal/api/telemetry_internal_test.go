package api

import (
	"encoding/json"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func TestTanksWithShape(t *testing.T) {
	tanks := []protocol.Tank{{Index: 1, OilType: "92#"}, {Index: 2}, {Index: 3}}
	attrs := map[string]any{
		"tank1.tankShape": "vertical",
		"tank3.tankShape": "cuboid", // unrecognised → unknown, not passed through
	}

	got := tanksWithShape(tanks, attrs)
	raw, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	var wire []map[string]any
	if err := json.Unmarshal(raw, &wire); err != nil {
		t.Fatal(err)
	}

	if wire[0]["tankShape"] != "vertical" {
		t.Errorf("tank1 tankShape = %v, want vertical", wire[0]["tankShape"])
	}
	if wire[0]["oilType"] != "92#" || wire[0]["index"] != float64(1) {
		t.Errorf("snapshot fields must stay flattened beside tankShape: %v", wire[0])
	}
	for _, i := range []int{1, 2} {
		if v, present := wire[i]["tankShape"]; present {
			t.Errorf("tank%d has tankShape=%v, want the key omitted (unknown, not horizontal)", i+1, v)
		}
	}

	// nil attrs (attribute read failed, or an old device) degrades every tank to unknown.
	for i, lt := range tanksWithShape(tanks, nil) {
		if lt.TankShape != "" {
			t.Errorf("nil attrs: tank%d shape = %q", i+1, lt.TankShape)
		}
	}
}
