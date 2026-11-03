package protocol

import (
	"encoding/json"
	"errors"
	"fmt"
)

// ErrInvalidSource marks a stockEvent whose `source` is present but not AUTO or MANUAL. Distinct
// from a generic parse failure so a caller can tell "the JSON is fine, one tag is off" from "this is
// not a stockEvent at all" — the former should cost one record, not a whole query window.
var ErrInvalidSource = errors.New("invalid stockEvent source")

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

	// DeliveredWeightKg is the delivered volume (IN: NetVolumeL + DuringDeliveryVolumeL; OUT:
	// −NetVolumeL) times the tank's density setting when the record closed, computed by the device.
	// BeginWeightKg/EndWeightKg cannot stand in for it: their difference includes what was sold during
	// a delivery. Absent on manual entries (no density snapshot) and on older firmware (cloud#85).
	DeliveredWeightKg *float64 `json:"deliveredWeightKg,omitempty"`
	// OilTypeIndex is the device's oil table index (0 unset, 1–17 built in, 18–25 custom), independent
	// of the display language OilType was rendered in. Absent on manual entries and older firmware.
	OilTypeIndex *int `json:"oilTypeIndex,omitempty"`

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
	// `source` is validated on the raw payload first, for two reasons. Only an *omitted* field means
	// AUTO, and `"source":""` / `"source":null` both decode to the zero string, so presence cannot be
	// read off the struct. And a non-string value (e.g. a number) would otherwise surface as a generic
	// decode error instead of ErrInvalidSource.
	//
	// Rejected rather than passed through: an unknown source would reach API consumers as a third
	// state they have no way to interpret, and "AUTO" is not a safe fallback — it would present an
	// unrecognised record as machine-measured.
	var probe struct {
		Source json.RawMessage `json:"source"`
	}
	if err := decodeJSONValue(raw, &probe); err != nil {
		return StockEvent{}, fmt.Errorf("%s: %w", KeyStockEvent, err)
	}
	if probe.Source != nil {
		var src string
		if err := json.Unmarshal(probe.Source, &src); err != nil || (src != SourceAuto && src != SourceManual) {
			return StockEvent{}, fmt.Errorf("%s: %w: %s (want %q or %q)", KeyStockEvent, ErrInvalidSource, probe.Source, SourceAuto, SourceManual)
		}
	}
	var e StockEvent
	if err := decodeJSONValue(raw, &e); err != nil {
		return StockEvent{}, fmt.Errorf("%s: %w", KeyStockEvent, err)
	}
	return e, nil
}
