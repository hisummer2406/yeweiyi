package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;

/**
 * 泵/阀等高风险动作的占位实现：注册方法名，让 {@code RpcDispatcher} 能识别这些方法
 * （而不是当成 UNKNOWN_METHOD），但始终拒绝执行。
 * <p>
 * 对应需求"泵阀等高风险动作暂不开放，设备端始终是最终安全裁决者"和 issue #35 scope
 * "所有高风险动作默认功能开关关闭"。真正开放需要先完成 #34（补齐联锁 + 真机验证），
 * 不是加个配置开关就能打开——这里没有做成"关闭态可配置"，是因为目前设备侧联锁
 * 本来就不完整，打开开关也执行不安全，见 CLAUDE.md "远程控制范围与安全原则"。
 */
public final class HighRiskActionHandler implements RpcMethodHandler {

    public static final String METHOD_START_PUMP = "startPump";
    public static final String METHOD_STOP_PUMP = "stopPump";
    public static final String METHOD_OPEN_VALVE = "openValve";
    public static final String METHOD_CLOSE_VALVE = "closeValve";

    @Override
    public RpcResult handle(RpcRequestEnvelope request) {
        return RpcResult.rejected(RpcErrorCode.FEATURE_DISABLED,
                "高风险动作 " + request.getMethod() + " 暂未开放：联锁与真机验证见 issue #34");
    }
}
