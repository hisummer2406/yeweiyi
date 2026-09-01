package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.NetOilCan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一份 {@link NetOilCan} 快照组装成 ThingsBoard telemetry 上报 payload：
 * {@code {"ts": <毫秒时间戳>, "values": {...}}}。
 * <p>
 * 纯 Java、无 Android 依赖，方便在 JVM 单测里对着 fixture 校验字段/单位/结构，
 * 不必起 Service 或连 MQTT。
 * <p>
 * <b>单位说明</b>：{@code oilLevel}/{@code waterLevel} 的 mm 单位有
 * {@code bean/OilCan.java} 字段注释("mm 途径：油罐设置表")佐证，可信。
 * {@code temp}/{@code oilVolume}/{@code waterVolume}/{@code oilWeight} 的单位
 * （摄氏度/升/千克）是沿用现场惯例的假设，尚未有跨仓库协议文档或 fixture 确认
 * （yeweiyi-cloud#7 协议仍未冻结）——一旦协议冻结，先核对这四个单位再改
 * {@link #UNITS}，不要改字段名。
 */
public final class TelemetrySnapshotBuilder {

    /** key 与 payload 里 tank 对象的字段名一一对应，值是单位标识，供云端/人工核对。 */
    public static final Map<String, String> UNITS = buildUnits();

    private static Map<String, String> buildUnits() {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("oilLevel", "mm");
        units.put("waterLevel", "mm");
        units.put("temp", "C");
        units.put("oilVolume", "L");
        units.put("waterVolume", "L");
        units.put("oilWeight", "kg");
        return units;
    }

    private TelemetrySnapshotBuilder() {
    }

    /**
     * @param tanks          当前全部油罐的快照（{@code OilcanDataManager.getNetOilcans()}）
     * @param deviceTimeMs   设备本地时间（epoch 毫秒），不是 ThingsBoard 服务端收到消息的时间
     * @param connectionState 例如 "online"/"degraded"，由调用方按探头轮询健康度决定
     */
    public static Map<String, Object> buildValues(List<NetOilCan> tanks, long deviceTimeMs, String connectionState) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("protocolVersion", ProtocolVersion.CURRENT);
        values.put("deviceTime", deviceTimeMs);
        values.put("connectionState", connectionState);
        values.put("units", UNITS);
        values.put("tanks", buildTankList(tanks));
        return values;
    }

    private static List<Map<String, Object>> buildTankList(List<NetOilCan> tanks) {
        List<Map<String, Object>> result = new java.util.ArrayList<>(tanks.size());
        for (NetOilCan tank : tanks) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", tank.getId());
            t.put("oilType", tank.getOiltype());
            t.put("oilLevel", tank.getOilLevel());
            t.put("waterLevel", tank.getWaterLevel());
            t.put("temp", tank.getTemp());
            t.put("oilVolume", tank.getOilVolume());
            t.put("waterVolume", tank.getWaterVolume());
            t.put("oilWeight", tank.getOilWeight());
            t.put("alarmState", tank.getAlarmState());
            t.put("probeConnected", tank.isConnect());
            t.put("probeAddress", tank.getProbeAddress());
            result.add(t);
        }
        return result;
    }

    /** ThingsBoard 官方 telemetry 上行 payload 外层形状：{@code {"ts":..., "values":{...}}}。 */
    public static Map<String, Object> wrapWithTimestamp(Map<String, Object> values, long tsMillis) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", tsMillis);
        payload.put("values", values);
        return payload;
    }
}
