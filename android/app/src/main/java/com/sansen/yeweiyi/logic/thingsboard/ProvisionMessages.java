package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * ThingsBoard 设备自助注册（Device Provisioning）的请求构造与应答解析，字段名按 ThingsBoard
 * 官方 MQTT API。只接受 ACCESS_TOKEN 类型的凭据：本设备的 MQTT 认证只支持 token 当用户名。
 */
public final class ProvisionMessages {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String CREDENTIALS_TYPE_ACCESS_TOKEN = "ACCESS_TOKEN";

    private ProvisionMessages() {
    }

    public static String buildRequest(String deviceName, String provisionDeviceKey, String provisionDeviceSecret) {
        JsonObject request = new JsonObject();
        request.addProperty("deviceName", deviceName);
        request.addProperty("provisionDeviceKey", provisionDeviceKey);
        request.addProperty("provisionDeviceSecret", provisionDeviceSecret);
        return request.toString();
    }

    /** 返回 SUCCESS、REJECTED（云端明确拒绝）或 MALFORMED（应答格式不认识），不会返回 null。 */
    public static ProvisionResult parseResponse(String json) {
        JsonObject root;
        try {
            JsonElement element = new JsonParser().parse(json == null ? "" : json);
            if (!element.isJsonObject()) {
                return ProvisionResult.failure(ProvisionResult.Outcome.MALFORMED, "应答不是 JSON 对象");
            }
            root = element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return ProvisionResult.failure(ProvisionResult.Outcome.MALFORMED, "应答不是合法 JSON");
        }

        String status = stringOrNull(root, "status");
        if (status == null) {
            return ProvisionResult.failure(ProvisionResult.Outcome.MALFORMED, "应答缺少 status");
        }
        if (!STATUS_SUCCESS.equals(status)) {
            String errorMsg = stringOrNull(root, "errorMsg");
            return ProvisionResult.failure(ProvisionResult.Outcome.REJECTED, errorMsg != null ? errorMsg : status);
        }

        String credentialsType = stringOrNull(root, "credentialsType");
        if (!CREDENTIALS_TYPE_ACCESS_TOKEN.equals(credentialsType)) {
            // 云端 Device Profile 配成了证书/MQTT basic 等别的凭据类型，本设备用不了，属于配置错误。
            return ProvisionResult.failure(ProvisionResult.Outcome.REJECTED,
                    "不支持的凭据类型: " + credentialsType);
        }
        String token = stringOrNull(root, "credentialsValue");
        if (token == null || token.trim().isEmpty()) {
            return ProvisionResult.failure(ProvisionResult.Outcome.MALFORMED, "应答缺少 credentialsValue");
        }
        return ProvisionResult.success(token.trim());
    }

    private static String stringOrNull(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }
}
