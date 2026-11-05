package com.sansen.yeweiyi.logic.thingsboard.rpc;

/** 一个 RPC 方法的具体实现。参数校验、执行、失败原因都在这里，调度/幂等/ACK 由 {@link RpcDispatcher} 负责。 */
public interface RpcMethodHandler {

    /**
     * 同步执行。目前所有参考实现都是快速返回的本地配置修改，不需要 EXECUTING 中间态；
     * 未来如果有真正耗时的动作（比如需要等待硬件应答），再给这个接口加异步/回调版本。
     */
    RpcResult handle(RpcRequestEnvelope request);
}
