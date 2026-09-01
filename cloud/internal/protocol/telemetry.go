package protocol

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// TelemetryTopic is ThingsBoard's own device-side telemetry topic. It is not configurable and not
// namespaced per product — ThingsBoard resolves which device published from the MQTT session's
// credentials, which is why the protocol carries no deviceName field anywhere (contrast the retired Alink
// sys/{productKey}/{deviceName}/... topics, where the device identity sat in the topic string and
// was therefore forgeable by any client holding wildcard publish rights).
const TelemetryTopic = "v1/devices/me/telemetry"

// SnapshotIntervalSec is the default cadence for a full tank snapshot. The device pushes on this
// timer; the backend never polls a device for realtime data (that was the old requestOilcan
// design). Overridable per device via the reportIntervalSec shared attribute.
const SnapshotIntervalSec = 30

// Telemetry key suffixes for per-tank values. The unit is part of the key name on purpose: it makes
// "a number with an undefined unit" structurally impossible, and it is what a ThingsBoard dashboard
// shows as the series label, so the unit stays visible without a legend.
const (
	KeyOilType        = "oilType"        // string, 油品名称, free text as configured on-device
	KeyOilLevelMm     = "oilLevelMm"     // double, 油位, millimetres
	KeyWaterLevelMm   = "waterLevelMm"   // double, 水位, millimetres
	KeyTempC          = "tempC"          // double, 油温, degrees Celsius
	KeyOilVolumeL     = "oilVolumeL"     // double, 油体积, litres
	KeyWaterVolumeL   = "waterVolumeL"   // double, 水体积, litres
	KeyOilWeightKg    = "oilWeightKg"    // double, 油重量, kilograms
	KeyNullVolumeL    = "nullVolumeL"    // double, 空容积, litres
	KeyOilRatioPct    = "oilRatioPct"    // double, 油品占罐容比例, percent 0-100
	KeyWaterRatioPct  = "waterRatioPct"  // double, 水占罐容比例, percent 0-100
	KeyAlarmState     = "alarmState"     // string, one of the AlarmState constants below
	KeyProbeConnected = "probeConnected" // bool, 探针通信状态
)

// Device-level telemetry keys (not per tank).
const (
	KeySnapshotSeq = "snapshotSeq" // int64, increments by 1 per snapshot since device boot
	KeyTankCount   = "tankCount"   // int, number of tanks in this snapshot
)

// AlarmState is the per-tank rollup shown in a snapshot. It is the *current* worst state of that
// tank, not an event — alarm transitions are reported separately and immediately as an AlarmEvent
// (see events.go), because a 30-second snapshot is far too slow to be an alarm channel.
const (
	AlarmStateNormal = "NORMAL"
	AlarmStateAlarm  = "ALARM"
	AlarmStateFault  = "FAULT" // probe disconnected / hardware fault, distinct from a level alarm
)

// Tank is one tank's slice of a snapshot. Index is the device-local tank number (1-based), the same
// number the device shows on its own screen and the same one RPC args address.
//
// Fields dropped relative to the legacy NetOilCan bean, and why: oilColor (UI-only, never used by
// the cloud), probeAddress/probeType/address/fatherAllProbe (configuration, not timeseries — these
// are client attributes now, see attributes.go), object (unused placeholder), deviceName (implicit
// in the ThingsBoard session), id (replaced by Index). oilRadio/waterRadio are renamed to
// OilRatioPct/WaterRatioPct — "Radio" was a misspelling of "ratio" on the device side, and V1 is
// the point at which that gets fixed rather than carried forward forever.
type Tank struct {
	Index          int     `json:"index"`
	OilType        string  `json:"oilType"`
	OilLevelMm     float64 `json:"oilLevelMm"`
	WaterLevelMm   float64 `json:"waterLevelMm"`
	TempC          float64 `json:"tempC"`
	OilVolumeL     float64 `json:"oilVolumeL"`
	WaterVolumeL   float64 `json:"waterVolumeL"`
	OilWeightKg    float64 `json:"oilWeightKg"`
	NullVolumeL    float64 `json:"nullVolumeL"`
	OilRatioPct    float64 `json:"oilRatioPct"`
	WaterRatioPct  float64 `json:"waterRatioPct"`
	AlarmState     string  `json:"alarmState"`
	ProbeConnected bool    `json:"probeConnected"`
}

// Snapshot is one full report of every tank on a device.
type Snapshot struct {
	// TS is the device's own wall-clock time in epoch milliseconds, UTC. V1 deliberately drops the
	// legacy "yyyy-MM-dd HH:mm" string format, which had no seconds and no timezone and forced the
	// backend to guess Asia/Shanghai (see CLAUDE.md "The MQTT contract"). Epoch millis has neither
	// problem, and it is also what ThingsBoard stores natively.
	TS          int64
	SnapshotSeq int64
	Tanks       []Tank
}

// TankKey builds the flat telemetry key for one tank field, e.g. TankKey(1, KeyOilLevelMm) is
// "tank1.oilLevelMm". Flat scalar keys (rather than one nested JSON blob per snapshot) are what let
// ThingsBoard's own aggregation, TTL and trend widgets work on this data without the backend
// reshaping it first.
func TankKey(index int, field string) string {
	return "tank" + strconv.Itoa(index) + "." + field
}

var tankKeyFields = []string{
	KeyOilType, KeyOilLevelMm, KeyWaterLevelMm, KeyTempC,
	KeyOilVolumeL, KeyWaterVolumeL, KeyOilWeightKg, KeyNullVolumeL,
	KeyOilRatioPct, KeyWaterRatioPct, KeyAlarmState, KeyProbeConnected,
}

// SnapshotKeys returns every telemetry key a snapshot of tankCount tanks produces, which is what a
// caller passes to ThingsBoard's REST/WebSocket timeseries APIs (both require an explicit key list;
// neither has a "give me everything" mode that is safe to rely on).
func SnapshotKeys(tankCount int) []string {
	keys := []string{KeySnapshotSeq, KeyTankCount}
	for i := 1; i <= tankCount; i++ {
		for _, f := range tankKeyFields {
			keys = append(keys, TankKey(i, f))
		}
	}
	return keys
}

// Values flattens a snapshot into the key/value map that goes inside a ThingsBoard telemetry
// message. Callers that want the full wire message want MarshalTelemetry instead.
func (s Snapshot) Values() map[string]any {
	v := map[string]any{
		KeySnapshotSeq: s.SnapshotSeq,
		KeyTankCount:   len(s.Tanks),
	}
	for _, t := range s.Tanks {
		v[TankKey(t.Index, KeyOilType)] = t.OilType
		v[TankKey(t.Index, KeyOilLevelMm)] = t.OilLevelMm
		v[TankKey(t.Index, KeyWaterLevelMm)] = t.WaterLevelMm
		v[TankKey(t.Index, KeyTempC)] = t.TempC
		v[TankKey(t.Index, KeyOilVolumeL)] = t.OilVolumeL
		v[TankKey(t.Index, KeyWaterVolumeL)] = t.WaterVolumeL
		v[TankKey(t.Index, KeyOilWeightKg)] = t.OilWeightKg
		v[TankKey(t.Index, KeyNullVolumeL)] = t.NullVolumeL
		v[TankKey(t.Index, KeyOilRatioPct)] = t.OilRatioPct
		v[TankKey(t.Index, KeyWaterRatioPct)] = t.WaterRatioPct
		v[TankKey(t.Index, KeyAlarmState)] = t.AlarmState
		v[TankKey(t.Index, KeyProbeConnected)] = t.ProbeConnected
	}
	return v
}

// telemetryEnvelope is ThingsBoard's own "explicit timestamp" telemetry form. The device always
// sends this form rather than the bare {"key":value} form, so that a snapshot replayed from the
// device's offline buffer lands at the time it was measured instead of the time it was uploaded.
type telemetryEnvelope struct {
	TS     int64          `json:"ts"`
	Values map[string]any `json:"values"`
}

// MarshalTelemetry produces the exact bytes a device publishes to TelemetryTopic for a snapshot.
func (s Snapshot) MarshalTelemetry() ([]byte, error) {
	return json.Marshal(telemetryEnvelope{TS: s.TS, Values: s.Values()})
}

// ParseSnapshot rebuilds a Snapshot from a flat key/value map — the shape ThingsBoard hands back
// from both the latest-timeseries REST endpoint and a WebSocket telemetry subscription, and the
// shape a device publishes. ts is supplied separately because those two APIs carry it outside the
// value map.
//
// Unknown keys are ignored rather than rejected, per the compatibility policy on Version: a device
// running a newer minor protocol must not break an older backend.
func ParseSnapshot(ts int64, values map[string]any) (Snapshot, error) {
	s := Snapshot{TS: ts}
	if raw, ok := values[KeySnapshotSeq]; ok {
		n, err := toInt64(raw)
		if err != nil {
			return Snapshot{}, fmt.Errorf("%s: %w", KeySnapshotSeq, err)
		}
		s.SnapshotSeq = n
	}

	byIndex := map[int]*Tank{}
	tank := func(i int) *Tank {
		t, ok := byIndex[i]
		if !ok {
			t = &Tank{Index: i}
			byIndex[i] = t
		}
		return t
	}

	for k, raw := range values {
		idx, field, ok := parseTankKey(k)
		if !ok {
			continue // device-level key, or a key from a newer protocol version
		}
		t := tank(idx)
		var err error
		switch field {
		case KeyOilType:
			t.OilType, err = toString(raw)
		case KeyOilLevelMm:
			t.OilLevelMm, err = toFloat(raw)
		case KeyWaterLevelMm:
			t.WaterLevelMm, err = toFloat(raw)
		case KeyTempC:
			t.TempC, err = toFloat(raw)
		case KeyOilVolumeL:
			t.OilVolumeL, err = toFloat(raw)
		case KeyWaterVolumeL:
			t.WaterVolumeL, err = toFloat(raw)
		case KeyOilWeightKg:
			t.OilWeightKg, err = toFloat(raw)
		case KeyNullVolumeL:
			t.NullVolumeL, err = toFloat(raw)
		case KeyOilRatioPct:
			t.OilRatioPct, err = toFloat(raw)
		case KeyWaterRatioPct:
			t.WaterRatioPct, err = toFloat(raw)
		case KeyAlarmState:
			t.AlarmState, err = toString(raw)
		case KeyProbeConnected:
			t.ProbeConnected, err = toBool(raw)
		default:
			continue // unrecognised per-tank field from a newer protocol version
		}
		if err != nil {
			return Snapshot{}, fmt.Errorf("%s: %w", k, err)
		}
	}

	idxs := make([]int, 0, len(byIndex))
	for i := range byIndex {
		idxs = append(idxs, i)
	}
	sort.Ints(idxs)
	for _, i := range idxs {
		s.Tanks = append(s.Tanks, *byIndex[i])
	}
	return s, nil
}

// UnmarshalTelemetry parses the bytes a device publishes to TelemetryTopic back into a Snapshot.
func UnmarshalTelemetry(b []byte) (Snapshot, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(b, &env); err != nil {
		return Snapshot{}, err
	}
	return ParseSnapshot(env.TS, env.Values)
}

// parseTankKey splits "tank12.oilLevelMm" into (12, "oilLevelMm", true). Anything that is not a
// tank-prefixed key returns ok=false.
func parseTankKey(key string) (index int, field string, ok bool) {
	if !strings.HasPrefix(key, "tank") {
		return 0, "", false
	}
	dot := strings.IndexByte(key, '.')
	if dot < 0 {
		return 0, "", false
	}
	n, err := strconv.Atoi(key[len("tank"):dot])
	if err != nil || n < 1 {
		return 0, "", false
	}
	return n, key[dot+1:], true
}
