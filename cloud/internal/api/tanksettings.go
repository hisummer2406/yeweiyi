package api

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/store/db"
)

// GET /api/devices/:device/tank-settings (cloud#70, phase 1) returns the mirror of what the device last
// reported about each tank's configuration. The cloud never writes these values: the device is the
// source of truth, internal/ingest copies its client attributes into tank_settings, and this endpoint
// only reads them back. It is read from the business database, not ThingsBoard, so it still answers
// while the device is offline — `reportedAt` says how old the answer is.
//
// Visibility is the same as /tanks: the device-level check first (cross-customer, unknown and
// unauthorised are the same 403), then an employee sees only the tanks granted to them.

// tankSettingsView is the wire shape. Every configuration field is nullable: null means the device did
// not report that key, which is not the same as 0 ("0 = 不启用" for the alarm thresholds).
type tankSettingsView struct {
	Tank       int       `json:"tank"`
	ReportedAt time.Time `json:"reportedAt"`

	TankHeightMm   *int32   `json:"tankHeightMm"`
	SensorLengthMm *float64 `json:"sensorLengthMm"`
	TankShape      *int32   `json:"tankShape"` // 0 卧罐 / 1 立罐
	// OilType is the name in the device's UI language, for display only; OilTypeIndex is authoritative
	// (0 未设置, 1–31 内置, 32–39 自定义；旧固件 18–25 为自定义，#11). The cloud keeps no name table.
	OilType      *string `json:"oilType"`
	OilTypeIndex *int32  `json:"oilTypeIndex"`

	AlarmHighHighLevelMm *int32 `json:"alarmHighHighLevelMm"`
	AlarmHighLevelMm     *int32 `json:"alarmHighLevelMm"`
	AlarmLowLevelMm      *int32 `json:"alarmLowLevelMm"`
	AlarmLowLowLevelMm   *int32 `json:"alarmLowLowLevelMm"`
	AlarmHighWaterMm     *int32 `json:"alarmHighWaterMm"`
	AlarmHighTempC       *int32 `json:"alarmHighTempC"`
	// AlarmLowTempC: 0 = not enabled. Firmware without tankHeightLowTempSync stores it but never checks it.
	AlarmLowTempC *int32 `json:"alarmLowTempC"`

	LevelCorrectionMm *float64 `json:"levelCorrectionMm"`
	WaterCorrectionMm *float64 `json:"waterCorrectionMm"`
	TempCorrectionC   *float64 `json:"tempCorrectionC"`
	// Density is in g/cm³ (= kg/L; the device's settings page wrongly labels it kg/m³).
	Density *float64 `json:"density"`
	// CapacityL is the tank's nominal volume in litres; it only affects the device's empty-volume math.
	CapacityL *float64 `json:"capacityL"`
	// OilColor is the tank colour as 0xRRGGBB; null when the device has none set or does not report it.
	OilColor *int32 `json:"oilColor"`
}

func i32p(v pgtype.Int4) *int32 {
	if !v.Valid {
		return nil
	}
	return &v.Int32
}

func f64p(v pgtype.Float8) *float64 {
	if !v.Valid {
		return nil
	}
	return &v.Float64
}

func strp(v pgtype.Text) *string {
	if !v.Valid {
		return nil
	}
	return &v.String
}

func newTankSettingsView(r db.TankSetting) tankSettingsView {
	return tankSettingsView{
		Tank: int(r.Tank), ReportedAt: r.ReportedAt.Time.UTC(),
		TankHeightMm: i32p(r.TankHeightMm), SensorLengthMm: f64p(r.SensorLengthMm), TankShape: i32p(r.TankShape),
		OilType: strp(r.OilType), OilTypeIndex: i32p(r.OilTypeIndex),
		AlarmHighHighLevelMm: i32p(r.AlarmHighHighLevelMm), AlarmHighLevelMm: i32p(r.AlarmHighLevelMm),
		AlarmLowLevelMm: i32p(r.AlarmLowLevelMm), AlarmLowLowLevelMm: i32p(r.AlarmLowLowLevelMm),
		AlarmHighWaterMm: i32p(r.AlarmHighWaterMm), AlarmHighTempC: i32p(r.AlarmHighTempC),
		AlarmLowTempC:     i32p(r.AlarmLowTempC),
		LevelCorrectionMm: f64p(r.LevelCorrectionMm), WaterCorrectionMm: f64p(r.WaterCorrectionMm),
		TempCorrectionC: f64p(r.TempCorrectionC), Density: f64p(r.Density), CapacityL: f64p(r.CapacityL),
		OilColor: i32p(r.OilColor),
	}
}

func listTankSettingsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		rows, err := deps.Queries.ListTankSettings(c.Request.Context(), device)
		if err != nil {
			internalError(c, err)
			return
		}
		var granted map[int]bool
		if isEmployee(c) {
			if granted, err = grantedTankSet(c, deps, device); err != nil {
				internalError(c, err)
				return
			}
		}
		tanks := make([]tankSettingsView, 0, len(rows))
		for _, r := range rows {
			if granted != nil && !granted[int(r.Tank)] {
				continue
			}
			tanks = append(tanks, newTankSettingsView(r))
		}
		c.JSON(http.StatusOK, gin.H{"deviceName": device, "tanks": tanks})
	}
}
