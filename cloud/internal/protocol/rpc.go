package protocol

import (
	"encoding/json"
	"fmt"
)

// ThingsBoard's own device-side RPC topics. The device subscribes to the request filter and answers
// on the response topic with the same requestId ThingsBoard put in the request topic.
const (
	RPCRequestTopicFilter = "v1/devices/me/rpc/request/+"
	RPCResponseTopicPfx   = "v1/devices/me/rpc/response/"
)

// Control methods. The name is what goes in ThingsBoard's own `method` field, so it is also what
// shows up in ThingsBoard's RPC audit log — hence the readable dotted form rather than an opaque
// code.
const (
	MethodPumpStart            = "pump.start"                // 启泵
	MethodPumpStop             = "pump.stop"                 // 停泵
	MethodValveOpen            = "valve.open"                // 开阀
	MethodValveClose           = "valve.close"               // 关阀
	MethodAlarmMute            = "alarm.mute"                // 消音
	MethodAlarmClear           = "alarm.clear"               // 清除报警
	MethodDeviceReboot         = "device.reboot"             // 重启设备
	MethodConfigAlarmThreshold = "config.setAlarmThresholds" // 修改报警阈值
	MethodConfigTank           = "config.setTank"            // 修改油罐配置
	MethodConfigProbe          = "config.setProbe"           // 修改探针配置
	// config.setTankSettings / config.setDeviceSettings: see config_args.go.
)

// PermissionLevel is the authorization tier a method requires. The backend enforces this before a
// command is ever enqueued; the device enforces nothing about *who* asked, only whether the action
// is physically safe right now (the interlock check).
type PermissionLevel string

const (
	// PermControl covers routine operational actions an authorised customer user may take.
	PermControl PermissionLevel = "control"
	// PermAdmin covers actions that change how the device measures or alarms, or that interrupt
	// service. A customer *administrator* or platform administrator is required.
	PermAdmin PermissionLevel = "admin"
	// PermPlatform is for actions only a platform administrator may take; a customer administrator is
	// refused like any other role without the permission.
	PermPlatform PermissionLevel = "platform"
)

// MethodSpec describes one control method's contract.
type MethodSpec struct {
	Method     string
	Permission PermissionLevel
	// Physical reports whether the method causes a physical action in the field. Physical methods
	// are the ones issue #1 gates behind "设备端联锁补齐并真机验证" before production exposure —
	// a config write is recoverable, an unexpected pump start is not.
	Physical bool
	Summary  string
}

// Methods is the complete V1 method registry. A method absent from this table MUST be rejected by
// the backend before it reaches ThingsBoard, and by the device with ErrUnknownMethod if it somehow
// arrives anyway.
var Methods = map[string]MethodSpec{
	MethodPumpStart:            {MethodPumpStart, PermControl, true, "启动指定泵"},
	MethodPumpStop:             {MethodPumpStop, PermControl, true, "停止指定泵"},
	MethodValveOpen:            {MethodValveOpen, PermControl, true, "打开指定阀门"},
	MethodValveClose:           {MethodValveClose, PermControl, true, "关闭指定阀门"},
	MethodAlarmMute:            {MethodAlarmMute, PermControl, false, "现场消音，不改变报警状态"},
	MethodAlarmClear:           {MethodAlarmClear, PermControl, false, "清除已恢复的报警"},
	MethodDeviceReboot:         {MethodDeviceReboot, PermAdmin, true, "重启设备"},
	MethodConfigAlarmThreshold: {MethodConfigAlarmThreshold, PermAdmin, false, "修改报警阈值"},
	MethodConfigTank:           {MethodConfigTank, PermAdmin, false, "修改油罐配置"},
	MethodConfigProbe:          {MethodConfigProbe, PermAdmin, false, "修改探针配置"},

	MethodConfigTankSettings:   {MethodConfigTankSettings, PermAdmin, false, "修改单个罐的设置（报警阈值、修正值、密度、油品、容积）"},
	MethodConfigDeviceSettings: {MethodConfigDeviceSettings, PermPlatform, false, "修改整机设置（出入库判定的高度/时间阈值）"},
}

// Command status values — the state machine issue #1 requires. Which side owns each transition:
//
//	pending   backend  command row written, not yet handed to ThingsBoard
//	sent      backend  ThingsBoard accepted the RPC call
//	received  device   ACK on the RPC response topic
//	rejected  device   ACK on the RPC response topic, terminal
//	executing device   optional CmdResult, for actions that take a while
//	succeeded device   CmdResult, terminal
//	failed    device   CmdResult, terminal
//	expired   backend  no terminal status before expiresAtMs, terminal
const (
	CmdStatusPending   = "pending"
	CmdStatusSent      = "sent"
	CmdStatusReceived  = "received"
	CmdStatusRejected  = "rejected"
	CmdStatusExecuting = "executing"
	CmdStatusSucceeded = "succeeded"
	CmdStatusFailed    = "failed"
	CmdStatusExpired   = "expired"
)

// Rejection / failure codes. Reason carries the human-readable Chinese explanation shown to the
// operator; the code is what the backend branches on.
const (
	ErrUnknownMethod   = "UNKNOWN_METHOD"    // 方法名不在协议内
	ErrInvalidParams   = "INVALID_PARAMS"    // 参数缺失或越界
	ErrExpired         = "EXPIRED"           // 到达时已超过 expiresAtMs
	ErrInterlockBlock  = "INTERLOCK_BLOCKED" // 设备端联锁拒绝，例如低液位禁止启泵
	ErrBusy            = "BUSY"              // 另一条命令正在执行
	ErrNotSupported    = "NOT_SUPPORTED"     // 本机型无此硬件
	ErrHardwareFault   = "HARDWARE_FAULT"    // 执行中硬件故障
	ErrLocalModeLocked = "LOCAL_MODE_LOCKED" // 设备处于现场手动模式，拒绝远程控制
)

// Envelope is what the backend puts in ThingsBoard's `params` field. ThingsBoard's own request body
// is {"method": <Method>, "params": <Envelope>, "timeout": <ms>} — the envelope nests inside params
// rather than replacing the body, because ThingsBoard reads `method` itself for its audit log.
type Envelope struct {
	CommandID       string          `json:"commandId"`
	ProtocolVersion int             `json:"protocolVersion"`
	Args            json.RawMessage `json:"args,omitempty"`
	IssuedAtMs      int64           `json:"issuedAtMs"`
	// ExpiresAtMs is the short validity window issue #1 requires. The device MUST compare it
	// against its own clock on arrival and reject with ErrExpired rather than act — this is what
	// stops a command that sat in a broker queue through a long outage from firing a pump hours
	// after the operator asked for it and walked away.
	ExpiresAtMs int64  `json:"expiresAtMs"`
	Operator    string `json:"operator"` // who authorised it, for the device's own local log
	Reason      string `json:"reason"`   // the mandatory 二次确认 reason, carried through to audit
}

// Request is the full ThingsBoard RPC request body.
type Request struct {
	Method  string   `json:"method"`
	Params  Envelope `json:"params"`
	Timeout int64    `json:"timeout,omitempty"` // ThingsBoard-side wait for the ACK, milliseconds
}

// Ack is what the device publishes back on the RPC response topic, and therefore what ThingsBoard
// returns as the body of a two-way RPC call. It is *only* an acknowledgement — see CmdResult for
// the eventual outcome.
type Ack struct {
	CommandID string `json:"commandId"`
	Status    string `json:"status"` // CmdStatusReceived or CmdStatusRejected
	ErrorCode string `json:"errorCode,omitempty"`
	Reason    string `json:"reason,omitempty"`
	// Duplicate is set when the device has already seen this CommandID. The device MUST NOT repeat
	// the physical action; it replays the original outcome instead. This is the idempotency
	// guarantee issue #1 requires ("重复投递不能重复造成现场动作") and is why every command carries a
	// backend-minted CommandID rather than relying on ThingsBoard's own per-call request id, which
	// changes on every retry.
	Duplicate bool  `json:"duplicate,omitempty"`
	TSMs      int64 `json:"tsMs"`
}

// ParseAck decodes a device ACK, tolerating the same value shapes as the event parsers.
func ParseAck(raw any) (Ack, error) {
	var a Ack
	if err := decodeJSONValue(raw, &a); err != nil {
		return Ack{}, fmt.Errorf("rpc ack: %w", err)
	}
	return a, nil
}

// ValidateEnvelope checks the invariants both sides rely on, so a malformed command is caught
// before it is sent rather than by the device.
func ValidateEnvelope(method string, e Envelope) error {
	if _, ok := Methods[method]; !ok {
		return fmt.Errorf("%s: unknown method %q", ErrUnknownMethod, method)
	}
	if e.CommandID == "" {
		return fmt.Errorf("%s: commandId is required", ErrInvalidParams)
	}
	if e.ProtocolVersion != Version {
		return fmt.Errorf("%s: protocolVersion %d, this build speaks %d", ErrInvalidParams, e.ProtocolVersion, Version)
	}
	if e.ExpiresAtMs <= e.IssuedAtMs {
		return fmt.Errorf("%s: expiresAtMs must be after issuedAtMs", ErrInvalidParams)
	}
	if e.Reason == "" {
		return fmt.Errorf("%s: reason is required (二次确认)", ErrInvalidParams)
	}
	return nil
}

// IsTerminal reports whether a command status is final, i.e. no further transition is expected.
func IsTerminal(status string) bool {
	switch status {
	case CmdStatusRejected, CmdStatusSucceeded, CmdStatusFailed, CmdStatusExpired:
		return true
	}
	return false
}
