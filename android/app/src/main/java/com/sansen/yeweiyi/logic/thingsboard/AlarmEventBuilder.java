package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.OilMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报警产生/恢复事件的立即上报 payload：不进 30 秒快照队列，走独立 telemetry key
 * （{@code alarmEvent}），一产生/一恢复就单独发一条，不等下一次 30 秒周期。
 * <p>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接构造 {@link OilMessage} 校验字段。
 */
public final class AlarmEventBuilder {

    public static final String STATE_RAISED = "raised";
    public static final String STATE_RECOVERED = "recovered";

    private AlarmEventBuilder() {
    }

    /**
     * 报警产生和恢复用同一个 eventId（按 类型+油罐ID 派生，确定性、无需额外状态），
     * 云端据此把两条事件关联成一次完整的报警生命周期。
     */
    public static String defaultEventId(OilMessage message) {
        return message.getType() + "-" + message.getOilCanId();
    }

    public static Map<String, Object> build(OilMessage message, boolean recovered, long deviceTimeMs) {
        return build(message, recovered, defaultEventId(message), deviceTimeMs);
    }

    public static Map<String, Object> build(OilMessage message, boolean recovered, String eventId, long deviceTimeMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("protocolVersion", ProtocolVersion.CURRENT);
        event.put("eventId", eventId);
        event.put("state", recovered ? STATE_RECOVERED : STATE_RAISED);
        event.put("alarmType", message.getType());
        event.put("oilCanId", message.getOilCanId());
        event.put("value", message.getValue());
        event.put("deviceTime", deviceTimeMs);
        return event;
    }

    /** ThingsBoard telemetry 上行 payload：{@code {"ts":..., "values": {"alarmEvent": {...}}}}。 */
    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> alarmEvent, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("alarmEvent", alarmEvent);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }
}
