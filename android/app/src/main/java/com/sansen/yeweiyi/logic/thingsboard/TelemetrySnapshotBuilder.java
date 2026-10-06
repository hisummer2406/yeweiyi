package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.NetOilCan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一份 {@link NetOilCan} 快照组装成 ThingsBoard telemetry 上报 payload：
 * {@code {"ts": <毫秒时间戳>, "values": {...}}}。
 * <p>
 * 纯 Java、无 Android 依赖，方便在 JVM 单测里对着 fixture 校验字段/结构，
 * 不必起 Service 或连 MQTT。
 * <p>
 * <b>扁平键，不是嵌套数组</b>：{@code values} 里是 {@code tank{N}.{field}} 形式的扁平
 * key（{@code N} 为 1-based 罐号），不是一个 {@code tanks} 数组——这是跟
 * {@code yeweiyi-cloud} 侧 {@code internal/protocol/telemetry.go}（issue #39 核对结果）
 * 对齐后的形状：ThingsBoard 的时序聚合/TTL/趋势图表控件按"一个数字类型的 key"为最小
 * 单元工作，一个 JSON 数组塞进单个 key 会被当成不透明 blob，没法用这些内置能力。
 * 单位已经并入字段名（{@code oilLevelMm}/{@code tempC} 等），不再需要单独的单位表。
 */
public final class TelemetrySnapshotBuilder {

    /**
     * 设备级 key：本次快照里出现的最大罐号（不是罐数），云端 {@code LatestSnapshot} 靠这个
     * 锚定扁平键 {@code tank1.*..tank{tankCount}.*} 的范围。启用的罐号不保证连续（中间可能有
     * 被禁用的罐），所以不能用 {@code tanks.size()}——比如只启用 1 号和 5 号罐时，
     * {@code size()==2} 会让按 1..tankCount 遍历的消费方永远读不到 tank5（PR #38 Codex 审核）。
     */
    private static final String KEY_TANK_COUNT = "tankCount";
    /** 设备级 key：开机以来的快照序号，单调递增。 */
    private static final String KEY_SNAPSHOT_SEQ = "snapshotSeq";

    private TelemetrySnapshotBuilder() {
    }

    /**
     * @param tanks        当前全部油罐的快照（{@code OilcanDataManager.getNetOilcans()}）
     * @param deviceTimeMs 设备本地时间（epoch 毫秒），不是 ThingsBoard 服务端收到消息的时间——
     *                     由调用方通过 {@link #wrapWithTimestamp} 放进外层 {@code ts}，这里不再
     *                     单独重复放一份（旧版本的 {@code deviceTime}/{@code connectionState}/
     *                     {@code protocolVersion} 都不在云端 {@code Snapshot.Values()} 的字段
     *                     范围内，本次一并去掉，不是遗漏）。
     * @param snapshotSeq  开机以来的快照序号，由调用方（{@code ThingsBoardManager}）维护自增计数器。
     */
    public static Map<String, Object> buildValues(List<NetOilCan> tanks, long deviceTimeMs, long snapshotSeq) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(KEY_SNAPSHOT_SEQ, snapshotSeq);
        values.put(KEY_TANK_COUNT, maxTankId(tanks));
        for (NetOilCan tank : tanks) {
            putTankFields(values, tank);
        }
        return values;
    }

    private static int maxTankId(List<NetOilCan> tanks) {
        int max = 0;
        for (NetOilCan tank : tanks) {
            max = Math.max(max, tank.getId());
        }
        return max;
    }

    private static void putTankFields(Map<String, Object> values, NetOilCan tank) {
        int index = tank.getId();
        values.put(tankKey(index, "oilType"), tank.getOiltype());
        values.put(tankKey(index, "oilLevelMm"), tank.getOilLevel());
        values.put(tankKey(index, "waterLevelMm"), tank.getWaterLevel());
        values.put(tankKey(index, "tempC"), tank.getTemp());
        values.put(tankKey(index, "oilVolumeL"), tank.getOilVolume());
        values.put(tankKey(index, "waterVolumeL"), tank.getWaterVolume());
        values.put(tankKey(index, "oilWeightKg"), tank.getOilWeight());
        values.put(tankKey(index, "nullVolumeL"), tank.getNullVolume());
        if (tank.getOilRatioPct() != null) {
            values.put(tankKey(index, "oilRatioPct"), tank.getOilRatioPct());
        }
        // waterRatioPct 暂不上报（2026-10-04 用户决定）：真实模式下 OilCan.waterRatio 从未被计算，恒为 0。
        // NORMAL/ALARM/FAULT，由 OilcanDataManager 从 CloudAlarmReporter 的 ACTIVE 集合填入，
        // 跟云端报警事件同一个来源。没有填（null）时不发这个 key，不发一个编造的 "NORMAL"。
        if (tank.getAlarmState() != null) {
            values.put(tankKey(index, "alarmState"), tank.getAlarmState());
        }
        values.put(tankKey(index, "probeConnected"), tank.isConnect());
    }

    /**
     * {@code tank{n}.oilRatioPct}：液位占罐高的百分比，0–100，保留一位小数。含义跟本机罐体图用的
     * {@code OilCan.oilRatio}（{@code ZhongChengCompany} 里 {@code (int)(液位/罐高*100)}，超过 100 按 100）
     * 一致，只是不截成整数；小数位跟液位一样向下截断（{@code ZhongChengCompany} 对液位也是 floor 到 0.1）。
     *
     * @return 罐高未配置（≤0）时返回 null，不上报——本机此时算出来的是除零后的无意义值。
     * 液位是 NaN/无穷大时同样返回 null：BigDecimal.valueOf 会对它们抛异常，不拦住的话一个罐的
     * 异常读数会让 getNetOilcans() 整个中断，所有罐的这一轮快照都发不出去。
     */
    public static Double heightRatioPct(double level, double maxHeight) {
        if (maxHeight <= 0 || Double.isNaN(level) || Double.isInfinite(level)) {
            return null;
        }
        // BigDecimal 而不是 Math.floor(level / maxHeight * 1000) / 10：后者会因浮点误差把 94.8 截成 94.7
        double pct = BigDecimal.valueOf(level).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(maxHeight), 1, RoundingMode.DOWN).doubleValue();
        return Math.max(0.0, Math.min(100.0, pct));
    }

    private static String tankKey(int index, String field) {
        return "tank" + index + "." + field;
    }

    /** ThingsBoard 官方 telemetry 上行 payload 外层形状：{@code {"ts":..., "values":{...}}}。 */
    public static Map<String, Object> wrapWithTimestamp(Map<String, Object> values, long tsMillis) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", tsMillis);
        payload.put("values", values);
        return payload;
    }
}
