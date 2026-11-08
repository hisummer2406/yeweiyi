package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.logic.thingsboard.DeviceSettingsAttributeBuilder;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config.setDeviceSettings}：修改整机出入库阈值，字段名与客户端属性相同，见
 * {@code docs/远程配置下发-字段清单.md}。下限为 1：本地基础设置页填 0 不保存，远程保持一致。
 */
public final class SetDeviceSettingsHandler implements RpcMethodHandler {

    public static final String METHOD = "config.setDeviceSettings";

    /**
     * 整机设置的落盘与生效（真机实现写 SharedPreferences 并刷新 Singleton），返回前必须已写入。
     * <b>必须全有或全无</b>（#9）：抛异常时不得留下部分新值，回执据此说"未修改任何字段"。
     */
    public interface DeviceSettingsStore {
        void save(Map<String, Integer> values) throws Exception;
    }

    private static final Map<String, int[]> RANGES = new LinkedHashMap<>();

    static {
        RANGES.put(DeviceSettingsAttributeBuilder.KEY_IN_HEIGHT_MM, new int[]{1, 10_000});
        RANGES.put(DeviceSettingsAttributeBuilder.KEY_IN_TIME_SEC, new int[]{1, 86_400});
        RANGES.put(DeviceSettingsAttributeBuilder.KEY_OUT_HEIGHT_MM, new int[]{1, 10_000});
        RANGES.put(DeviceSettingsAttributeBuilder.KEY_OUT_TIME_SEC, new int[]{1, 86_400});
    }

    private final DeviceSettingsStore store;

    public SetDeviceSettingsHandler(DeviceSettingsStore store) {
        this.store = store;
    }

    @Override
    public RpcResult handle(RpcRequestEnvelope request) {
        JsonObject args = request.getArgs();
        Map<String, Integer> values = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String field = entry.getKey();
            int[] range = RANGES.get(field);
            if (range == null) {
                // 协议约定接收方忽略不认识的 key；回执里列出来，云端据此发现拼错
                ignored.add(field);
                continue;
            }
            Integer v = SetTankSettingsHandler.intValue(entry.getValue());
            if (v == null) {
                return invalid(field + " 必须是整数");
            }
            if (v < range[0] || v > range[1]) {
                return invalid(field + " 超出范围 " + range[0] + "～" + range[1] + ": " + v);
            }
            values.put(field, v);
        }
        if (values.isEmpty()) {
            return invalid("没有可修改的字段" + (ignored.isEmpty() ? "" : "，已忽略: " + ignored));
        }

        try {
            store.save(values);
        } catch (Exception e) {
            return RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "写入整机设置失败，未修改任何字段: " + e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", new ArrayList<>(values.keySet()));
        if (!ignored.isEmpty()) {
            data.put("ignored", ignored);
        }
        return RpcResult.succeeded("整机设置已更新", data);
    }

    private static RpcResult invalid(String message) {
        return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, message);
    }
}
