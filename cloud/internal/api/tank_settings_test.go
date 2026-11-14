package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/ingest"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
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
		OilColor           *int     `json:"oilColor"`
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

// cloud#14: the calibration curve mirror. Rows are seeded directly; the ingest side has its own test.
func TestTankCalibration_VisibilityAndShape(t *testing.T) {
	g := newGrantEnv(t)
	reported := time.Now().Add(-2 * time.Hour).UTC().Truncate(time.Millisecond)
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO tank_calibration (device_name, tank, height_mm, volume_l, reported_at)
		VALUES ($1, 2, ARRAY[10,20,30]::float8[], ARRAY[100,200,300.5]::float8[], $2)`, g.device, reported); err != nil {
		t.Fatal(err)
	}
	url := func(tank string) string { return "/api/devices/" + g.device + "/tanks/" + tank + "/calibration" }

	code, body := g.call(t, "GET", url("2"), g.admin, nil)
	var v struct {
		Tank       int       `json:"tank"`
		PointCount int       `json:"pointCount"`
		HeightMm   []float64 `json:"heightMm"`
		VolumeL    []float64 `json:"volumeL"`
		ReportedAt time.Time `json:"reportedAt"`
	}
	if err := json.Unmarshal([]byte(body), &v); code != http.StatusOK || err != nil ||
		v.Tank != 2 || v.PointCount != 3 || v.VolumeL[2] != 300.5 || v.HeightMm[0] != 10 || !v.ReportedAt.Equal(reported) {
		t.Fatalf("admin: %d %s (%v)", code, body, err)
	}

	// Reported for no other tank: a distinct 404 from the 403s.
	if code, body := g.call(t, "GET", url("1"), g.admin, nil); code != http.StatusNotFound || !strings.Contains(body, "calibration_not_reported") {
		t.Errorf("unreported tank: %d %s", code, body)
	}
	if code, _ := g.call(t, "GET", url("0"), g.admin, nil); code != http.StatusBadRequest {
		t.Errorf("tank 0: %d, want 400", code)
	}

	// An employee needs a grant on that tank; an ungranted or out-of-range tank is the same 403.
	if code, body := g.set(t, g.platform, g.empID, g.device, 1); code != http.StatusOK {
		t.Fatalf("grant: %d %s", code, body)
	}
	for _, tank := range []string{"2", "99"} {
		if code, body := g.call(t, "GET", url(tank), g.emp, nil); code != http.StatusForbidden || !strings.Contains(body, "tank_not_authorized") {
			t.Errorf("employee tank %s: %d %s, want 403 tank_not_authorized", tank, code, body)
		}
	}
	if code, body := g.set(t, g.platform, g.empID, g.device, 1, 2); code != http.StatusOK {
		t.Fatalf("grant: %d %s", code, body)
	}
	if code, _ := g.call(t, "GET", url("2"), g.emp, nil); code != http.StatusOK {
		t.Errorf("granted employee: %d, want 200", code)
	}
}

// The colour and the colour-sync capability come in through ingest like every other mirrored key, and the
// colour is served as reported (0xRRGGBB), null when the device did not report one.
func TestTankSettings_OilColorRoundTrip(t *testing.T) {
	g := newGrantEnv(t)
	ctx := context.Background()
	q := db.New(g.env.pool)
	red, sync := int32(0xFF0000), int32(1)
	reported := time.Now().Add(-time.Minute).UnixMilli()
	if err := ingest.SaveTankSettings(ctx, q, g.device, protocol.TankSettings{Tank: 1, OilColor: &red, ReportedAtMs: reported}); err != nil {
		t.Fatal(err)
	}
	if err := ingest.SaveTankSettings(ctx, q, g.device, protocol.TankSettings{Tank: 2, ReportedAtMs: reported}); err != nil {
		t.Fatal(err)
	}
	if err := ingest.SaveDeviceSettings(ctx, q, g.device, protocol.DeviceSettings{OilColorSync: &sync, ReportedAtMs: reported}); err != nil {
		t.Fatal(err)
	}
	code, body := g.call(t, "GET", "/api/devices/"+g.device+"/tank-settings", g.admin, nil)
	if code != http.StatusOK {
		t.Fatalf("%d %s", code, body)
	}
	v := decodeTankSettings(t, body)
	if len(v.Tanks) != 2 || v.Tanks[0].OilColor == nil || *v.Tanks[0].OilColor != 0xFF0000 || v.Tanks[1].OilColor != nil {
		t.Errorf("oilColor not served as reported: %s", body)
	}
	ds, err := q.GetDeviceSettings(ctx, g.device)
	if err != nil || !ds.OilColorSync.Valid || ds.OilColorSync.Int32 != 1 {
		t.Errorf("oil_color_sync = %+v, %v", ds.OilColorSync, err)
	}
}
