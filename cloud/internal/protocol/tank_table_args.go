package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"sort"
)

// MethodConfigTankTable (#49) asks the device to generate and store one tank's height→volume table.
// Only the shape and its dimensions travel: the device builds the table itself with the same formulas as
// its on-site 罐表设置 page (Android SetTankTableHandler → EllipseTable), so there is one algorithm and a
// small payload. The result is read back through the calibration mirror (tank{N}.calibrationHeightMm /
// calibrationVolumeL, GET /api/devices/:device/tanks/:tank/calibration).
//
// **Not yet a confirmed/frozen part of V1** — field names come from this PR and await the device owner's
// confirmation (docs/protocol-v1-open-items.md §E).
const MethodConfigTankTable = "config.setTankTable"

const (
	ArgTankShape   = "shape"
	TankShapeHoriz = "horizontal" // 卧罐：diameterMm、lengthMm、headMm（单侧封头，可 0）
	TankShapeVert  = "vertical"   // 立罐：heightMm、diameterMm
	TankShapeCube  = "cuboid"     // 长方体：heightMm、lengthMm、widthMm
	ArgDiameterMm  = "diameterMm"
	ArgLengthMm    = "lengthMm"
	ArgHeadMm      = "headMm"
	ArgHeightMm    = "heightMm"
	ArgWidthMm     = "widthMm"
	tankDimMinMm   = 100   // 现场「不能小于10厘米」
	tankDimMaxMm   = 20000 // 远程新增的保护：避免误输入生成几十万点
)

// TankTableShapeFields lists the dimension fields each shape needs, in display order.
var TankTableShapeFields = map[string][]string{
	TankShapeHoriz: {ArgDiameterMm, ArgLengthMm, ArgHeadMm},
	TankShapeVert:  {ArgHeightMm, ArgDiameterMm},
	TankShapeCube:  {ArgHeightMm, ArgLengthMm, ArgWidthMm},
}

// validateTankTableArgs mirrors the device's checks so the caller gets a 400 instead of a rejection.
// Unlike config.setTankSettings, a field that does not belong to the shape is an error: the table is
// replaced as a whole, so a misspelt dimension must not silently fall back to anything.
func validateTankTableArgs(raw json.RawMessage) error {
	var args map[string]any
	if err := json.NewDecoder(bytes.NewReader(raw)).Decode(&args); err != nil || args == nil {
		return &ArgsError{Fields: []FieldError{{"args", ArgInvalidJSON, "args 必须是 JSON 对象"}}}
	}
	var errs []FieldError
	if n, ok := args["tank"].(float64); !ok || n != math.Trunc(n) || n < 1 || n > MaxDeviceTankSettings {
		errs = append(errs, FieldError{"tank", ArgMissingTank, fmt.Sprintf("tank 必须是 1～%d 的整数", MaxDeviceTankSettings)})
	}
	shape, _ := args[ArgTankShape].(string)
	fields, known := TankTableShapeFields[shape]
	if !known {
		errs = append(errs, FieldError{ArgTankShape, ArgInvalidValue, "shape 必须是 horizontal / vertical / cuboid 之一"})
		return &ArgsError{Fields: errs}
	}
	want := map[string]bool{"tank": true, ArgTankShape: true}
	for _, f := range fields {
		want[f] = true
		v, present := args[f]
		if !present {
			errs = append(errs, FieldError{f, ArgInvalidValue, f + " 必填"})
			continue
		}
		minMm := float64(tankDimMinMm)
		if f == ArgHeadMm {
			minMm = 0
		}
		if msg := checkNumber(v, numRule{minMm, tankDimMaxMm, false, false}); msg != "" {
			errs = append(errs, FieldError{f, ArgInvalidValue, f + " " + msg})
		}
	}
	extra := make([]string, 0)
	for k := range args {
		if !want[k] {
			extra = append(extra, k)
		}
	}
	sort.Strings(extra)
	for _, k := range extra {
		errs = append(errs, FieldError{k, ArgUnknownField, "不属于 " + shape + " 的字段"})
	}
	if len(errs) > 0 {
		return &ArgsError{Fields: errs}
	}
	return nil
}
