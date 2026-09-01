package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.Map;

/**
 * RPC 处理主流程：解析 -&gt; 协议版本/有效期校验 -&gt; 幂等去重 -&gt; RECEIVED ACK -&gt; 执行 -&gt; 终态 ACK。
 * <p>
 * 云端只能拿到这里发出的 ACK 作为"设备执行结果"，{@code MqttClient.publish} 本身是否成功
 * 跟这个状态机无关——publish 失败由 {@link RpcAckPublisher} 的实现处理（比如记日志/重试），
 * 不会让 {@link RpcDispatcher} 把"没发出去"误判成某种执行结果。
 */
public final class RpcDispatcher {

    /** 供测试注入固定时钟；真机用 {@code System::currentTimeMillis}。 */
    public interface Clock {
        long nowMillis();
    }

    private final RpcMethodRegistry registry;
    private final ProcessedCommandStore commandStore;
    private final RpcAckPublisher ackPublisher;
    private final Clock clock;

    public RpcDispatcher(RpcMethodRegistry registry, ProcessedCommandStore commandStore,
                          RpcAckPublisher ackPublisher, Clock clock) {
        this.registry = registry;
        this.commandStore = commandStore;
        this.ackPublisher = ackPublisher;
        this.clock = clock;
    }

    /**
     * @param mqttRequestId 来自 topic {@code v1/devices/me/rpc/request/{requestId}}
     * @param payload       RPC 请求体原始 JSON
     */
    public void dispatch(String mqttRequestId, String payload) {
        RpcRequestEnvelope request;
        try {
            request = RpcRequestEnvelope.parse(mqttRequestId, payload);
        } catch (RpcRequestEnvelope.MalformedRpcRequestException e) {
            // 连 commandId 都拿不到，没法做幂等记录，只能就这一次 ACK 拒绝，不重试。
            ackPublisher.publishAck(mqttRequestId, RpcResultStatus.REJECTED, RpcErrorCode.MALFORMED_PARAMS,
                    e.getMessage());
            return;
        }

        long now = clock.nowMillis();

        ProcessedCommandStore.Record existing = commandStore.find(request.getCommandId());
        if (existing != null && isTerminal(existing.status)) {
            // 重复投递：回放上一次的终态，不重新执行 handler。
            ackPublisher.publishAck(mqttRequestId, existing.status, existing.errorCode, existing.message);
            return;
        }

        if (!request.isProtocolVersionSupported()) {
            terminate(request, mqttRequestId, RpcResultStatus.REJECTED, RpcErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "不支持的协议版本: " + request.getProtocolVersion(), now);
            return;
        }

        if (request.isExpired(now)) {
            terminate(request, mqttRequestId, RpcResultStatus.EXPIRED, RpcErrorCode.EXPIRED,
                    "命令已过期: expireAt=" + request.getExpireAt() + " now=" + now, now);
            return;
        }

        RpcMethodHandler handler = registry.find(request.getMethod());
        if (handler == null) {
            terminate(request, mqttRequestId, RpcResultStatus.REJECTED, RpcErrorCode.UNKNOWN_METHOD,
                    "未注册的方法: " + request.getMethod(), now);
            return;
        }

        // 正式受理：先落 RECEIVED 再执行，这样进程如果在 handler 执行期间被杀掉，
        // 重启后 recoverAfterRestart 能发现这条命令停在非终态，判 FAILED 而不是当作没处理过。
        commandStore.record(request.getCommandId(), RpcResultStatus.RECEIVED, RpcErrorCode.NONE,
                "已受理，执行中", now);
        ackPublisher.publishAck(mqttRequestId, RpcResultStatus.RECEIVED, RpcErrorCode.NONE, "已受理");

        RpcResult result;
        try {
            result = handler.handle(request);
        } catch (Exception e) {
            result = RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "handler 执行异常: " + e.getMessage());
        }

        commandStore.record(request.getCommandId(), result.getStatus(), result.getErrorCode(),
                result.getMessage(), clock.nowMillis());
        ackPublisher.publishAck(mqttRequestId, result.getStatus(), result.getErrorCode(), result.getMessage());
    }

    private void terminate(RpcRequestEnvelope request, String mqttRequestId, RpcResultStatus status,
                            RpcErrorCode errorCode, String message, long now) {
        commandStore.record(request.getCommandId(), status, errorCode, message, now);
        ackPublisher.publishAck(mqttRequestId, status, errorCode, message);
    }

    private static boolean isTerminal(RpcResultStatus status) {
        return status == RpcResultStatus.SUCCEEDED || status == RpcResultStatus.FAILED
                || status == RpcResultStatus.REJECTED || status == RpcResultStatus.EXPIRED;
    }

    public Map<String, RpcMethodHandler> registeredMethods() {
        return registry.allHandlers();
    }
}
