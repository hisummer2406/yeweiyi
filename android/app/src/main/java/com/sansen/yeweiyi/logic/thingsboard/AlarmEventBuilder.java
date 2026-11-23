package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sansen.yeweiyi.bean.OilMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报警产生/恢复事件的立即上报 payload：不进 30 秒快照队列，走独立 telemetry key
 * （{@code alarm}），一产生/一恢复就单独发一条，不等下一次 30 秒周期。
 * <p>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接校验字段。类型码沿用 {@link OilMessage} 的常量。
 * <p>
 * 字段形状对齐 {@code yeweiyi-cloud} 侧 {@code internal/protocol.AlarmEvent}（issue #39）：
 * telemetry key 从 {@code alarmEvent} 改成 {@code alarm}（云端按这个固定 key 名找报警，
 * 名字不对整条事件就等于对云端不存在）；{@code eventId}→{@code alarmId}，
 * {@code state:"raised"/"recovered"}→{@code status:"ACTIVE"/"CLEARED"}，新增
 * {@code tank}/{@code severity}/{@code text}，{@code deviceTime}→{@code tsMs}，去掉
 * {@code protocolVersion}（云端 {@code AlarmEvent} 结构没有这个字段，只有 RPC 的
 * {@code Envelope} 才带协议版本）。
 */
public final class AlarmEventBuilder {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLEARED = "CLEARED";

    // 云端 events.go 的封闭类型集合，跟 OilMessage 的类型码是两套完全不同的编号——
    // 这里是显式映射表，不是数值转换，因为不是所有 OilMessage 类型都有对应的云端类型。
    private static final String TYPE_HIGH_HIGH_LEVEL = "HIGH_HIGH_LEVEL";
    private static final String TYPE_HIGH_LEVEL = "HIGH_LEVEL";
    private static final String TYPE_LOW_LEVEL = "LOW_LEVEL";
    private static final String TYPE_LOW_LOW_LEVEL = "LOW_LOW_LEVEL";
    private static final String TYPE_HIGH_WATER_LEVEL = "HIGH_WATER_LEVEL";
    private static final String TYPE_HIGH_TEMPERATURE = "HIGH_TEMPERATURE";
    private static final String TYPE_LOW_TEMPERATURE = "LOW_TEMPERATURE";
    private static final String TYPE_PROBE_DISCONNECTED = "PROBE_DISCONNECTED";
    // TYPE_LEAK_SUSPECTED / TYPE_OVERFILL_RISK 也在云端封闭集合里，但目前没有任何
    // OilMessage 类型可以映到它们——设备侧还没有对应的业务信号源，故意不在下面的表里出现。

    private static final String SEVERITY_CRITICAL = "CRITICAL";
    private static final String SEVERITY_MAJOR = "MAJOR";

    private static final class Mapping {
        final String type;
        final String severity;

        Mapping(String type, String severity) {
            this.type = type;
            this.severity = severity;
        }
    }

    /**
     * {@code OilMessage} 类型码 → 云端封闭类型 + 严重度。只覆盖这 8 个——其余类型
     * （出入库、网络/继电器故障、探头勘探等）在云端协议里没有对应的家，见
     * {@link #build}。
     * <p>
     * <b>严重度是这次改动自己的判断，不是跨仓库确认过的结果</b>——issue #39 核对时
     * 双方只对齐了枚举取值本身（CRITICAL/MAJOR/MINOR/WARNING/INDETERMINATE 这几个
     * 词），没有对齐"哪种报警该配哪个严重度"。这里按"双阈值报警最严重、单阈值报警
     * 次之、探头断线次之"的直觉排的，之后需要跟云端/产品对一遍。
     */
    private static final Map<Integer, Mapping> TYPE_MAPPING = buildTypeMapping();

    private static Map<Integer, Mapping> buildTypeMapping() {
        Map<Integer, Mapping> mapping = new LinkedHashMap<>();
        mapping.put(OilMessage.HHEIGHT_OIL_ALARM, new Mapping(TYPE_HIGH_HIGH_LEVEL, SEVERITY_CRITICAL));
        mapping.put(OilMessage.LLOW_OIL_ALARM, new Mapping(TYPE_LOW_LOW_LEVEL, SEVERITY_CRITICAL));
        mapping.put(OilMessage.HEIGHT_OIL_ALARM, new Mapping(TYPE_HIGH_LEVEL, SEVERITY_MAJOR));
        mapping.put(OilMessage.LOW_OIL_ALARM, new Mapping(TYPE_LOW_LEVEL, SEVERITY_MAJOR));
        mapping.put(OilMessage.HEIGHT_WATER, new Mapping(TYPE_HIGH_WATER_LEVEL, SEVERITY_MAJOR));
        mapping.put(OilMessage.HEIGHT_TEMPER, new Mapping(TYPE_HIGH_TEMPERATURE, SEVERITY_MAJOR));
        mapping.put(OilMessage.LOW_TEMPER, new Mapping(TYPE_LOW_TEMPERATURE, SEVERITY_MAJOR));
        mapping.put(OilMessage.PRO_ERR_CONN, new Mapping(TYPE_PROBE_DISCONNECTED, SEVERITY_MAJOR));
        return mapping;
    }

    private AlarmEventBuilder() {
    }

    /**
     * {@code OilMessage} 类型码能否上报到云端。出入库、网络/继电器故障、探头勘探等
     * 在云端封闭类型集合里没有对应的家，返回 false，调用方据此跳过，不能猜一个近似类型。
     */
    public static boolean isReportable(int oilMessageType) {
        return TYPE_MAPPING.containsKey(oilMessageType);
    }

    /**
     * 协议 §4.1：alarmId 区分"每一次"报警，同一次的 ACTIVE 与 CLEARED 共用；仅靠 (tank, type)
     * 不够——断网补传时两次重叠的同类报警会配错。所以带上产生时刻：{@code 类型-罐号-产生时 tsMs}。
     *
     * @return 不可上报的类型返回 null。
     */
    public static String alarmIdFor(int oilMessageType, int tank, long raisedAtMs) {
        Mapping mapping = TYPE_MAPPING.get(oilMessageType);
        if (mapping == null) {
            return null;
        }
        return mapping.type + "-" + tank + "-" + raisedAtMs;
    }

    /**
     * @param alarmId 由 {@link #alarmIdFor} 在报警产生时生成，恢复时传同一个值。
     * @param text    人类可读文案，调用方负责生成（比如 {@code OilMessage.toString()}）——这个类
     *                本身必须保持无 Android 依赖，而 {@code OilMessage.toString()} 经
     *                {@code getState()} 最终会调 {@code SystemUtils.getRsStr()} →
     *                {@code MyApplication.getInstance().getResources()}，是硬 Android 依赖，
     *                不能放进这个纯 Java 类，否则这个包"无 Android 依赖、可 JVM 单测"的前提
     *                就破了。注意这个文案会跟随设备当前 UI 语言（中/英切换），不保证永远是
     *                中文，尽管云端字段注释写的是"human-readable Chinese text"。
     * @return 不可上报的类型（见 {@link #isReportable}）返回 null。
     */
    public static Map<String, Object> build(int oilMessageType, int tank, String alarmId, boolean recovered,
                                            String text, double value, long deviceTimeMs) {
        return build(oilMessageType, tank, alarmId, recovered, text, value, deviceTimeMs, null, null);
    }

    /**
     * 同上，另带报警发生时该罐的介质（zhongchengyeweiyi#49，fixture {@code alarm_active_oil_type.json}）：
     * {@code oilType} 显示名、{@code oilTypeIndex} 油品表下标，均可选，null 时不出现。恢复报文传 ACTIVE 时记下的值。
     */
    public static Map<String, Object> build(int oilMessageType, int tank, String alarmId, boolean recovered,
                                            String text, double value, long deviceTimeMs,
                                            String oilType, Integer oilTypeIndex) {
        Mapping mapping = TYPE_MAPPING.get(oilMessageType);
        if (mapping == null) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("alarmId", alarmId);
        event.put("status", recovered ? STATUS_CLEARED : STATUS_ACTIVE);
        event.put("type", mapping.type);
        event.put("severity", mapping.severity);
        event.put("tank", tank);
        if (oilType != null && !oilType.isEmpty()) {
            event.put("oilType", oilType);
        }
        if (oilTypeIndex != null) {
            event.put("oilTypeIndex", oilTypeIndex);
        }
        event.put("text", text);
        event.put("value", value);
        event.put("tsMs", deviceTimeMs);
        return event;
    }

    /** ThingsBoard telemetry 上行 payload：{@code {"ts":..., "values": {"alarm": {...}}}}。 */
    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> alarmEvent, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TELEMETRY_KEY, alarmEvent);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }

    /**
     * 补发队列的淘汰策略：只有报警"产生"（ACTIVE）是低优先级，按 alarmId 分组——队列满时丢掉最旧的
     * 一条"产生"，连同它后面同一 alarmId 的"恢复"一起丢。"恢复"是受保护事件，兜底淘汰时也跳过：它的
     * "产生"可能已经送达，丢掉"恢复"会让云端那条报警永远停在 ACTIVE。出入库和命令结果不是报警。
     * 解析失败按非报警处理（宁可多留不误删）。
     */
    public static final PendingAlarmStore.EvictionPolicy EVICTION_POLICY = new PendingAlarmStore.EvictionPolicy() {
        @Override
        public boolean isLowPriority(String serializedEvent) {
            JsonObject alarm = alarmOf(serializedEvent);
            return alarm != null && alarm.has("status") && STATUS_ACTIVE.equals(alarm.get("status").getAsString());
        }

        @Override
        public String groupKey(String serializedEvent) {
            JsonObject alarm = alarmOf(serializedEvent);
            return alarm != null && alarm.has("alarmId") ? alarm.get("alarmId").getAsString() : null;
        }

        /** "恢复"事件受保护：兜底淘汰也跳过（代码审核 M3）。 */
        @Override
        public boolean isProtected(String serializedEvent) {
            JsonObject alarm = alarmOf(serializedEvent);
            return alarm != null && alarm.has("status") && STATUS_CLEARED.equals(alarm.get("status").getAsString());
        }
    };

    /** 取出 {@code values.alarm} 对象；不是报警事件或解析失败返回 null。 */
    private static JsonObject alarmOf(String serializedPayload) {
        try {
            JsonObject root = new JsonParser().parse(serializedPayload).getAsJsonObject();
            JsonElement values = root.get("values");
            if (values == null || !values.isJsonObject()) {
                return null;
            }
            JsonElement alarm = values.getAsJsonObject().get(TELEMETRY_KEY);
            return alarm != null && alarm.isJsonObject() ? alarm.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final String TELEMETRY_KEY = "alarm";
}
