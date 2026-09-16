package com.sansen.yeweiyi.logic.thingsboard.rpc;

/**
 * 把一次 ACK 发布到 {@code v1/devices/me/rpc/response/{mqttRequestId}}。
 * <p>
 * 接口本身不关心"发布成功"和"设备执行成功"的区别——那正是这一层要解决的问题：
 * {@link RpcDispatcher} 只在真正跑完 handler、算出终态之后才调用这个接口，MQTT publish
 * 本身失败与否由实现类处理（重试/丢弃），不会被误当成"设备执行成功"。
 * <p>
 * 跟 {@code yeweiyi-cloud} 侧 {@code internal/protocol.Ack} 对齐（issue #39）：这个 topic
 * 上的 {@code status} 只应该是 {@code received}/{@code rejected} 两种（首次应答），或者
 * 重复投递时原样回放的历史终态（{@code duplicate=true}）——真正的执行结果
 * （executing/succeeded/failed）走 {@link RpcResultPublisher} 那条独立的 {@code cmdResult}
 * telemetry 通道，不在这个 ACK topic 上发第二次。
 */
public interface RpcAckPublisher {
    /**
     * @param commandId  云端下发的幂等键，回填进 Ack 好让云端直接关联，而不是只能靠
     *                   mqttRequestId 反查
     * @param duplicate  true = 这是对一个已处理完的 commandId 的重复投递，回放的是历史终态，
     *                   不是这次新执行的结果
     * @param tsMillis   设备本地时间（epoch 毫秒）
     */
    void publishAck(String mqttRequestId, String commandId, RpcResultStatus status, RpcErrorCode errorCode,
                     String message, boolean duplicate, long tsMillis);
}
