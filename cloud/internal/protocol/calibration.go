package protocol

import (
	"fmt"
	"math"
)

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
	// A key present with a null value is "not published": ThingsBoard reports a deleted or never-set
	// attribute that way, and treating it as an empty curve would be indistinguishable from a
	// device that published a zero-point table.
	heightRaw, hasHeight := attrs[TankKey(tankIndex, AttrCalibrationHeightMm)]
	volumeRaw, hasVolume := attrs[TankKey(tankIndex, AttrCalibrationVolumeL)]
	hasHeight = hasHeight && heightRaw != nil
	hasVolume = hasVolume && volumeRaw != nil
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
	if err := validateCalibrationCurve(heights, volumes); err != nil {
		return TankCalibration{}, false, fmt.Errorf("tank %d: %w", tankIndex, err)
	}
	return TankCalibration{HeightMm: heights, VolumeL: volumes}, true, nil
}

// validateCalibrationCurve rejects a curve a lookup could not interpolate over: every value must be
// finite and non-negative, heights strictly ascending (the device builds a 1cm-step table, so a
// repeat or reversal is corruption), and volume non-decreasing (a taller liquid column never holds
// less oil). An empty pair is rejected too — a published-but-empty table is a device bug.
func validateCalibrationCurve(heights, volumes []float64) error {
	if len(heights) == 0 {
		return fmt.Errorf("calibration curve is empty")
	}
	for i := range heights {
		for _, v := range []float64{heights[i], volumes[i]} {
			if math.IsNaN(v) || math.IsInf(v, 0) || v < 0 {
				return fmt.Errorf("calibration point %d has invalid value %v", i, v)
			}
		}
		if i == 0 {
			continue
		}
		if heights[i] <= heights[i-1] {
			return fmt.Errorf("calibrationHeightMm not strictly ascending at index %d: %v after %v", i, heights[i], heights[i-1])
		}
		if volumes[i] < volumes[i-1] {
			return fmt.Errorf("calibrationVolumeL decreases at index %d: %v after %v", i, volumes[i], volumes[i-1])
		}
	}
	return nil
}
