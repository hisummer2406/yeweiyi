package devicesim

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"time"

	"yeweiyi-cloud/internal/protocol"
)

// The device's remotely configurable settings and its two config RPC handlers (cloud#71). This is a
// stand-in for the Android implementation (zhongchengyeweiyi, branch feature/thingsboard-device-protocol:
// SetTankSettingsHandler / SetDeviceSettingsHandler, docs/远程配置下发-字段清单.md) so the cloud can be
// tested end to end before a real device is available.
//
// The validation below is written out from that field list on purpose and does NOT call
// protocol.ValidateArgs: the cloud's pre-check and the device's rules are two independent
// implementations of one document, and a test that routed one through the other could not tell them
// apart. The differences are deliberate and mirror the real device: it ignores unknown fields (listing
// them in cmdResult.detail.ignored), it knows the tank height and the stored values, and it enforces the
// level order against the merged values.

// tankConfig is one tank's settings as the device's settings page holds them.
type tankConfig struct {
	HeightMm       int
	SensorLengthMm float64
	Shape          int
	OilTypeIndex   int
	LowLow, Low    int
	High, HighHigh int
	HighWaterMm    int
	HighTempC      int
	LowTempC       int
	LevelCorrMm    float64
	WaterCorrMm    float64
	TempCorrC      float64
	Density        float64
	CapacityL      int
}

func defaultTankConfig() tankConfig {
	return tankConfig{
		HeightMm: 3000, SensorLengthMm: 3100.5, Shape: 0, OilTypeIndex: 3,
		LowLow: 100, Low: 300, High: 2600, HighHigh: 2800,
		HighWaterMm: 50, HighTempC: 60, LowTempC: 0,
		Density: 0.56, CapacityL: 30000,
	}
}

// deviceThresholds is the whole-machine in/out stock detection setting.
type deviceThresholds struct {
	InHeightMm, InTimeSec, OutHeightMm, OutTimeSec int
}

func defaultDeviceThresholds() deviceThresholds {
	return deviceThresholds{InHeightMm: 20, InTimeSec: 60, OutHeightMm: 20, OutTimeSec: 60}
}

// oilTypeNames is a stand-in for the device's UI-language name table; only the names the tests meet.
func oilTypeName(index int) string {
	switch {
	case index == 0:
		return "未设置"
	case index == 3:
		return "92#"
	case index >= 18:
		return fmt.Sprintf("自定义油品%d", index-17)
	}
	return fmt.Sprintf("油品%d", index)
}

// SetLocalControlMode flips the device's 现场手动模式. While true every remote config command is
// refused with LOCAL_MODE_LOCKED, and the client attribute localControlMode says so.
func (d *Device) SetLocalControlMode(on bool) {
	d.mu.Lock()
	d.localMode = on
	d.mu.Unlock()
}

// TankSettings returns a copy of what the device currently holds for a tank (1-based).
func (d *Device) TankSettings(tank int) (tankConfig, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if tank < 1 || tank > len(d.cfgTanks) {
		return tankConfig{}, false
	}
	return d.cfgTanks[tank-1], true
}

// DeviceThresholds returns the device's current in/out stock thresholds.
func (d *Device) DeviceThresholds() deviceThresholds {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.thresholds
}

// tankAttributes renders tank n's settings under the keys the real device reports them with.
func (c tankConfig) attributes(n int) map[string]any {
	k := func(f string) string { return protocol.TankKey(n, f) }
	return map[string]any{
		k("probeAddress"):         fmt.Sprintf("%02d", n),
		k("probeType"):            "磁致伸缩",
		k("capacityL"):            float64(c.CapacityL),
		k("tankHeightMm"):         c.HeightMm,
		k("sensorLengthMm"):       c.SensorLengthMm,
		k("tankShape"):            c.Shape,
		k("oilType"):              oilTypeName(c.OilTypeIndex),
		k("oilTypeIndex"):         c.OilTypeIndex,
		k("alarmHighHighLevelMm"): c.HighHigh,
		k("alarmHighLevelMm"):     c.High,
		k("alarmLowLevelMm"):      c.Low,
		k("alarmLowLowLevelMm"):   c.LowLow,
		k("alarmHighWaterMm"):     c.HighWaterMm,
		k("alarmHighTempC"):       c.HighTempC,
		k("alarmLowTempC"):        c.LowTempC,
		k("levelCorrectionMm"):    c.LevelCorrMm,
		k("waterCorrectionMm"):    c.WaterCorrMm,
		k("tempCorrectionC"):      c.TempCorrC,
		k("density"):              c.Density,
	}
}

func (t deviceThresholds) attributes() map[string]any {
	return map[string]any{
		protocol.ArgInStockHeightThresholdMm:  t.InHeightMm,
		protocol.ArgInStockTimeThresholdSec:   t.InTimeSec,
		protocol.ArgOutStockHeightThresholdMm: t.OutHeightMm,
		protocol.ArgOutStockTimeThresholdSec:  t.OutTimeSec,
	}
}

// isConfigMethod reports whether the built-in config handlers answer this method.
func isConfigMethod(method string) bool {
	return method == protocol.MethodConfigTankSettings || method == protocol.MethodConfigDeviceSettings
}

// configPolicy is the device's answer to config.setTankSettings / config.setDeviceSettings.
func (d *Device) configPolicy(method string, env protocol.Envelope) (protocol.Ack, *protocol.CmdResult, time.Duration) {
	now := time.Now().UnixMilli()
	ack := protocol.Ack{CommandID: env.CommandID, TSMs: now}
	reject := func(code, reason string) (protocol.Ack, *protocol.CmdResult, time.Duration) {
		ack.Status, ack.ErrorCode, ack.Reason = protocol.CmdStatusRejected, code, reason
		return ack, nil, 0
	}

	if env.ExpiresAtMs > 0 && now > env.ExpiresAtMs {
		return reject(protocol.ErrExpired, "命令已超过有效期，拒绝执行")
	}
	d.mu.Lock()
	local := d.localMode
	d.mu.Unlock()
	if local {
		return reject(protocol.ErrLocalModeLocked, "设备处于现场手动模式，拒绝远程控制")
	}

	var detail map[string]any
	var reason string
	var err error
	if method == protocol.MethodConfigTankSettings {
		detail, reason, err = d.applyTankSettings(env.Args)
	} else {
		detail, reason, err = d.applyDeviceSettings(env.Args)
	}
	if err != nil {
		return reject(protocol.ErrInvalidParams, err.Error())
	}

	// "Attributes are re-sent shortly after a write (debounced)": the cloud reads the new values back.
	time.AfterFunc(d.cfg.AttributeEchoDelay, func() {
		if err := d.PublishAttributes(); err != nil {
			d.cfg.Logf("attribute echo failed: %v", err)
		}
	})

	raw, _ := json.Marshal(detail)
	ack.Status = protocol.CmdStatusReceived
	return ack, &protocol.CmdResult{
		CommandID: env.CommandID, Status: protocol.CmdStatusSucceeded, Reason: reason, Detail: raw,
	}, 50 * time.Millisecond
}

func decodeArgs(raw json.RawMessage) (map[string]json.RawMessage, error) {
	var all map[string]json.RawMessage
	if err := json.Unmarshal(raw, &all); err != nil || all == nil {
		return nil, fmt.Errorf("args 必须是 JSON 对象")
	}
	return all, nil
}

// number accepts only a JSON number (never "650"), as the device does.
func number(raw json.RawMessage) (float64, bool) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return 0, false
	}
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	f, err := n.Float64()
	if err != nil || math.IsNaN(f) || math.IsInf(f, 0) {
		return 0, false
	}
	return f, true
}

func integer(raw json.RawMessage) (int, bool) {
	f, ok := number(raw)
	if !ok || f != math.Trunc(f) || f < math.MinInt32 || f > math.MaxInt32 {
		return 0, false
	}
	return int(f), true
}

func (d *Device) applyTankSettings(rawArgs json.RawMessage) (map[string]any, string, error) {
	args, err := decodeArgs(rawArgs)
	if err != nil {
		return nil, "", err
	}
	tank, ok := integer(args["tank"])
	if !ok || tank < 1 || tank > 16 {
		return nil, "", fmt.Errorf("tank 必须是 1～16 的整数")
	}

	d.mu.Lock()
	defer d.mu.Unlock()
	if tank > len(d.cfgTanks) {
		return nil, "", fmt.Errorf("罐 %d 不存在或未启用", tank)
	}
	cur := d.cfgTanks[tank-1]
	next := cur // validate everything against a copy; commit only if all of it passes

	closed := map[string]bool{"alarmLowTempC": true, "tankHeightMm": true, "probeAddress": true, "tankShape": true, "sensorLengthMm": true}
	fields := make([]string, 0, len(args))
	for f := range args {
		if f != "tank" {
			fields = append(fields, f)
		}
	}
	sort.Strings(fields)

	var updated, ignored []string
	levelsTouched := false
	intIn := func(f string, lo, hi int) (int, error) {
		v, ok := integer(args[f])
		if !ok {
			return 0, fmt.Errorf("%s 必须是整数", f)
		}
		if v < lo || v > hi {
			return 0, fmt.Errorf("%s 超出范围 %d～%d: %d", f, lo, hi, v)
		}
		return v, nil
	}
	numIn := func(f string, lo, hi float64) (float64, error) {
		v, ok := number(args[f])
		if !ok {
			return 0, fmt.Errorf("%s 必须是数字", f)
		}
		if v < lo || v > hi {
			return 0, fmt.Errorf("%s 超出范围 %v～%v: %v", f, lo, hi, v)
		}
		return v, nil
	}
	for _, f := range fields {
		var err error
		switch f {
		case "alarmLowLowLevelMm":
			next.LowLow, err = intIn(f, 0, cur.HeightMm)
			levelsTouched = true
		case "alarmLowLevelMm":
			next.Low, err = intIn(f, 0, cur.HeightMm)
			levelsTouched = true
		case "alarmHighLevelMm":
			next.High, err = intIn(f, 0, cur.HeightMm)
			levelsTouched = true
		case "alarmHighHighLevelMm":
			next.HighHigh, err = intIn(f, 0, cur.HeightMm)
			levelsTouched = true
		case "alarmHighWaterMm":
			next.HighWaterMm, err = intIn(f, 0, cur.HeightMm)
		case "alarmHighTempC":
			next.HighTempC, err = intIn(f, -50, 100)
		case "levelCorrectionMm":
			next.LevelCorrMm, err = numIn(f, -100, 100)
		case "waterCorrectionMm":
			next.WaterCorrMm, err = numIn(f, -100, 100)
		case "tempCorrectionC":
			next.TempCorrC, err = numIn(f, -10, 10)
		case "density":
			next.Density, err = numIn(f, 0, 2)
			if err == nil && next.Density <= 0 {
				err = fmt.Errorf("density 必须大于 0")
			}
		case "oilTypeIndex":
			next.OilTypeIndex, err = intIn(f, 0, 39)
		case "capacityL":
			next.CapacityL, err = intIn(f, 0, 1_000_000)
		default:
			if closed[f] {
				return nil, "", fmt.Errorf("不开放远程修改的字段: %s", f)
			}
			ignored = append(ignored, f)
			continue
		}
		if err != nil {
			return nil, "", err
		}
		updated = append(updated, f)
	}
	if len(updated) == 0 {
		msg := "没有可修改的字段"
		if len(ignored) > 0 {
			msg += fmt.Sprintf("，已忽略: %v", ignored)
		}
		return nil, "", fmt.Errorf("%s", msg)
	}
	if levelsTouched {
		if msg := levelOrder([]int{next.LowLow, next.Low, next.High, next.HighHigh}); msg != "" {
			return nil, "", fmt.Errorf("%s", msg)
		}
	}

	d.cfgTanks[tank-1] = next
	detail := map[string]any{"tank": tank, "updated": updated}
	if len(ignored) > 0 {
		detail["ignored"] = ignored
	}
	return detail, "罐设置已更新", nil
}

// levelOrder takes [低低, 低, 高, 高高]; 0 = not enabled and takes no part, the rest must increase.
func levelOrder(v []int) string {
	names := []string{"低低", "低", "高", "高高"}
	prev, prevName := 0, ""
	for i, x := range v {
		if x == 0 {
			continue
		}
		if prevName != "" && x <= prev {
			return fmt.Sprintf("液位阈值须满足 %s(%d) < %s(%d)", prevName, prev, names[i], x)
		}
		prev, prevName = x, names[i]
	}
	return ""
}

func (d *Device) applyDeviceSettings(rawArgs json.RawMessage) (map[string]any, string, error) {
	args, err := decodeArgs(rawArgs)
	if err != nil {
		return nil, "", err
	}
	ranges := map[string][2]int{
		protocol.ArgInStockHeightThresholdMm:  {1, 10_000},
		protocol.ArgInStockTimeThresholdSec:   {1, 86_400},
		protocol.ArgOutStockHeightThresholdMm: {1, 10_000},
		protocol.ArgOutStockTimeThresholdSec:  {1, 86_400},
	}
	fields := make([]string, 0, len(args))
	for f := range args {
		fields = append(fields, f)
	}
	sort.Strings(fields)

	d.mu.Lock()
	defer d.mu.Unlock()
	next := d.thresholds
	var updated, ignored []string
	for _, f := range fields {
		r, known := ranges[f]
		if !known {
			ignored = append(ignored, f)
			continue
		}
		v, ok := integer(args[f])
		if !ok {
			return nil, "", fmt.Errorf("%s 必须是整数", f)
		}
		if v < r[0] || v > r[1] {
			return nil, "", fmt.Errorf("%s 超出范围 %d～%d: %d", f, r[0], r[1], v)
		}
		switch f {
		case protocol.ArgInStockHeightThresholdMm:
			next.InHeightMm = v
		case protocol.ArgInStockTimeThresholdSec:
			next.InTimeSec = v
		case protocol.ArgOutStockHeightThresholdMm:
			next.OutHeightMm = v
		case protocol.ArgOutStockTimeThresholdSec:
			next.OutTimeSec = v
		}
		updated = append(updated, f)
	}
	if len(updated) == 0 {
		msg := "没有可修改的字段"
		if len(ignored) > 0 {
			msg += fmt.Sprintf("，已忽略: %v", ignored)
		}
		return nil, "", fmt.Errorf("%s", msg)
	}
	d.thresholds = next
	detail := map[string]any{"updated": updated}
	if len(ignored) > 0 {
		detail["ignored"] = ignored
	}
	return detail, "整机设置已更新", nil
}
