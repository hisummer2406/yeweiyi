package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.OilMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报警产生/恢复事件的立即上报 payload：不进 30 秒快照队列，走独立 telemetry key
 * （{@code alarm}），一产生/一恢复就单独发一条，不等下一次 30 秒周期。
 * <p>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接构造 {@link OilMessage} 校验字段。
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
     * {@code OilMessage} 类型码 → 云端封闭类型 + 严重度。只覆盖这 7 个——其余类型
     * （出入库、低温、网络/继电器故障、探头勘探等）在云端协议里没有对应的家，见
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
        mapping.put(OilMessage.PRO_ERR_CONN, new Mapping(TYPE_PROBE_DISCONNECTED, SEVERITY_MAJOR));
        return mapping;
    }

    private AlarmEventBuilder() {
    }

    /**
     * 报警产生和恢复用同一个 alarmId（按 云端类型+油罐ID 派生，确定性、无需额外状态），
     * 云端据此把两条事件关联成一次完整的报警生命周期。
     *
     * @return 无法映射到云端类型时返回 null（调用方必须处理，见 {@link #build}）。
     */
    public static String defaultAlarmId(OilMessage message) {
        Mapping mapping = TYPE_MAPPING.get(message.getType());
        if (mapping == null) {
            return null;
        }
        return mapping.type + "-" + message.getOilCanId();
    }

    /**
     * @param text 人类可读文案，调用方负责生成（比如 {@code OilMessage.toString()}）——这个类
     *             本身必须保持无 Android 依赖，而 {@code OilMessage.toString()} 经
     *             {@code getState()} 最终会调 {@code SystemUtils.getRsStr()} →
     *             {@code MyApplication.getInstance().getResources()}，是硬 Android 依赖，
     *             不能放进这个纯 Java 类，否则这个包"无 Android 依赖、可 JVM 单测"的前提
     *             就破了。注意这个文案会跟随设备当前 UI 语言（中/英切换），不保证永远是
     *             中文，尽管云端字段注释写的是"human-readable Chinese text"。
     * @return 无法映射到云端封闭类型集合的 {@code OilMessage}（出入库、低温、网络/继电器
     * 故障、探头勘探等）返回 null——调用方（{@code ThingsBoardManager.reportAlarmEvent}）
     * 必须据此跳过发送，不能猜一个近似类型糊弄过去。
     */
    public static Map<String, Object> build(OilMessage message, boolean recovered, String text, long deviceTimeMs) {
        Mapping mapping = TYPE_MAPPING.get(message.getType());
        if (mapping == null) {
            return null;
        }
        return build(message, mapping, recovered, text, deviceTimeMs);
    }

    private static Map<String, Object> build(OilMessage message, Mapping mapping, boolean recovered, String text,
                                               long deviceTimeMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("alarmId", defaultAlarmId(message));
        event.put("status", recovered ? STATUS_CLEARED : STATUS_ACTIVE);
        event.put("type", mapping.type);
        event.put("severity", mapping.severity);
        event.put("tank", message.getOilCanId());
        event.put("text", text);
        event.put("value", message.getValue());
        event.put("tsMs", deviceTimeMs);
        return event;
    }

    /** ThingsBoard telemetry 上行 payload：{@code {"ts":..., "values": {"alarm": {...}}}}。 */
    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> alarmEvent, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("alarm", alarmEvent);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }
}
