package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.Map;

/**
 * RPC 处理主流程：解析 -&gt; 协议版本/有效期校验 -&gt; 幂等去重 -&gt; RECEIVED ACK -&gt; 执行 -&gt; 终态 CmdResult。
 * <p>
 * 跟 {@code yeweiyi-cloud} 侧协议对齐后（issue #39）分成两条通道：{@link RpcAckPublisher}
 * 只用来发 {@code received}/{@code rejected}（首次应答，或者重复投递时原样回放的历史终态），
 * {@link RpcResultPublisher} 才是命令真正执行结果（executing/succeeded/failed）的出口，走独立
 * 的 {@code cmdResult} telemetry，不在 ACK topic 上发第二次——云端侧 {@code Ack.Status} 的契约
 * 就是"只表示收到/拒绝"，真正结果只有异步事件能确定。
 * <p>
 * 云端只能拿到这里发出的 ACK/CmdResult 作为"设备执行结果"，{@code MqttClient.publish} 本身是否
 * 成功跟这个状态机无关——publish 失败由两个 publisher 的实现处理（比如记日志/重试/补投队列），
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
    private final RpcResultPublisher resultPublisher;
    private final Clock clock;

    public RpcDispatcher(RpcMethodRegistry registry, ProcessedCommandStore commandStore,
                          RpcAckPublisher ackPublisher, RpcResultPublisher resultPublisher, Clock clock) {
        this.registry = registry;
        this.commandStore = commandStore;
        this.ackPublisher = ackPublisher;
        this.resultPublisher = resultPublisher;
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
            // 连 commandId 都拿不到，没法做幂等记录，只能就这一次 ACK 拒绝，不重试；
            // commandId 传 null——云端这时候也没有可关联的 commandId。
            long now = clock.nowMillis();
            ackPublisher.publishAck(mqttRequestId, null, RpcResultStatus.REJECTED, RpcErrorCode.MALFORMED_PARAMS,
                    e.getMessage(), false, now);
            return;
        }

        long now = clock.nowMillis();

        ProcessedCommandStore.Record existing = commandStore.find(request.getCommandId());
        if (existing != null && isTerminal(existing.status)) {
            // 重复投递：回放上一次的终态，不重新执行 handler。这是 Ack.Status 契约里唯一
            // 允许携带非 received/rejected 值的例外——duplicate=true 标出来，云端不会误当成
            // 一次新的执行结果。
            ackPublisher.publishAck(mqttRequestId, request.getCommandId(), existing.status, existing.errorCode,
                    existing.message, true, now);
            return;
        }

        if (!request.isProtocolVersionSupported()) {
            terminateBeforeExecution(request, mqttRequestId, RpcResultStatus.REJECTED,
                    RpcErrorCode.PROTOCOL_VERSION_UNSUPPORTED, "不支持的协议版本: " + request.getProtocolVersion(), now);
            return;
        }

        if (request.isExpired(now)) {
            terminateBeforeExecution(request, mqttRequestId, RpcResultStatus.EXPIRED, RpcErrorCode.EXPIRED,
                    "命令已过期: expireAt=" + request.getExpireAt() + " now=" + now, now);
            return;
        }

        RpcMethodHandler handler = registry.find(request.getMethod());
        if (handler == null) {
            terminateBeforeExecution(request, mqttRequestId, RpcResultStatus.REJECTED, RpcErrorCode.UNKNOWN_METHOD,
                    "未注册的方法: " + request.getMethod(), now);
            return;
        }

        // 正式受理：先落 RECEIVED 再执行，这样进程如果在 handler 执行期间被杀掉，
        // 重启后 recoverAfterRestart 能发现这条命令停在非终态，判 FAILED 而不是当作没处理过。
        //
        // 这条记录**没写进盘**就不能往下走：它是"重启后不会重复执行"这个保证的唯一载体，
        // 只在内存里的话，设备在 handler 执行期间掉电，重启后同一个 commandId 会被当成新命令
        // 再执行一遍。云端为了拿到确定结果本来就会重投，重投必然撞上这个窗口。宁可这次不执行、
        // 明确回一个 FAILED（云端可以换 commandId 重下），也不能执行一次说不清做没做过的动作。
        if (!commandStore.record(request.getCommandId(), RpcResultStatus.RECEIVED, RpcErrorCode.NONE,
                "已受理，执行中", now)) {
            // 受理阶段的失败还没真正执行 handler，属于"这次没做"，走 ACK 拒绝（REJECTED 语义上
            // 更贴切，但内部记录仍然按 FAILED 落盘/去重表，跟原来的行为保持一致）。
            terminateBeforeExecution(request, mqttRequestId, RpcResultStatus.FAILED, RpcErrorCode.INTERNAL_ERROR,
                    "去重记录写盘失败，为避免重启后重复执行，本次命令未执行", now);
            return;
        }
        ackPublisher.publishAck(mqttRequestId, request.getCommandId(), RpcResultStatus.RECEIVED, RpcErrorCode.NONE,
                "已受理", false, now);

        RpcResult result;
        try {
            result = handler.handle(request);
        } catch (Exception e) {
            result = RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "handler 执行异常: " + e.getMessage());
        }

        long finishedAt = clock.nowMillis();
        // 终态这一条写盘失败只能认了：动作已经做完，不存在"回退成没执行"这个选项。
        // 代价是重启后这条命令看起来停在 RECEIVED，被 recoverAfterRestart 改判 FAILED——
        // 对云端来说是"执行结果不明"，比谎报成功安全，也比重新执行一遍安全。
        commandStore.record(request.getCommandId(), result.getStatus(), result.getErrorCode(),
                result.getMessage(), finishedAt);
        commandStore.evictExpired(finishedAt);
        resultPublisher.publishResult(request.getCommandId(), result.getStatus(), result.getErrorCode(),
                result.getMessage(), result.getData(), finishedAt);
    }

    /**
     * 还没执行 handler 就已经确定的终态（协议版本/过期/未知方法/受理阶段写盘失败）。
     * 这些统统只在 ACK topic 上以 {@code rejected} 的面目出现——跟云端 {@code Ack.Status}
     * "只表示 received/rejected" 的契约对齐；具体原因看 errorCode，不看 ACK 的 status 名字。
     * 内部去重表仍然按传入的 {@code internalStatus} 记录真实语义（REJECTED/EXPIRED/FAILED），
     * 只有发到 ACK topic 上的那个字段被收口成 rejected。
     */
    private void terminateBeforeExecution(RpcRequestEnvelope request, String mqttRequestId,
                                           RpcResultStatus internalStatus, RpcErrorCode errorCode, String message,
                                           long now) {
        commandStore.record(request.getCommandId(), internalStatus, errorCode, message, now);
        commandStore.evictExpired(now);
        ackPublisher.publishAck(mqttRequestId, request.getCommandId(), RpcResultStatus.REJECTED, errorCode, message,
                false, now);
    }

    private static boolean isTerminal(RpcResultStatus status) {
        return status == RpcResultStatus.SUCCEEDED || status == RpcResultStatus.FAILED
                || status == RpcResultStatus.REJECTED || status == RpcResultStatus.EXPIRED;
    }

    public Map<String, RpcMethodHandler> registeredMethods() {
        return registry.allHandlers();
    }
}
