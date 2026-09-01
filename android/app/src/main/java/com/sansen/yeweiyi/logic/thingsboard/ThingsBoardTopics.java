package com.sansen.yeweiyi.logic.thingsboard;

/**
 * ThingsBoard CE 设备端 MQTT topic 常量与构造方法。
 * <p>
 * 全部按 ThingsBoard 官方设备 MQTT API 的固定 topic 形状（无 productKey/deviceName 分段），
 * 因为设备身份已经由连接凭据（access token / 客户端证书）决定，不需要在 topic 里再带一次。
 * 不保留 Alink 风格 {@code sys/{productKey}/{deviceName}/...} topic。
 */
public final class ThingsBoardTopics {

    public static final String TELEMETRY = "v1/devices/me/telemetry";

    public static final String ATTRIBUTES_PUBLISH = "v1/devices/me/attributes";

    private static final String ATTRIBUTES_REQUEST_PREFIX = "v1/devices/me/attributes/request/";
    private static final String ATTRIBUTES_RESPONSE_PREFIX = "v1/devices/me/attributes/response/";

    /** 订阅时用通配符匹配所有 requestId。 */
    public static final String ATTRIBUTES_RESPONSE_SUBSCRIBE = ATTRIBUTES_RESPONSE_PREFIX + "+";

    private static final String RPC_REQUEST_PREFIX = "v1/devices/me/rpc/request/";
    private static final String RPC_RESPONSE_PREFIX = "v1/devices/me/rpc/response/";

    /** 订阅时用通配符匹配所有服务端下发的 RPC 请求。 */
    public static final String RPC_REQUEST_SUBSCRIBE = RPC_REQUEST_PREFIX + "+";

    private ThingsBoardTopics() {
    }

    public static String attributesRequest(int requestId) {
        return ATTRIBUTES_REQUEST_PREFIX + requestId;
    }

    public static String rpcResponse(String requestId) {
        return RPC_RESPONSE_PREFIX + requestId;
    }

    /**
     * 从形如 {@code v1/devices/me/rpc/request/123} 的 topic 中取出 requestId。
     * 不匹配该形状时返回 null，调用方需要据此判断"未知消息，丢弃"而不是抛异常。
     */
    public static String extractRpcRequestId(String topic) {
        if (topic == null || !topic.startsWith(RPC_REQUEST_PREFIX)) {
            return null;
        }
        String id = topic.substring(RPC_REQUEST_PREFIX.length());
        return id.isEmpty() ? null : id;
    }
}
