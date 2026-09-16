package com.sansen.yeweiyi.logic.thingsboard.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sansen.yeweiyi.logic.thingsboard.ProtocolVersion;

/**
 * 一条 ThingsBoard server-side RPC 请求的解析结果。
 * <p>
 * ThingsBoard 官方 RPC 请求本身只有 {@code {"method":..., "params":{...}}}，requestId 在
 * topic 里（{@code v1/devices/me/rpc/request/{requestId}}），只用于把响应发回对应的
 * {@code v1/devices/me/rpc/response/{requestId}}，跟"命令是否已处理过"这件事无关。
 * <p>
 * 幂等去重、有效期用的是 {@code params} 里项目自定义的 {@code commandId}/{@code expiresAtMs}
 * 字段——同一个 commandId 换一个 MQTT requestId 重发（比如设备断线重连后云端重推），
 * 仍然要能识别成"这条命令已经处理过"。字段名/类型跟 {@code yeweiyi-cloud} 侧
 * {@code internal/protocol.Envelope} 对齐（issue #39）：{@code expiresAtMs}/
 * {@code issuedAtMs}（不是 {@code expireAt}/{@code issuedAt}），{@code protocolVersion}
 * 是数字（不是字符串）。
 */
public final class RpcRequestEnvelope {

    /**
     * {@code params.protocolVersion} 缺失或不是数字时用这个占位，不能默认成
     * {@link ProtocolVersion#CURRENT}——那样等于让版本校验对"根本没带版本号/版本号非法"
     * 的请求直接放行，形同虚设：畸形请求或还在用旧协议的发送方，其 payload 语义完全
     * 不能假定跟 V1 一致，版本门禁本来就是为了拦住这种情况（PR #38 Codex 第六轮审核 P2）。
     * 取一个不可能等于任何真实协议版本号的负数（版本号跟云端 {@code const Version = 1}
     * 一样恒为正数），让 {@link #isProtocolVersionSupported()} 自然返回 false，
     * 走既有的"协议版本不支持"拒绝路径——这条路径会话按 commandId 去重记录，
     * 比在 {@link #parse} 里直接抛 {@link MalformedRpcRequestException} 更合适：
     * 这时候 commandId 已经解析出来了，值得保留去重能力，不是"连 commandId 都拿不到"
     * 那种没法记录的畸形请求。
     */
    private static final int INVALID_PROTOCOL_VERSION = -1;

    private final String mqttRequestId;
    private final String method;
    private final int protocolVersion;
    private final String commandId;
    private final long issuedAt;
    private final long expireAt;
    private final String operator;
    private final String reason;
    private final JsonObject args;

    private RpcRequestEnvelope(String mqttRequestId, String method, int protocolVersion, String commandId,
                                long issuedAt, long expireAt, String operator, String reason, JsonObject args) {
        this.mqttRequestId = mqttRequestId;
        this.method = method;
        this.protocolVersion = protocolVersion;
        this.commandId = commandId;
        this.issuedAt = issuedAt;
        this.expireAt = expireAt;
        this.operator = operator;
        this.reason = reason;
        this.args = args;
    }

    /**
     * @throws MalformedRpcRequestException json 不是合法对象，或者缺 method/params 必填字段
     */
    public static RpcRequestEnvelope parse(String mqttRequestId, String json) {
        JsonObject root;
        try {
            // 项目锁定 gson 2.2.2（很老的版本，见 app/build.gradle），没有后来加入的
            // JsonParser.parseString 静态方法，只能用实例方法。
            root = new JsonParser().parse(json).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new MalformedRpcRequestException("RPC payload 不是合法 JSON 对象: " + e.getMessage());
        }
        if (!root.has("method") || !root.get("method").isJsonPrimitive()) {
            throw new MalformedRpcRequestException("缺少 method 字段");
        }
        String method = root.get("method").getAsString();

        JsonObject params = root.has("params") && root.get("params").isJsonObject()
                ? root.getAsJsonObject("params") : new JsonObject();

        String commandId = optString(params, "commandId", null);
        if (commandId == null || commandId.trim().isEmpty()) {
            throw new MalformedRpcRequestException("缺少 commandId 字段——无法做幂等去重");
        }
        long expireAt = optLong(params, "expiresAtMs", -1L);
        if (expireAt < 0) {
            throw new MalformedRpcRequestException("缺少或非法的 expiresAtMs 字段——无法做有效期校验");
        }

        int protocolVersion = optInt(params, "protocolVersion", INVALID_PROTOCOL_VERSION);
        long issuedAt = optLong(params, "issuedAtMs", 0L);
        String operator = optString(params, "operator", null);
        String reason = optString(params, "reason", null);
        JsonObject args = params.has("args") && params.get("args").isJsonObject()
                ? params.getAsJsonObject("args") : new JsonObject();

        return new RpcRequestEnvelope(mqttRequestId, method, protocolVersion, commandId, issuedAt, expireAt,
                operator, reason, args);
    }

    private static String optString(JsonObject obj, String key, String def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : def;
    }

    private static long optLong(JsonObject obj, String key, long def) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsLong() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int optInt(JsonObject obj, String key, int def) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsInt() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expireAt;
    }

    public boolean isProtocolVersionSupported() {
        return ProtocolVersion.CURRENT == protocolVersion;
    }

    public String getMqttRequestId() {
        return mqttRequestId;
    }

    public String getMethod() {
        return method;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public String getCommandId() {
        return commandId;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public long getExpireAt() {
        return expireAt;
    }

    public String getOperator() {
        return operator;
    }

    public String getReason() {
        return reason;
    }

    public JsonObject getArgs() {
        return args;
    }

    /** JSON 语法错误、或缺少 method/commandId/expireAt 等必填字段时抛出。 */
    public static final class MalformedRpcRequestException extends RuntimeException {
        public MalformedRpcRequestException(String message) {
            super(message);
        }
    }
}
