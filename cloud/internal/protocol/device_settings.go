package protocol

import "math"

// Device-level settings the device mirrors as top-level client attributes (device side
// DeviceSettingsAttributeBuilder, 基础设置页): the 出入库 decision thresholds. The keys are the same
// names config.setDeviceSettings writes (ArgInStock*/ArgOutStock*).
//
// **Not yet a confirmed/frozen part of V1.** Taken from the device's code, consumed optionally and
// additively (CLAUDE.md exception for device-proposed fields); see docs/protocol-v1-open-items.md.
// The device reports 0 for "not configured" and the mirror keeps that 0 as-is.

// DeviceSettings is the mirrored device-level configuration. A nil field means the device did not
// report the key, or reported a value that is not an integer.
type DeviceSettings struct {
	InStockHeightThresholdMm  *int32
	InStockTimeThresholdSec   *int32
	OutStockHeightThresholdMm *int32
	OutStockTimeThresholdSec  *int32
	// CustomMediaSync is 1 on firmware that accepts medium names with config.setTankSettings (#27); nil on older firmware.
	CustomMediaSync *int32
	// ReportedAtMs is the newest last-update time among the keys that were present.
	ReportedAtMs int64
}

// ParseDeviceSettings picks the device-level setting keys out of a client attribute read. ok is false
// when none of the keys is present (an older firmware, or a device that never reported). skipped lists
// keys that were present but unusable; they do not stop the others from being mirrored.
func ParseDeviceSettings(attrs []Attribute) (s DeviceSettings, ok bool, skipped []string) {
	for _, a := range attrs {
		var dst **int32
		switch a.Key {
		case ArgInStockHeightThresholdMm:
			dst = &s.InStockHeightThresholdMm
		case ArgInStockTimeThresholdSec:
			dst = &s.InStockTimeThresholdSec
		case ArgOutStockHeightThresholdMm:
			dst = &s.OutStockHeightThresholdMm
		case ArgOutStockTimeThresholdSec:
			dst = &s.OutStockTimeThresholdSec
		case AttrCustomMediaSync:
			dst = &s.CustomMediaSync
		default:
			continue
		}
		ok = true
		if a.TS > s.ReportedAtMs {
			s.ReportedAtMs = a.TS
		}
		f, isNum := finiteNumber(a.Value)
		if !isNum || f != math.Trunc(f) || f < math.MinInt32 || f > math.MaxInt32 {
			skipped = append(skipped, a.Key)
			continue
		}
		v := int32(f)
		*dst = &v
	}
	return s, ok, skipped
}
