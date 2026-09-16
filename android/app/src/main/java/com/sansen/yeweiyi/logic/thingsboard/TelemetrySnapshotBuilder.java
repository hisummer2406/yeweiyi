package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.NetOilCan;

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
        values.put(tankKey(index, "oilRatioPct"), tank.getOilRadio());
        values.put(tankKey(index, "waterRatioPct"), tank.getWaterRadio());
        values.put(tankKey(index, "probeConnected"), tank.isConnect());
        // 有意不发 tank{N}.alarmState：NetOilCan.setAlarmState(String) 在整个代码库里
        // 从未被真正调用过（唯一调用点在 OilcanDataManager 里是注释掉的），真实报警状态
        // 在 AlarmDetection 一套布尔标志位状态机上（不同的 bean：OilCan，int 编码 0-5），
        // 这次没有动它——见 CLAUDE.md 里报警恢复事件那条已知缺口，需要真机验证才能安全
        // 重写。宁可不发这个 key（云端 ParseSnapshot 对缺失 key 留空字符串），也不要
        // 发一个恒为 false 的 "NORMAL" 误导云端/小程序。
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

    /**
     * 供"立即上报"类事件（比如出入库 stockEvent）在包一层 {@link #wrapWithTimestamp} 之前，
     * 优先用事件自身携带的 {@code tsMs} 字段作为外层 {@code ts}，而不是发送/落盘的当下时刻——
     * ThingsBoard 按外层 {@code ts} 给时序数据定位置，同一个 eventId 核对后补发如果外层 ts
     * 用的是发送时刻，会在时序库里落成新的一个点，而不是覆盖事件本来发生那个点
     * （PR #38 Codex 第四轮审核 P2）。
     *
     * @param parsedValue  已经反序列化的事件 payload；不是 JSON 对象，或者取不到
     *                     {@code tsMs}/不是数字，都视为"没有可用的事件时间戳"。
     * @param fallbackMillis 取不到事件自身时间戳时使用的兜底值（调用方通常传"现在"）。
     */
    public static long resolveEventTimestamp(JsonElement parsedValue, long fallbackMillis) {
        if (parsedValue == null || !parsedValue.isJsonObject()) {
            return fallbackMillis;
        }
        JsonObject object = parsedValue.getAsJsonObject();
        JsonElement tsMs = object.get("tsMs");
        if (tsMs == null || !tsMs.isJsonPrimitive() || !tsMs.getAsJsonPrimitive().isNumber()) {
            return fallbackMillis;
        }
        return tsMs.getAsLong();
    }
}
