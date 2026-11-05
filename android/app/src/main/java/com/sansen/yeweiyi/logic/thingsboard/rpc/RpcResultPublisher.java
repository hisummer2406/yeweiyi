package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.Map;

/**
 * 一条命令的*真正执行结果*，异步上报为独立的 {@code cmdResult} telemetry 事件——
 * 跟 {@link RpcAckPublisher} 走的 {@code v1/devices/me/rpc/response/...} ACK topic
 * 是两个不同的通道。
 * <p>
 * 拆开的原因（跟 {@code yeweiyi-cloud} 侧 {@code internal/protocol.CmdResult}/
 * {@code events.go} 对齐，issue #39）：RPC 应答只表示"收到/拒绝"，命令是否真的执行成功
 * 只有这条异步事件能确定——见 {@link RpcDispatcher} 类注释。
 */
public interface RpcResultPublisher {
    /**
     * @param status   SUCCEEDED/FAILED/EXECUTING 之一（不会是 RECEIVED/REJECTED——那些走 ACK）
     * @param data     handler 返回的结果数据，可能为空
     * @param tsMillis 设备本地时间（epoch 毫秒）
     */
    void publishResult(String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message,
                        Map<String, Object> data, long tsMillis);
}
