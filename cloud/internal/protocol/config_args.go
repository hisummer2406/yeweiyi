package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"
)

// Configuration commands (cloud#71, phase 2 of the tank settings mirror). Field names are the same as
// the client attributes the device reports (tank_settings.go), so what is written is what is read back.
// Ranges follow the device's own 远程配置下发-字段清单; the cloud checks them before anything is sent so
// the caller gets a precise 400 instead of a device rejection, but the device remains the authority
// (it also knows the tank height and the values currently stored, which the cloud does not).
//
// **Not yet a confirmed/frozen part of V1** — see docs/protocol-v1-open-items.md §E.
const (
	MethodConfigTankSettings   = "config.setTankSettings"   // 修改单个罐的设置
	MethodConfigDeviceSettings = "config.setDeviceSettings" // 修改整机设置（出入库判定阈值）
)

// Device-level setting keys for MethodConfigDeviceSettings.
const (
	ArgInStockHeightThresholdMm  = "inStockHeightThresholdMm"
	ArgInStockTimeThresholdSec   = "inStockTimeThresholdSec"
	ArgOutStockHeightThresholdMm = "outStockHeightThresholdMm"
	ArgOutStockTimeThresholdSec  = "outStockTimeThresholdSec"
)

// Codes for FieldError.Code.
const (
	ArgInvalidJSON       = "invalid_args"          // args is not a JSON object
	ArgMissingTank       = "tank_required"         // tank missing or not an integer 1..16
	ArgUnknownField      = "unknown_field"         // not a field of this method
	ArgFieldNotEditable  = "field_not_editable"    // a known field the device does not allow changing remotely
	ArgInvalidValue      = "invalid_value"         // wrong type or out of range
	ArgNothingToChange   = "no_editable_fields"    // only `tank` (or nothing) was sent
	ArgLevelOrder        = "level_order_violation" // 低低 < 低 < 高 < 高高 broken
	capacityLMax         = 1_000_000
	maxOilTypeIndex      = 25
	deviceThresholdMaxMm = 10000
	deviceThresholdMaxS  = 86400
)

// FieldError is one problem with one args field.
type FieldError struct {
	Field   string `json:"field"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// ArgsError is the full list of problems with a command's args. It is a user-input error (HTTP 400).
type ArgsError struct {
	Fields []FieldError `json:"fields"`
}

func (e *ArgsError) Error() string {
	parts := make([]string, 0, len(e.Fields))
	for _, f := range e.Fields {
		parts = append(parts, f.Field+": "+f.Message)
	}
	return "invalid args: " + strings.Join(parts, "; ")
}

type numRule struct {
	min, max float64
	integer  bool
	exclMin  bool // min itself is not allowed (density > 0)
}

var tankSettingRules = map[string]numRule{
	AttrAlarmHighHighLevel: {0, math.MaxInt32, true, false},
	AttrAlarmHighLevel:     {0, math.MaxInt32, true, false},
	AttrAlarmLowLevel:      {0, math.MaxInt32, true, false},
	AttrAlarmLowLowLevel:   {0, math.MaxInt32, true, false},
	AttrAlarmHighWater:     {0, math.MaxInt32, true, false},
	AttrAlarmHighTempC:     {-50, 100, true, false},
	AttrLevelCorrectionMm:  {-100, 100, false, false},
	AttrWaterCorrectionMm:  {-100, 100, false, false},
	AttrTempCorrectionC:    {-10, 10, false, false},
	AttrDensity:            {0, 2, false, true},
	AttrOilTypeIndex:       {0, maxOilTypeIndex, true, false},
	AttrCapacityL:          {0, capacityLMax, true, false},
}

// tankSettingsClosed are fields the device knows but refuses to change remotely; sending one is an
// error, not a no-op, because a caller who sent it expects it to take effect.
var tankSettingsClosed = map[string]string{
	AttrAlarmLowTempC:  "低温检测在设备端从未被调用，改了不生效",
	AttrTankHeightMm:   "罐高决定罐的启用/停用，不开放远程修改",
	AttrProbeAddress:   "改错会与探针断开通讯，不开放远程修改",
	AttrTankShape:      "影响罐体图示与计算口径，安装阶段一次性设置",
	AttrSensorLengthMm: "设备本地设置页尚不能保存该值，暂不开放",
}

var deviceSettingRules = map[string]numRule{
	ArgInStockHeightThresholdMm:  {1, deviceThresholdMaxMm, true, false},
	ArgInStockTimeThresholdSec:   {1, deviceThresholdMaxS, true, false},
	ArgOutStockHeightThresholdMm: {1, deviceThresholdMaxMm, true, false},
	ArgOutStockTimeThresholdSec:  {1, deviceThresholdMaxS, true, false},
}

// ValidateArgs checks the args of the methods that have a defined shape and returns nil for every other
// method (their args are passed through untouched, as before). The error is an *ArgsError.
func ValidateArgs(method string, args json.RawMessage) error {
	switch method {
	case MethodConfigTankSettings:
		return validateConfigArgs(args, true, tankSettingRules, tankSettingsClosed)
	case MethodConfigDeviceSettings:
		return validateConfigArgs(args, false, deviceSettingRules, nil)
	}
	return nil
}

func validateConfigArgs(raw json.RawMessage, needTank bool, rules map[string]numRule, closed map[string]string) error {
	var args map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&args); err != nil || args == nil {
		return &ArgsError{Fields: []FieldError{{"args", ArgInvalidJSON, "args 必须是 JSON 对象"}}}
	}

	var errs []FieldError
	if needTank {
		if n, ok := args["tank"].(float64); !ok || n != math.Trunc(n) || n < 1 || n > MaxDeviceTankSettings {
			errs = append(errs, FieldError{"tank", ArgMissingTank, fmt.Sprintf("tank 必须是 1～%d 的整数", MaxDeviceTankSettings)})
		}
	}

	keys := make([]string, 0, len(args))
	for k := range args {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	editable := 0
	levels := map[string]int{}
	for _, k := range keys {
		if needTank && k == "tank" {
			continue
		}
		if why, isClosed := closed[k]; isClosed {
			errs = append(errs, FieldError{k, ArgFieldNotEditable, "不开放远程修改: " + why})
			continue
		}
		rule, known := rules[k]
		if !known {
			errs = append(errs, FieldError{k, ArgUnknownField, "不认识的字段"})
			continue
		}
		if msg := checkNumber(args[k], rule); msg != "" {
			errs = append(errs, FieldError{k, ArgInvalidValue, k + " " + msg})
			continue
		}
		editable++
		switch k {
		case AttrAlarmLowLowLevel, AttrAlarmLowLevel, AttrAlarmHighLevel, AttrAlarmHighHighLevel:
			levels[k] = int(args[k].(float64))
		}
	}
	if len(errs) == 0 && editable == 0 {
		errs = append(errs, FieldError{"args", ArgNothingToChange, "没有可修改的字段"})
	}
	if len(errs) == 0 {
		if msg := levelOrderError(levels); msg != "" {
			errs = append(errs, FieldError{"alarmLevels", ArgLevelOrder, msg})
		}
	}
	if len(errs) > 0 {
		return &ArgsError{Fields: errs}
	}
	return nil
}

func checkNumber(v any, r numRule) string {
	f, ok := v.(float64)
	if !ok || math.IsNaN(f) || math.IsInf(f, 0) {
		if r.integer {
			return "必须是整数"
		}
		return "必须是数字"
	}
	if r.integer && f != math.Trunc(f) {
		return "必须是整数"
	}
	if f < r.min || f > r.max || (r.exclMin && f <= r.min) {
		if r.exclMin {
			return fmt.Sprintf("必须大于 %v 且不超过 %v", r.min, r.max)
		}
		return fmt.Sprintf("超出范围 %v～%v", r.min, r.max)
	}
	return ""
}

// levelOrderError checks only the thresholds present in this command (the device additionally merges
// them with its stored values, which the cloud cannot see): non-zero ones must satisfy
// 低低 < 低 < 高 < 高高; 0 means "not enabled" and takes no part.
func levelOrderError(v map[string]int) string {
	order := []struct{ key, name string }{
		{AttrAlarmLowLowLevel, "低低"}, {AttrAlarmLowLevel, "低"},
		{AttrAlarmHighLevel, "高"}, {AttrAlarmHighHighLevel, "高高"},
	}
	prev, prevName := 0, ""
	for _, o := range order {
		val, present := v[o.key]
		if !present || val == 0 {
			continue
		}
		if prevName != "" && val <= prev {
			return fmt.Sprintf("液位阈值须满足 %s(%d) < %s(%d)", prevName, prev, o.name, val)
		}
		prev, prevName = val, o.name
	}
	return ""
}
