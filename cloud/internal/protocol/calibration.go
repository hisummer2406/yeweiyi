package protocol

import "fmt"

// TankCalibration is one tank's height→volume calibration curve, as published by the device in its
// AttrCalibrationHeightMm/AttrCalibrationVolumeL client attributes (zhongchengyeweiyi#42 待办1). The
// device computes this once, at installation time, from the tank's physical shape and dimensions, and
// persists only the resulting curve — the shape category (卧罐/立罐/长方体) and the raw dimensions
// never reach this protocol, because the device itself never persists them either (see
// zhongchengyeweiyi's TankCalibrationAttributeBuilder doc comment): they are local variables used
// once to compute the curve and discarded, so there is nothing to port.
//
// This is the *read* half of docs/miniprogram-design-gaps.md §2.4 ("罐表生成与下发") — visibility
// into a curve the device already has. The *write* half that document describes (小程序 generating a
// new curve and pushing it down to the device) is a separate, larger piece of work with no protocol
// support at all yet (that document recommends it be scoped separately), and is not what this type or
// ParseTankCalibration are for.
//
// **Not yet a confirmed/frozen part of V1** — see hisummer2406/yeweiyi-cloud#14. This type exists so
// the wire shape can be parsed and tested ahead of that decision, not as a claim that storage or
// display is settled.
type TankCalibration struct {
	// HeightMm and VolumeL are paired, index-for-index, ascending by height — the device's own
	// 1cm-step lookup table, unchanged from how it stores them locally.
	HeightMm []float64
	VolumeL  []float64
}

// ParseTankCalibration reads one tank's calibration curve out of a client-attributes map — the shape
// tbclient.Client.Attributes returns for CLIENT_SCOPE.
//
// ok=false (with a nil error) means the tank simply has no calibration attributes yet, which is an
// expected steady-state case, not a fault: the device only (re)publishes client attributes on MQTT
// reconnect (see TankCalibrationAttributeBuilder's Javadoc on the Android side), so a device that has
// been connected for a long time since its calibration table last changed, or a device running an
// older build that predates this attribute, will not have it.
func ParseTankCalibration(attrs map[string]any, tankIndex int) (TankCalibration, bool, error) {
	heightRaw, hasHeight := attrs[TankKey(tankIndex, AttrCalibrationHeightMm)]
	volumeRaw, hasVolume := attrs[TankKey(tankIndex, AttrCalibrationVolumeL)]
	if !hasHeight && !hasVolume {
		return TankCalibration{}, false, nil
	}
	if !hasHeight || !hasVolume {
		return TankCalibration{}, false, fmt.Errorf(
			"tank %d: calibrationHeightMm/calibrationVolumeL must be published together, got height=%v volume=%v",
			tankIndex, hasHeight, hasVolume)
	}

	heights, err := toFloatSlice(heightRaw)
	if err != nil {
		return TankCalibration{}, false, fmt.Errorf("%s: %w", TankKey(tankIndex, AttrCalibrationHeightMm), err)
	}
	volumes, err := toFloatSlice(volumeRaw)
	if err != nil {
		return TankCalibration{}, false, fmt.Errorf("%s: %w", TankKey(tankIndex, AttrCalibrationVolumeL), err)
	}
	if len(heights) != len(volumes) {
		return TankCalibration{}, false, fmt.Errorf(
			"tank %d: calibrationHeightMm/calibrationVolumeL length mismatch: %d vs %d",
			tankIndex, len(heights), len(volumes))
	}
	return TankCalibration{HeightMm: heights, VolumeL: volumes}, true, nil
}
