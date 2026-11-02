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
 * <p>
 * {@code alarm.mute}/{@code alarm.clear}/{@code device.reboot}（issue #39 对齐后云端也有
 * 这三个方法名）同样挂在这个 handler 上，跟泵/阀一视同仁地拒绝——它们虽然不是物理执行器
 * 动作，但要真正实现需要碰 {@code SoundLightManager}/{@code AlarmManager} 那套现有的布尔
 * 标志位报警状态机，而那套状态机目前的行为需要真机验证才能安全改动（同一个理由挡住了报警
 * 恢复事件源，见 {@code ThingsBoardManager} 类注释）。这次只保证它们不再被当成
 * {@code UNKNOWN_METHOD}，不代表功能已经打开——{@code yeweiyi-cloud} 的 {@code cmd/smoke}
 * 装机验收脚本（issue #40）仍然会在这台设备上失败，直到真正实现。
 */
public final class HighRiskActionHandler implements RpcMethodHandler {

    // 云端 internal/protocol/rpc.go 的方法名（MethodPumpStart 等）——2026-09-19 复核发现这四个
    // 泵/阀常量此前一直是改造前的旧写法（startPump 等），issue #39 对齐时只顾上改了
    // alarm.mute/alarm.clear/device.reboot/config.setAlarmThresholds 四个,漏了这四个。因为这七个
    // 方法眼下都恒 FEATURE_DISABLED,名字错了也测不出来——真正会暴露这个问题的时刻是 #34 联锁补齐、
    // 这些方法被真正打开之后:云端按自己的方法名发 RPC,设备端会因为方法名对不上返回
    // UNKNOWN_METHOD 而不是 FEATURE_DISABLED,错误语义也是错的。趁现在改动量还小(纯改常量值,
    // 不涉及任何需要跨仓库确认的语义)先修掉,不要等联锁开发那天才发现。
    public static final String METHOD_START_PUMP = "pump.start";
    public static final String METHOD_STOP_PUMP = "pump.stop";
    public static final String METHOD_OPEN_VALVE = "valve.open";
    public static final String METHOD_CLOSE_VALVE = "valve.close";
    public static final String METHOD_ALARM_MUTE = "alarm.mute";
    public static final String METHOD_ALARM_CLEAR = "alarm.clear";
    public static final String METHOD_DEVICE_REBOOT = "device.reboot";

    @Override
    public RpcResult handle(RpcRequestEnvelope request) {
        return RpcResult.rejected(RpcErrorCode.FEATURE_DISABLED,
                "高风险动作 " + request.getMethod() + " 暂未开放：联锁与真机验证见 issue #34");
    }
}
