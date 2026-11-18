package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * 解析 ThingsBoard 共享属性（服务端→设备下发）载荷——issue #39/#40 遗留的 P1-4
 * "Shared Attributes 完全没接"，这次只补 {@code reportIntervalSec}（覆盖 30 秒快照周期，
 * protocol-v1.md §5.2）。
 * <p>
 * <b>{@code alarmThresholds} 故意不在这里解析</b>：协议文档只写了"每罐报警阈值，JSON"，
 * 没给出具体字段形状——对照 {@code config.setAlarmThresholds} RPC 的 args 形状（oilCanId +
 * 四个阈值字段）大概率是同一套字段,但这是猜测,不是跨仓库确认过的结论。照这个包里一贯的
 * 做法（{@code config.setTank}/{@code config.setProbe} 两个 RPC 方法同样因为参数形状未定
 * 不注册 handler，见 {@code ThingsBoardManager}），宁可先不接也不要按猜测的形状写解析器——
 * 形状猜错时,云端真正下发会被解析成错误的阈值覆盖本地配置,比完全不解析更危险。
 * <p>
 * ThingsBoard 设备 MQTT API 对共享属性有两种载荷形状，由调用方（{@code ThingsBoardManager}）
 * 按消息来源的 topic 区分后调不同的入口：
 * <ul>
 *   <li>{@code v1/devices/me/attributes} 上的主动推送——根对象直接就是发生变化的共享属性，
 *       例如 {@code {"reportIntervalSec":60}}，用 {@link #parsePushedReportIntervalMillis}；</li>
 *   <li>{@code v1/devices/me/attributes/response/{id}} 上对 {@code attributes/request} 的
 *       应答——外面包一层 {@code {"shared":{...},"client":{...}}}，真正的共享属性值在
 *       {@code shared} 子对象里，用 {@link #parseResponseReportIntervalMillis}。ThingsBoard
 *       只在设备主动请求时才回这个值，不会在每次订阅/重连时自动重发当前值（跟 protocol-v1.md
 *       §5.2 "ThingsBoard 会在设备每次重连时重新下发" 的表述不完全一致——那句话描述的是共享
 *       属性变化会被推送到已订阅的设备，不是订阅本身会重放历史值；要在重连时立刻拿到当前值，
 *       设备必须主动发一次 {@code attributes/request}，{@code ThingsBoardManager} 正是这么做的）。
 * </ul>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接用 JSON 字符串校验。
 */
public final class SharedAttributesParser {

    /** 键名，跟 protocol-v1.md §5.2 一致。 */
    static final String KEY_REPORT_INTERVAL_SEC = "reportIntervalSec";

    private SharedAttributesParser() {
    }

    /**
     * @param pushJson {@code v1/devices/me/attributes} 推送消息的原始 payload。
     * @return 新的快照周期（毫秒）；键缺失、不是数字、或数值非正，都返回 {@code null}——调用方
     * 保持当前周期不变，不能让一条畸形/被篡改的消息把周期改成 0 或负数（0 会让
     * {@link SnapshotTrigger#setPeriodMillis} 直接抛异常，负数没有意义）。
     */
    public static Long parsePushedReportIntervalMillis(String pushJson) {
        return reportIntervalMillisFrom(parseObject(pushJson));
    }

    /**
     * @param responseJson {@code v1/devices/me/attributes/response/{id}} 应答消息的原始
     *                      payload，形状是 {@code {"shared":{...},"client":{...}}}。
     */
    public static Long parseResponseReportIntervalMillis(String responseJson) {
        JsonObject root = parseObject(responseJson);
        if (root == null) {
            return null;
        }
        JsonElement shared = root.get("shared");
        if (shared == null || !shared.isJsonObject()) {
            return null;
        }
        return reportIntervalMillisFrom(shared.getAsJsonObject());
    }

    private static Long reportIntervalMillisFrom(JsonObject sharedAttributes) {
        if (sharedAttributes == null) {
            return null;
        }
        JsonElement value = sharedAttributes.get(KEY_REPORT_INTERVAL_SEC);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        long seconds;
        try {
            seconds = value.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
        if (seconds <= 0) {
            return null;
        }
        return seconds * 1000L;
    }

    private static JsonObject parseObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            // 项目锁定 gson 2.2.2（见 app/build.gradle），没有后来加入的
            // JsonParser.parseString 静态方法，只能用实例方法（同 RpcRequestEnvelope.parse）。
            JsonElement root = new JsonParser().parse(json);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }
}
