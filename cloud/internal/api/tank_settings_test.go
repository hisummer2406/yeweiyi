package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

// cloud#70: GET /api/devices/:device/tank-settings serves the mirror of the device-reported tank
// configuration from the business database. Rows are seeded directly; the ingest side has its own test.

type tankSettingsBody struct {
	DeviceName string `json:"deviceName"`
	Tanks      []struct {
		Tank               int      `json:"tank"`
		ReportedAt         string   `json:"reportedAt"`
		TankShape          *int     `json:"tankShape"`
		OilType            *string  `json:"oilType"`
		OilTypeIndex       *int     `json:"oilTypeIndex"`
		AlarmHighLevelMm   *int     `json:"alarmHighLevelMm"`
		AlarmLowLowLevelMm *int     `json:"alarmLowLowLevelMm"`
		Density            *float64 `json:"density"`
		TankHeightMm       *int     `json:"tankHeightMm"`
	} `json:"tanks"`
}

func (g *grantEnv) seedTankSettings(t *testing.T, tank int, reported time.Time) {
	t.Helper()
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO tank_settings (device_name, tank, tank_shape, oil_type, oil_type_index,
			alarm_high_level_mm, alarm_low_low_level_mm, density, reported_at)
		VALUES ($1, $2, 1, '92#', 3, 2200, 0, 0.56, $3)`, g.device, tank, reported); err != nil {
		t.Fatalf("seed tank_settings: %v", err)
	}
}

func decodeTankSettings(t *testing.T, body string) tankSettingsBody {
	t.Helper()
	var v tankSettingsBody
	if err := json.Unmarshal([]byte(body), &v); err != nil {
		t.Fatalf("decode %q: %v", body, err)
	}
	return v
}

func TestTankSettings_VisibilityAndShape(t *testing.T) {
	g := newGrantEnv(t)
	reported := time.Now().Add(-3 * time.Hour).UTC().Truncate(time.Millisecond)
	for _, tank := range []int{1, 2, 3} {
		g.seedTankSettings(t, tank, reported)
	}
	path := "/api/devices/" + g.device + "/tank-settings"

	// Administrators see every mirrored tank, null for what the device never reported and 0 kept as 0.
	code, body := g.call(t, "GET", path, g.admin, nil)
	if code != http.StatusOK {
		t.Fatalf("admin: %d %s", code, body)
	}
	v := decodeTankSettings(t, body)
	if len(v.Tanks) != 3 || v.DeviceName != g.device {
		t.Fatalf("admin tanks = %s", body)
	}
	t1 := v.Tanks[0]
	if t1.Tank != 1 || *t1.TankShape != 1 || *t1.OilTypeIndex != 3 || *t1.OilType != "92#" ||
		*t1.AlarmHighLevelMm != 2200 || *t1.Density != 0.56 {
		t.Errorf("tank 1 = %+v", t1)
	}
	if t1.TankHeightMm != nil {
		t.Errorf("a key the device never reported must be null, got %d", *t1.TankHeightMm)
	}
	if t1.AlarmLowLowLevelMm == nil || *t1.AlarmLowLowLevelMm != 0 {
		t.Errorf("a reported 0 (不启用) must stay 0, not null: %+v", t1.AlarmLowLowLevelMm)
	}
	if got, err := time.Parse(time.RFC3339Nano, t1.ReportedAt); err != nil || !got.Equal(reported) {
		t.Errorf("reportedAt = %q (%v), want %v", t1.ReportedAt, err, reported)
	}

	// An employee: no grant = the same account-level refusal as an unknown device; granted tanks only.
	if code, _ := g.call(t, "GET", path, g.emp, nil); code != http.StatusForbidden {
		t.Errorf("ungranted employee: %d, want 403", code)
	}
	if code, body := g.set(t, g.platform, g.empID, g.device, 2); code != http.StatusOK {
		t.Fatalf("grant: %d %s", code, body)
	}
	code, body = g.call(t, "GET", path, g.emp, nil)
	if code != http.StatusOK {
		t.Fatalf("granted employee: %d %s", code, body)
	}
	if v := decodeTankSettings(t, body); len(v.Tanks) != 1 || v.Tanks[0].Tank != 2 {
		t.Errorf("employee must see only tank 2: %s", body)
	}
}

func TestTankSettings_NeverReportedIsEmptyNotError(t *testing.T) {
	g := newGrantEnv(t)
	code, body := g.call(t, "GET", "/api/devices/"+g.device+"/tank-settings", g.admin, nil)
	if code != http.StatusOK {
		t.Fatalf("%d %s", code, body)
	}
	if v := decodeTankSettings(t, body); v.Tanks == nil || len(v.Tanks) != 0 {
		t.Errorf("want an empty array, got %s", body)
	}
}
