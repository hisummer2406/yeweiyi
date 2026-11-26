package protocol_test

import (
	"encoding/json"
	"reflect"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func TestOilColorPalette_MatchesSharedFixture(t *testing.T) {
	var f struct {
		Colors []int32 `json:"colors"`
	}
	if err := json.Unmarshal(readFixture(t, "oil_color_palette.json"), &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Colors) != 20 {
		t.Fatalf("fixture has %d colours, want 20", len(f.Colors))
	}
	if !reflect.DeepEqual(protocol.OilColorPalette, f.Colors) {
		t.Errorf("OilColorPalette = %v, fixture = %v", protocol.OilColorPalette, f.Colors)
	}
	if !protocol.IsPaletteColor(0xFF0000) || protocol.IsPaletteColor(0x123456) || protocol.IsPaletteColor(0) {
		t.Error("IsPaletteColor disagrees with the palette")
	}
}

func TestParseTankSettings_OilColor(t *testing.T) {
	got, skipped := protocol.ParseTankSettings(attrsFromJSON(t, `{"tank1.oilColor": 16711680, "tank2.oilColor": 1193046}`, 1))
	if len(skipped) != 0 || len(got) != 2 {
		t.Fatalf("got %+v skipped %v", got, skipped)
	}
	if got[0].OilColor == nil || *got[0].OilColor != 0xFF0000 {
		t.Errorf("tank1 oilColor = %v", got[0].OilColor)
	}
	// A colour from the device's old free picker is not in the palette but is still what the device shows.
	if got[1].OilColor == nil || *got[1].OilColor != 0x123456 {
		t.Errorf("tank2 oilColor = %v", got[1].OilColor)
	}
	for _, bad := range []string{`16777216`, `-1`, `1.5`, `"red"`} {
		_, skipped := protocol.ParseTankSettings(attrsFromJSON(t, `{"tank1.oilColor": `+bad+`}`, 1))
		if len(skipped) != 1 {
			t.Errorf("oilColor %s: skipped = %v, want it skipped", bad, skipped)
		}
	}
}

func TestValidateArgs_OilColor(t *testing.T) {
	const m = protocol.MethodConfigTankSettings
	for _, ok := range []string{`{"tank":1,"oilColor":16711680}`, `{"tank":1,"oilColor":16750848,"density":0.84}`} {
		if err := protocol.ValidateArgs(m, json.RawMessage(ok)); err != nil {
			t.Errorf("%s: %v", ok, err)
		}
	}
	for _, bad := range []string{`1193046`, `0`, `16711680.5`, `"#FF0000"`} {
		ae := argsErr(t, m, `{"tank":1,"oilColor":`+bad+`}`)
		if len(ae.Fields) != 1 || ae.Fields[0].Field != protocol.AttrOilColor || ae.Fields[0].Code != protocol.ArgInvalidValue {
			t.Errorf("oilColor %s: %+v", bad, ae.Fields)
		}
	}
}

func TestParseDeviceSettings_OilColorSync(t *testing.T) {
	s, ok, skipped := protocol.ParseDeviceSettings(attrsFromJSON(t, `{"oilColorSync": 1}`, 7))
	if !ok || len(skipped) != 0 || s.OilColorSync == nil || *s.OilColorSync != 1 {
		t.Errorf("s = %+v ok = %v skipped = %v", s, ok, skipped)
	}
}

func TestParseDeviceSettings_TankHeightLowTempSync(t *testing.T) {
	s, ok, skipped := protocol.ParseDeviceSettings(attrsFromJSON(t, `{"tankHeightLowTempSync": 1}`, 7))
	if !ok || len(skipped) != 0 || s.TankHeightLowTempSync == nil || *s.TankHeightLowTempSync != 1 {
		t.Fatalf("parsed = %+v ok=%v skipped=%v", s, ok, skipped)
	}
}
