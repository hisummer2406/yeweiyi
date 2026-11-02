package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.google.gson.JsonObject;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.repository.OilTankRepository;

import java.util.Collections;

/**
 * 低风险动作的参考实现：修改某个油罐的报警阈值（{@code setAlarmThreshold}）。
 * <p>
 * 对应 issue #35 scope "配置修改等低风险动作作为参考实现"——不涉及任何执行器（泵/阀），
 * 只是改一行本地配置，用来验证 {@link com.sansen.yeweiyi.logic.thingsboard.rpc.RpcDispatcher}
 * 的完整状态机（幂等/有效期/ACK）在真实业务方法上跑得通。
 * <p>
 * args 形状：{@code {"oilCanId": 1, "hheightOilAlarm": 5000, "heightOilAlarm": 4000,
 * "lowOilAlarm": 500, "llowOilAlarm": 200}}（单位 mm，与 {@code OilCanSet} 字段语义一致）。
 * 四个阈值字段都可选，只更新 args 里出现的字段。
 * <p>
 * 方法名 {@code config.setAlarmThresholds}——跟 {@code yeweiyi-cloud} 侧
 * {@code MethodConfigAlarmThreshold} 对齐（issue #39），不是原来的 {@code setAlarmThreshold}。
 * <p>
 * 写入用 {@link OilTankRepository#updateOilCanSetSync} 而不是 fire-and-forget 的
 * {@code updateOilCanSet}：这个 handler 的返回值会被 {@code RpcDispatcher} 当成终态缓存，
 * 写盘真正失败却报 SUCCEEDED 会让云端和现场配置永久不一致。成功写盘后还要通过
 * {@code onThresholdsUpdated} 回调让调用方刷新 {@code OilcanDataManager} 缓存——
 * {@code AlarmDetection} 读的是那份缓存，不是数据库，不刷新的话现场报警仍然按旧阈值走，
 * 直到下一次无关的刷新或重启才会生效。这个类保持不依赖 Android/{@code Context}，回调本身
 * 由调用方（{@code ThingsBoardManager}）注入，保持在 JVM 上可测。
 */
public final class SetAlarmThresholdHandler implements RpcMethodHandler {

    public static final String METHOD = "config.setAlarmThresholds";

    private final OilTankRepository oilTankRepository;
    private final Runnable onThresholdsUpdated;

    public SetAlarmThresholdHandler(OilTankRepository oilTankRepository, Runnable onThresholdsUpdated) {
        this.oilTankRepository = oilTankRepository;
        this.onThresholdsUpdated = onThresholdsUpdated;
    }

    @Override
    public RpcResult handle(RpcRequestEnvelope request) {
        JsonObject args = request.getArgs();
        if (!args.has("oilCanId") || !args.get("oilCanId").isJsonPrimitive()) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, "缺少 oilCanId");
        }

        int oilCanId;
        try {
            oilCanId = args.get("oilCanId").getAsInt();
        } catch (NumberFormatException e) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, "oilCanId 不是合法整数");
        }

        OilCanSet oilCanSet = oilTankRepository.findOilCanSetById(oilCanId);
        if (oilCanSet == null) {
            return RpcResult.failed(RpcErrorCode.INVALID_PARAMS, "未找到油罐配置: oilCanId=" + oilCanId);
        }

        boolean changed = false;
        int hheight = oilCanSet.getHheightOilAlarm();
        int height = oilCanSet.getHeightOilAlarm();
        int low = oilCanSet.getLowOilAlarm();
        int llow = oilCanSet.getLlowOilAlarm();
        try {
            if (args.has("hheightOilAlarm")) {
                hheight = args.get("hheightOilAlarm").getAsInt();
                changed = true;
            }
            if (args.has("heightOilAlarm")) {
                height = args.get("heightOilAlarm").getAsInt();
                changed = true;
            }
            if (args.has("lowOilAlarm")) {
                low = args.get("lowOilAlarm").getAsInt();
                changed = true;
            }
            if (args.has("llowOilAlarm")) {
                llow = args.get("llowOilAlarm").getAsInt();
                changed = true;
            }
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, "阈值字段不是合法整数: " + e.getMessage());
        }

        if (!changed) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS,
                    "args 里没有任何一个可识别的阈值字段（hheightOilAlarm/heightOilAlarm/lowOilAlarm/llowOilAlarm）");
        }

        if (hheight < 0 || height < 0 || low < 0 || llow < 0) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, "阈值不能为负数");
        }
        if (!(hheight > height && height > low && low > llow)) {
            return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS,
                    "阈值必须满足 高高(" + hheight + ") > 高(" + height + ") > 低(" + low + ") > 低低(" + llow + ")");
        }

        oilCanSet.setHheightOilAlarm(hheight);
        oilCanSet.setHeightOilAlarm(height);
        oilCanSet.setLowOilAlarm(low);
        oilCanSet.setLlowOilAlarm(llow);

        try {
            oilTankRepository.updateOilCanSetSync(oilCanSet);
        } catch (Exception e) {
            return RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "写入油罐配置失败: " + e.getMessage());
        }

        onThresholdsUpdated.run();

        return RpcResult.succeeded("阈值已更新", Collections.singletonMap("oilCanId", oilCanId));
    }
}
