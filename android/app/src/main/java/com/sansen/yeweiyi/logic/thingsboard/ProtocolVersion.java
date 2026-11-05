package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 跨仓库设备协议版本号，只用于 RPC envelope（{@code params.protocolVersion}）。
 * 类型和取值与 {@code yeweiyi-cloud} 侧 {@code internal/protocol.Version} 对齐——
 * 那边是 {@code int}（{@code const Version = 1}），不是字符串，见 issue #39 核对结果。
 * telemetry 快照 / alarm 事件不携带这个字段（云端 {@code Snapshot}/{@code AlarmEvent}
 * 结构体都没有 protocolVersion 字段，只有 RPC 的 {@code Envelope} 有）。
 */
public final class ProtocolVersion {

    public static final int CURRENT = 1;

    private ProtocolVersion() {
    }
}
