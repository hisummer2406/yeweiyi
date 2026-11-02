package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RPC 的两种上行报文的字段组装：ACK（{@code v1/devices/me/rpc/response/{id}}）和异步执行结果
 * （telemetry 的 {@code cmdResult} key）。字段形状对齐云端 {@code internal/protocol.Ack}/
 * {@code CmdResult}，由 {@code ProtocolV1ContractTest} 对照共享 fixture 固定。
 * <p>
 * 从 {@code ThingsBoardManager} 抽出来是为了让这两个报文能在 JVM 上做契约测试——
 * {@code ThingsBoardManager} 依赖 Android {@code Context}，没法直接单测。
 */
public final class RpcWirePayloads {

    private RpcWirePayloads() {
    }

    /**
     * {@code status} 用小写 wire 值；{@code errorCode}/{@code reason} 没有内容时省略，
     * 而不是发 {@code "NONE"}/空串；{@code duplicate} 只在为 true 时带上——跟云端
     * {@code omitempty} 行为一致。
     */
    public static Map<String, Object> ack(String commandId, RpcResultStatus status, RpcErrorCode errorCode,
                                          String reason, boolean duplicate, long tsMillis) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("commandId", commandId);
        ack.put("status", status.wireValue());
        if (errorCode != RpcErrorCode.NONE) {
            ack.put("errorCode", errorCode.name());
        }
        if (reason != null && !reason.isEmpty()) {
            ack.put("reason", reason);
        }
        if (duplicate) {
            ack.put("duplicate", true);
        }
        ack.put("tsMs", tsMillis);
        return ack;
    }

    /**
     * 只返回 {@code cmdResult} 对象本身，外层 {@code {"ts":..,"values":{"cmdResult":..}}}
     * 由调用方用 {@code TelemetrySnapshotBuilder.wrapWithTimestamp} 包。
     */
    public static Map<String, Object> cmdResult(String commandId, RpcResultStatus status, RpcErrorCode errorCode,
                                                String reason, Map<String, Object> detail, long tsMillis) {
        Map<String, Object> cmdResult = new LinkedHashMap<>();
        cmdResult.put("commandId", commandId);
        cmdResult.put("status", status.wireValue());
        if (errorCode != RpcErrorCode.NONE) {
            cmdResult.put("errorCode", errorCode.name());
        }
        if (reason != null && !reason.isEmpty()) {
            cmdResult.put("reason", reason);
        }
        if (detail != null && !detail.isEmpty()) {
            cmdResult.put("detail", detail);
        }
        cmdResult.put("tsMs", tsMillis);
        return cmdResult;
    }
}
