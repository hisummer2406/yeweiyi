package protocol

import (
	"encoding/json"
	"fmt"
)

// 交班与库存记录（cloud#85，契约 docs/shift-stock-records-contract.md）。和出入库一样是设备已生成的业务事实，
// 走即时事件；设备经独立的 outbox 上报（不占报警补投队列）。缺失值一律缺键，读方当作 null，绝不当 0：
// 进行中的班没有结束数据、班内新增的罐没有开始数据，0 会被读成实测值。

const (
	KeyShiftEvent  = "shiftEvent"  // ShiftEvent，一班一条，开始与结束各报一次
	KeyStockRecord = "stockRecord" // StockRecord，一次库存保存一条，携带全部罐
)

// Shift status.
const (
	ShiftOpen   = "OPEN"   // 已接班，尚无结束数据
	ShiftClosed = "CLOSED" // 已交班
)

// Stock record trigger.
const (
	StockTriggerScheduled = "SCHEDULED" // 按「库存生成时间」周期自动保存
	StockTriggerManual    = "MANUAL"    // 库存页手动保存
)

// ShiftEvent is one shift. The same ShiftID is reported when the shift opens and again when it
// closes (and again after a manual edit on the display); the report with the largest envelope ts
// wins.
type ShiftEvent struct {
	ShiftID     string      `json:"shiftId"`
	Status      string      `json:"status"`
	BeginTimeMs int64       `json:"beginTimeMs"`         // 接班时刻
	EndTimeMs   *int64      `json:"endTimeMs,omitempty"` // 交班时刻；进行中缺键
	Tanks       []ShiftTank `json:"tanks"`
}

// ShiftTank is one tank's figures for a shift. Every reading is optional: a tank added mid-shift
// has no begin figures, one removed mid-shift has no end figures, an open shift has neither end nor
// out figures.
type ShiftTank struct {
	Tank         int      `json:"tank"`
	OilType      string   `json:"oilType,omitempty"` // 接班时的介质显示名
	OilTypeIndex *int     `json:"oilTypeIndex,omitempty"`
	BeginLevelMm *float64 `json:"beginLevelMm,omitempty"`
	BeginVolumeL *float64 `json:"beginVolumeL,omitempty"`
	EndLevelMm   *float64 `json:"endLevelMm,omitempty"`
	EndVolumeL   *float64 `json:"endVolumeL,omitempty"`
	// OutVolumeL = BeginVolumeL − EndVolumeL, the device's own figure. Not sales when oil was
	// delivered during the shift; may be negative.
	OutVolumeL *float64 `json:"outVolumeL,omitempty"`
	// OutWeightKg = OutVolumeL × the tank's density setting at shift end.
	OutWeightKg *float64 `json:"outWeightKg,omitempty"`
}

// StockRecord is one 库存 save: every tank's reading at one instant.
type StockRecord struct {
	RecordID string            `json:"recordId"`
	Trigger  string            `json:"trigger,omitempty"`
	TSMs     int64             `json:"tsMs"` // 库存生成时刻
	Tanks    []StockRecordTank `json:"tanks"`
}

// StockRecordTank is one tank in a stock record. Units are in the names.
type StockRecordTank struct {
	Tank         int      `json:"tank"`
	OilType      string   `json:"oilType,omitempty"` // 保存当时的介质显示名
	OilTypeIndex *int     `json:"oilTypeIndex,omitempty"`
	LevelMm      *float64 `json:"levelMm,omitempty"`
	VolumeL      *float64 `json:"volumeL,omitempty"`
	WaterLevelMm *float64 `json:"waterLevelMm,omitempty"`
	TempC        *float64 `json:"tempC,omitempty"`
	WeightKg     *float64 `json:"weightKg,omitempty"`
}

// MarshalTelemetry produces the bytes a device publishes for a shift report sent at envTS.
func (e ShiftEvent) MarshalTelemetry(envTS int64) ([]byte, error) {
	return marshalEnvelope(envTS, KeyShiftEvent, e)
}

// MarshalTelemetry produces the bytes a device publishes for a stock record.
func (r StockRecord) MarshalTelemetry() ([]byte, error) {
	return marshalEnvelope(r.TSMs, KeyStockRecord, r)
}

// ParseShiftEvent reads a shift report out of a telemetry value and checks what storage and the
// per-tank authorization depend on: an id, a known status, a begin time, an end time once closed, and
// distinct tank numbers in range.
func ParseShiftEvent(raw any) (ShiftEvent, error) {
	var e ShiftEvent
	if err := decodeJSONValue(raw, &e); err != nil {
		return ShiftEvent{}, fmt.Errorf("%s: %w", KeyShiftEvent, err)
	}
	switch {
	case e.ShiftID == "":
		return ShiftEvent{}, fmt.Errorf("%s: shiftId is required", KeyShiftEvent)
	case e.Status != ShiftOpen && e.Status != ShiftClosed:
		return ShiftEvent{}, fmt.Errorf("%s: status %q (want %q or %q)", KeyShiftEvent, e.Status, ShiftOpen, ShiftClosed)
	case e.BeginTimeMs <= 0:
		return ShiftEvent{}, fmt.Errorf("%s: beginTimeMs is required", KeyShiftEvent)
	case e.Status == ShiftClosed && (e.EndTimeMs == nil || *e.EndTimeMs <= 0):
		return ShiftEvent{}, fmt.Errorf("%s: a CLOSED shift needs endTimeMs", KeyShiftEvent)
	}
	tanks := make([]int, len(e.Tanks))
	for i, t := range e.Tanks {
		tanks[i] = t.Tank
	}
	if err := checkRecordTanks(tanks); err != nil {
		return ShiftEvent{}, fmt.Errorf("%s: %w", KeyShiftEvent, err)
	}
	return e, nil
}

// ParseStockRecord reads a stock record out of a telemetry value; see ParseShiftEvent.
func ParseStockRecord(raw any) (StockRecord, error) {
	var r StockRecord
	if err := decodeJSONValue(raw, &r); err != nil {
		return StockRecord{}, fmt.Errorf("%s: %w", KeyStockRecord, err)
	}
	switch {
	case r.RecordID == "":
		return StockRecord{}, fmt.Errorf("%s: recordId is required", KeyStockRecord)
	case r.TSMs <= 0:
		return StockRecord{}, fmt.Errorf("%s: tsMs is required", KeyStockRecord)
	case r.Trigger != "" && r.Trigger != StockTriggerScheduled && r.Trigger != StockTriggerManual:
		return StockRecord{}, fmt.Errorf("%s: trigger %q (want %q or %q)", KeyStockRecord, r.Trigger, StockTriggerScheduled, StockTriggerManual)
	}
	tanks := make([]int, len(r.Tanks))
	for i, t := range r.Tanks {
		tanks[i] = t.Tank
	}
	if err := checkRecordTanks(tanks); err != nil {
		return StockRecord{}, fmt.Errorf("%s: %w", KeyStockRecord, err)
	}
	return r, nil
}

// checkRecordTanks requires at least one tank, each in 1..MaxTankCount, none repeated: a repeat
// would make "this tank's row" ambiguous, and storage keys rows by tank.
func checkRecordTanks(tanks []int) error {
	if len(tanks) == 0 {
		return fmt.Errorf("tanks is empty")
	}
	seen := make(map[int]bool, len(tanks))
	for _, n := range tanks {
		if n < 1 || n > MaxTankCount {
			return fmt.Errorf("tank %d out of range 1..%d", n, MaxTankCount)
		}
		if seen[n] {
			return fmt.Errorf("tank %d repeated", n)
		}
		seen[n] = true
	}
	return nil
}

func marshalEnvelope(envTS int64, key string, v any) ([]byte, error) {
	return json.Marshal(telemetryEnvelope{TS: envTS, Values: map[string]any{key: v}})
}
