package protocol

import (
	"encoding/json"
	"fmt"
)

// KeyStockEvent carries one completed 出入库 (stock in/out) record — a delivery into a tank or a
// bulk withdrawal from it.
//
// Like alarms and command results this is an *instant event*, published the moment the record is
// closed rather than on the snapshot timer, and it rides the same offline replay queue on the
// device. It is a discrete business record, so it travels as a single JSON-valued key rather than
// as flat scalars: half a stock record is worse than none.
//
// This is the business层's most valuable uplink after telemetry. Telemetry says how much oil is in
// the tank right now; alarms say something is wrong; a stock event says *where the oil went*, which
// is what reconciliation, loss accounting and supplier disputes actually run on.
const KeyStockEvent = "stockEvent"

// Stock movement direction.
const (
	StockIn  = "IN"  // 进油/收油
	StockOut = "OUT" // 出油/发油
)

// Stock event source. Distinguishes InOilManager's automatic detection (the default — omitted from
// the wire payload, see StockEvent.Source) from BuLuFragment's manual backfill (断电/故障导致自动
// 检测漏记后人工补一条). New/unconfirmed field, zhongchengyeweiyi#42 待办2 / yeweiyi-cloud#15.
const (
	SourceAuto   = "AUTO"
	SourceManual = "MANUAL"
)

// StockEvent is one completed stock movement.
//
// Field naming follows the same rules as the telemetry snapshot: units in the name, no carried-over
// misspellings. The device side currently spells the same concept three different ways —
// `beginHeith` (DetailInoutRecord), `beginHeigth` (OilInOut) and `beginheigth` (its SQLite column) —
// and V1 is where that stops.
type StockEvent struct {
	// EventID must be unique per movement and stable across a redelivery, so the cloud can
	// deduplicate an event replayed from the device's offline queue. Same role as AlarmEvent.AlarmID.
	EventID string `json:"eventId"`
	// Tank is the 1-based tank number, matching the telemetry snapshot's tank index.
	Tank      int    `json:"tank"`
	Direction string `json:"direction"` // StockIn or StockOut
	OilType   string `json:"oilType"`

	// The movement's own window, epoch millis UTC. Distinct from TSMs, which is when the record was
	// closed: a delivery can take an hour, and the reconciliation cares about when it started.
	BeginTimeMs int64 `json:"beginTimeMs"`
	EndTimeMs   int64 `json:"endTimeMs"`

	// Readings before and after. Units in the names, consistent with the snapshot.
	BeginLevelMm      float64 `json:"beginLevelMm"`
	EndLevelMm        float64 `json:"endLevelMm"`
	BeginVolumeL      float64 `json:"beginVolumeL"`
	EndVolumeL        float64 `json:"endVolumeL"`
	BeginWaterLevelMm float64 `json:"beginWaterLevelMm"`
	EndWaterLevelMm   float64 `json:"endWaterLevelMm"`
	BeginWaterVolumeL float64 `json:"beginWaterVolumeL"`
	EndWaterVolumeL   float64 `json:"endWaterVolumeL"`
	BeginWeightKg     float64 `json:"beginWeightKg"`
	EndWeightKg       float64 `json:"endWeightKg"`
	BeginTempC        float64 `json:"beginTempC"`
	EndTempC          float64 `json:"endTempC"`

	// NetVolumeL is the tank's own change (end − begin), which for an inbound delivery understates
	// what was actually delivered whenever the pumps kept selling during it.
	NetVolumeL float64 `json:"netVolumeL"`
	// DuringDeliveryVolumeL is the amount dispensed *while* the movement was in progress. The
	// device derives it as (operator-confirmed movement volume − NetVolumeL); a depot that keeps
	// selling during a delivery cannot reconcile without it.
	DuringDeliveryVolumeL float64 `json:"duringDeliveryVolumeL,omitempty"`

	// TSMs is when the device closed the record, epoch millis UTC.
	TSMs int64 `json:"tsMs"`

	// Source is SourceManual for a BuLuFragment backfill, and absent from the wire payload for
	// InOilManager's automatic detection (the device omits it entirely rather than send SourceAuto —
	// omitempty here mirrors that, and keeps every already-confirmed automatic-detection fixture
	// byte-identical). New/unconfirmed field: see the const block above.
	Source string `json:"source,omitempty"`
}

// Two device-side fields are deliberately absent from this struct until their meaning is confirmed,
// rather than guessed at and frozen into V1 — the same call made for config.setTank's arguments:
//
//   - DetailInoutRecord.beginTcVolume / endTcVolume. The name reads as temperature-compensated
//     volume (标准体积), which would matter a great deal for reconciliation, but the code assigns it
//     from OilCan.getSurPlus() ("surplus"), and nothing in that class documents what surPlus holds.
//     A field named for one quantity and filled from another is exactly the wrong thing to freeze.
//   - DetailInoutRecord.duringDelivery is modelled above, but only because its computation is
//     visible (InOilFragment: saleVolume − (endVolume − beginVolume)). Confirm the sign convention.
//
// See docs/protocol-v1.md §12 and zhongchengyeweiyi#39.

// MarshalTelemetry produces the exact bytes a device publishes to TelemetryTopic for a stock event.
func (e StockEvent) MarshalTelemetry() ([]byte, error) {
	return json.Marshal(telemetryEnvelope{TS: e.TSMs, Values: map[string]any{KeyStockEvent: e}})
}

// ParseStockEvent reads a stock event out of a telemetry value, tolerating the same shapes as the
// other event parsers (a decoded map off MQTT, a JSON string off ThingsBoard's REST/WebSocket).
func ParseStockEvent(raw any) (StockEvent, error) {
	var e StockEvent
	if err := decodeJSONValue(raw, &e); err != nil {
		return StockEvent{}, fmt.Errorf("%s: %w", KeyStockEvent, err)
	}
	// Rejected rather than passed through: an unknown source would otherwise reach API consumers as
	// a third state they have no way to interpret, and "AUTO" is not a safe fallback — it would
	// present an unrecognised record as machine-measured.
	switch e.Source {
	case "", SourceAuto, SourceManual:
	default:
		return StockEvent{}, fmt.Errorf("%s: unknown source %q (want %q or %q)", KeyStockEvent, e.Source, SourceAuto, SourceManual)
	}
	return e, nil
}
