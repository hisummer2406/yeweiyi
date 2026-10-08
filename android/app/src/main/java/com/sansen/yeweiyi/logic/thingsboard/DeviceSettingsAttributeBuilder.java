package com.sansen.yeweiyi.logic.thingsboard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整机设置（基础设置页 {@code SetBaseFragment}）作为 client attribute 上报：出入库判定的高度/时间阈值。
 * 0 表示未配置（读不到时的默认值），原样上报。新增 key 云端尚未对齐。纯 Java。
 */
public final class DeviceSettingsAttributeBuilder {

    private DeviceSettingsAttributeBuilder() {
    }

    public static Map<String, Object> build(int inHeightMm, int inTimeSec, int outHeightMm, int outTimeSec) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("inStockHeightThresholdMm", inHeightMm);
        attributes.put("inStockTimeThresholdSec", inTimeSec);
        attributes.put("outStockHeightThresholdMm", outHeightMm);
        attributes.put("outStockTimeThresholdSec", outTimeSec);
        return attributes;
    }
}
