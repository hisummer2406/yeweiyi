package com.sansen.yeweiyi.logic.thingsboard.rpc;

/**
 * 把一次 ACK 发布到 {@code v1/devices/me/rpc/response/{mqttRequestId}}。
 * <p>
 * 接口本身不关心"发布成功"和"设备执行成功"的区别——那正是这一层要解决的问题：
 * {@link RpcDispatcher} 只在真正跑完 handler、算出终态之后才调用这个接口，MQTT publish
 * 本身失败与否由实现类处理（重试/丢弃），不会被误当成"设备执行成功"。
 */
public interface RpcAckPublisher {
    void publishAck(String mqttRequestId, RpcResultStatus status, RpcErrorCode errorCode, String message);
}
