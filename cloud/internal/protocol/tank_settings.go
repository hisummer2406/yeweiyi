package protocol

import (
	"math"
	"strconv"
	"strings"
)

// Per-tank configuration the device mirrors to the cloud as client attributes (cloud#70, device side
// TankSettingsAttributeBuilder). The device is the source of truth: the cloud stores what it reports and
// never writes these values in phase 1.
//
// **Not yet a confirmed/frozen part of V1.** Key names follow the device's 远程配置下发-字段清单; the
// device owner still has to confirm them (docs/protocol-v1-open-items.md). They are consumed
// optionally and additively, like AttrCalibration*.
const (
	AttrTankHeightMm       = "tankHeightMm"         // int, mm, 罐高
	AttrSensorLengthMm     = "sensorLengthMm"       // number, mm, 传感器长度
	AttrTankShape          = "tankShape"            // int, 0 卧罐 / 1 立罐
	AttrOilType            = "oilType"              // string, 油品名称；随设备界面语言变化，仅供展示
	AttrOilTypeIndex       = "oilTypeIndex"         // int, 0 未设置 / 1–17 内置 / 18–25 自定义；读写以它为准
	AttrAlarmHighHighLevel = "alarmHighHighLevelMm" // int, mm, 0 = 不启用
	AttrAlarmHighLevel     = "alarmHighLevelMm"     // int, mm, 0 = 不启用
	AttrAlarmLowLevel      = "alarmLowLevelMm"      // int, mm, 0 = 不启用
	AttrAlarmLowLowLevel   = "alarmLowLowLevelMm"   // int, mm, 0 = 不启用
	AttrAlarmHighWater     = "alarmHighWaterMm"     // int, mm, 0 = 不启用；设备端没有低水位报警
	AttrAlarmHighTempC     = "alarmHighTempC"       // int, ℃, 0 = 不启用
	AttrAlarmLowTempC      = "alarmLowTempC"        // int, ℃；设备端低温检测从未被调用，改了不生效
	AttrLevelCorrectionMm  = "levelCorrectionMm"    // number, mm
	AttrWaterCorrectionMm  = "waterCorrectionMm"    // number, mm
	AttrTempCorrectionC    = "tempCorrectionC"      // number, ℃
	AttrDensity            = "density"              // number, **单位未对齐**（设置页标 kg/m³，实际存 0.56 这类值），原样透传
)

// MaxDeviceTankSettings is the device's own tank ceiling (tank numbers and probe IDs 1–16). Attributes
// for a higher tank number are ignored rather than stored.
const MaxDeviceTankSettings = 16

// Attribute is one client attribute as read from ThingsBoard: value keeps its JSON type, TS is the
// attribute's last update time in epoch milliseconds.
type Attribute struct {
	Key   string
	Value any
	TS    int64
}

// TankSettings is one tank's mirrored configuration. A nil field means the device did not report that
// key (or reported a value of the wrong type); nothing is defaulted, so "0 = 不启用" is only ever a
// value the device actually sent.
type TankSettings struct {
	Tank int

	TankHeightMm   *int32
	SensorLengthMm *float64
	TankShape      *int32
	OilType        *string
	OilTypeIndex   *int32

	AlarmHighHighLevelMm *int32
	AlarmHighLevelMm     *int32
	AlarmLowLevelMm      *int32
	AlarmLowLowLevelMm   *int32
	AlarmHighWaterMm     *int32
	AlarmHighTempC       *int32
	AlarmLowTempC        *int32

	LevelCorrectionMm *float64
	WaterCorrectionMm *float64
	TempCorrectionC   *float64
	Density           *float64
	CapacityL         *float64 // 油罐公称容积，L；同时是 config.setTankSettings 可改的字段

	// ReportedAtMs is the newest lastUpdateTs among this tank's settings keys.
	ReportedAtMs int64
}

// ParseTankSettings groups client attributes by tank. Tanks with no recognised settings key are
// omitted. Keys of unknown shape, tank numbers outside 1..MaxDeviceTankSettings, and values of the wrong
// type are skipped; the skipped keys are returned so the caller can log them (a mistyped value must not
// poison the other fields of the same tank).
func ParseTankSettings(attrs []Attribute) (tanks []TankSettings, skipped []string) {
	byTank := map[int]*TankSettings{}
	for _, a := range attrs {
		n, field, ok := splitTankKey(a.Key)
		if !ok || !isSettingsField(field) {
			continue
		}
		if n < 1 || n > MaxDeviceTankSettings {
			skipped = append(skipped, a.Key)
			continue
		}
		ts := byTank[n]
		if ts == nil {
			ts = &TankSettings{Tank: n}
			byTank[n] = ts
		}
		if !ts.set(field, a.Value) {
			skipped = append(skipped, a.Key)
			continue
		}
		if a.TS > ts.ReportedAtMs {
			ts.ReportedAtMs = a.TS
		}
	}
	for n := 1; n <= MaxDeviceTankSettings; n++ {
		if ts := byTank[n]; ts != nil {
			tanks = append(tanks, *ts)
		}
	}
	return tanks, skipped
}

// splitTankKey splits "tank3.alarmHighLevelMm" into (3, "alarmHighLevelMm").
func splitTankKey(key string) (int, string, bool) {
	rest, ok := strings.CutPrefix(key, "tank")
	if !ok {
		return 0, "", false
	}
	num, field, ok := strings.Cut(rest, ".")
	if !ok {
		return 0, "", false
	}
	n, err := strconv.Atoi(num)
	if err != nil {
		return 0, "", false
	}
	return n, field, true
}

func isSettingsField(f string) bool {
	switch f {
	case AttrTankHeightMm, AttrSensorLengthMm, AttrTankShape, AttrOilType, AttrOilTypeIndex,
		AttrAlarmHighHighLevel, AttrAlarmHighLevel, AttrAlarmLowLevel, AttrAlarmLowLowLevel,
		AttrAlarmHighWater, AttrAlarmHighTempC, AttrAlarmLowTempC,
		AttrLevelCorrectionMm, AttrWaterCorrectionMm, AttrTempCorrectionC, AttrDensity, AttrCapacityL:
		return true
	}
	return false
}

// set stores v under field; false means v had the wrong type for it.
func (t *TankSettings) set(field string, v any) bool {
	switch field {
	case AttrOilType:
		s, ok := v.(string)
		if ok {
			t.OilType = &s
		}
		return ok
	case AttrSensorLengthMm, AttrLevelCorrectionMm, AttrWaterCorrectionMm, AttrTempCorrectionC, AttrDensity, AttrCapacityL:
		f, ok := finiteNumber(v)
		if !ok {
			return false
		}
		switch field {
		case AttrSensorLengthMm:
			t.SensorLengthMm = &f
		case AttrLevelCorrectionMm:
			t.LevelCorrectionMm = &f
		case AttrWaterCorrectionMm:
			t.WaterCorrectionMm = &f
		case AttrTempCorrectionC:
			t.TempCorrectionC = &f
		case AttrDensity:
			t.Density = &f
		case AttrCapacityL:
			t.CapacityL = &f
		}
		return true
	}
	// Remaining fields are integers. A fractional value is a type error, not something to round.
	f, ok := finiteNumber(v)
	if !ok || f != math.Trunc(f) || f < math.MinInt32 || f > math.MaxInt32 {
		return false
	}
	i := int32(f)
	switch field {
	case AttrTankHeightMm:
		t.TankHeightMm = &i
	case AttrTankShape:
		t.TankShape = &i
	case AttrOilTypeIndex:
		t.OilTypeIndex = &i
	case AttrAlarmHighHighLevel:
		t.AlarmHighHighLevelMm = &i
	case AttrAlarmHighLevel:
		t.AlarmHighLevelMm = &i
	case AttrAlarmLowLevel:
		t.AlarmLowLevelMm = &i
	case AttrAlarmLowLowLevel:
		t.AlarmLowLowLevelMm = &i
	case AttrAlarmHighWater:
		t.AlarmHighWaterMm = &i
	case AttrAlarmHighTempC:
		t.AlarmHighTempC = &i
	case AttrAlarmLowTempC:
		t.AlarmLowTempC = &i
	}
	return true
}

// finiteNumber accepts the numeric shapes a decoded JSON attribute can take.
func finiteNumber(v any) (float64, bool) {
	var f float64
	switch n := v.(type) {
	case float64:
		f = n
	case int:
		f = float64(n)
	case int64:
		f = float64(n)
	default:
		return 0, false
	}
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return 0, false
	}
	return f, true
}
