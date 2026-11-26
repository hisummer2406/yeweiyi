package protocol

import (
	"encoding/json"
	"fmt"
)

// Instant events go to the same TelemetryTopic as the 30-second snapshot, but are published the
// moment they happen rather than on the snapshot timer — issue #1's "即时事件" requirement. They are
// carried as a single telemetry key whose value is a JSON object (ThingsBoard stores JSON-typed
// telemetry natively) rather than as flat scalar keys, because unlike a snapshot these are discrete
// records that must not be split across keys: a reader that saw half an alarm would be worse than
// one that saw none.
const (
	KeyAlarm     = "alarm"     // AlarmEvent, 报警产生/恢复
	KeyCmdResult = "cmdResult" // CmdResult, 控制命令的最终执行结果
)

// Alarm types. This is the closed set for V1; adding a member is a compatible change, and a
// receiver that does not recognise a type MUST still store and display it rather than drop it.
const (
	AlarmHighHighLevel     = "HIGH_HIGH_LEVEL"    // 高高液位
	AlarmHighLevel         = "HIGH_LEVEL"         // 高液位
	AlarmLowLevel          = "LOW_LEVEL"          // 低液位
	AlarmLowLowLevel       = "LOW_LOW_LEVEL"      // 低低液位
	AlarmHighWaterLevel    = "HIGH_WATER_LEVEL"   // 高水位
	AlarmHighTemperature   = "HIGH_TEMPERATURE"   // 高油温
	AlarmLowTemperature    = "LOW_TEMPERATURE"    // 低油温（#65）
	AlarmProbeDisconnected = "PROBE_DISCONNECTED" // 探针断线
	AlarmLeakSuspected     = "LEAK_SUSPECTED"     // 疑似泄漏
	AlarmOverfillRisk      = "OVERFILL_RISK"      // 溢罐风险
)

// Alarm severities. These are ThingsBoard's own severity enum spelled exactly as ThingsBoard
// spells it, so a rule chain can map the field straight through to a ThingsBoard alarm without a
// translation table.
const (
	SeverityCritical      = "CRITICAL"
	SeverityMajor         = "MAJOR"
	SeverityMinor         = "MINOR"
	SeverityWarning       = "WARNING"
	SeverityIndeterminate = "INDETERMINATE"
)

// Alarm lifecycle. V1 has exactly two transitions the device reports; acknowledgement is a
// cloud-side action on the operator's behalf and is never reported by the device.
const (
	AlarmStatusActive  = "ACTIVE"
	AlarmStatusCleared = "CLEARED"
)

// AlarmEvent is one alarm transition. AlarmID is minted by the device and is stable across the
// ACTIVE and CLEARED reports of the same alarm occurrence, which is what lets the cloud pair them
// up — matching on (tank, type) alone would mis-pair two occurrences that overlap after a
// reconnect backfill.
type AlarmEvent struct {
	AlarmID  string `json:"alarmId"`
	Tank     int    `json:"tank"` // 1-based tank index; 0 for a device-level alarm
	Type     string `json:"type"`
	Severity string `json:"severity"`
	Status   string `json:"status"`
	Text     string `json:"text"` // human-readable Chinese text as shown on the device
	// OilType/OilTypeIndex are the tank's oil when the alarm was raised; a CLEARED report carries the
	// ACTIVE one's values. Absent for device-level alarms, deleted tanks and older firmware (cloud#85).
	OilType      string  `json:"oilType,omitempty"`
	OilTypeIndex *int    `json:"oilTypeIndex,omitempty"`
	Value        float64 `json:"value,omitempty"` // the reading that tripped it, in that alarm's own unit
	TSMs         int64   `json:"tsMs"`            // when the device detected it, epoch millis UTC
}

// MarshalTelemetry produces the exact bytes a device publishes to TelemetryTopic for an alarm.
func (a AlarmEvent) MarshalTelemetry() ([]byte, error) {
	return json.Marshal(telemetryEnvelope{TS: a.TSMs, Values: map[string]any{KeyAlarm: a}})
}

// CmdResult is the *final* outcome of a control command, reported asynchronously after the
// synchronous RPC ACK. This split is the whole point of the command state machine in issue #1: the
// RPC response only ever means "the device received and accepted the request" (CmdStatusReceived)
// or "refused it outright" (CmdStatusRejected). Whether the pump actually started is only known
// once this arrives.
type CmdResult struct {
	CommandID string          `json:"commandId"`
	Status    string          `json:"status"` // CmdStatusExecuting/Succeeded/Failed; Rejected from legacy firmware (#42)
	ErrorCode string          `json:"errorCode,omitempty"`
	Reason    string          `json:"reason,omitempty"`
	Detail    json.RawMessage `json:"detail,omitempty"` // method-specific, e.g. the resulting config
	TSMs      int64           `json:"tsMs"`
}

// MarshalTelemetry produces the exact bytes a device publishes to TelemetryTopic for a result.
func (r CmdResult) MarshalTelemetry() ([]byte, error) {
	return json.Marshal(telemetryEnvelope{TS: r.TSMs, Values: map[string]any{KeyCmdResult: r}})
}

// ParseAlarmEvent reads an alarm out of a telemetry value. raw is whatever came back for KeyAlarm —
// a map when decoded straight from the device's MQTT payload, a JSON string when read back through
// ThingsBoard's REST or WebSocket timeseries APIs (which stringify JSON-typed values).
func ParseAlarmEvent(raw any) (AlarmEvent, error) {
	var a AlarmEvent
	if err := decodeJSONValue(raw, &a); err != nil {
		return AlarmEvent{}, fmt.Errorf("%s: %w", KeyAlarm, err)
	}
	return a, nil
}

// ParseCmdResult reads a command result out of a telemetry value. Same raw-shape caveat as
// ParseAlarmEvent.
func ParseCmdResult(raw any) (CmdResult, error) {
	var r CmdResult
	if err := decodeJSONValue(raw, &r); err != nil {
		return CmdResult{}, fmt.Errorf("%s: %w", KeyCmdResult, err)
	}
	return r, nil
}

func decodeJSONValue(raw any, dst any) error {
	switch v := raw.(type) {
	case string:
		return json.Unmarshal([]byte(v), dst)
	case []byte:
		return json.Unmarshal(v, dst)
	case json.RawMessage:
		return json.Unmarshal(v, dst)
	case nil:
		return fmt.Errorf("value is null")
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return err
		}
		return json.Unmarshal(b, dst)
	}
}
