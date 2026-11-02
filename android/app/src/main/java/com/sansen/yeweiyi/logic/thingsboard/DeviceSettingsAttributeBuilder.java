package com.sansen.yeweiyi.logic.thingsboard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整机设置（基础设置页 {@code SetBaseFragment}）作为 client attribute 上报：出入库判定的高度/时间阈值。
 * 0 表示未配置（读不到时的默认值），原样上报。新增 key 云端尚未对齐。纯 Java。
 */
public final class DeviceSettingsAttributeBuilder {

    /** 上报与下发（{@code config.setDeviceSettings}）共用的字段名，改名只改这里。 */
    public static final String KEY_IN_HEIGHT_MM = "inStockHeightThresholdMm";
    public static final String KEY_IN_TIME_SEC = "inStockTimeThresholdSec";
    public static final String KEY_OUT_HEIGHT_MM = "outStockHeightThresholdMm";
    public static final String KEY_OUT_TIME_SEC = "outStockTimeThresholdSec";

    private DeviceSettingsAttributeBuilder() {
    }

    public static Map<String, Object> build(int inHeightMm, int inTimeSec, int outHeightMm, int outTimeSec) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(KEY_IN_HEIGHT_MM, inHeightMm);
        attributes.put(KEY_IN_TIME_SEC, inTimeSec);
        attributes.put(KEY_OUT_HEIGHT_MM, outHeightMm);
        attributes.put(KEY_OUT_TIME_SEC, outTimeSec);
        return attributes;
    }
}
