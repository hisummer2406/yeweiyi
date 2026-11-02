package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.db.OilCanSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 罐设置页（{@code OilCanSetFragment}）的设置项作为 client attribute 上报，每个启用罐一组
 * {@code tank{N}.*}。{@code probeAddress}/{@code capacityL} 已由 {@link ClientAttributesBuilder} 发送，
 * 这里不重复；颜色只用于界面、{@code densVar} 含义未定，均不报。新增 key 云端尚未对齐。
 * <p>
 * 报警阈值为 0 表示该项未启用，原样上报。纯 Java，可在 JVM 单测里直接构造 {@link OilCanSet} 校验。
 */
public final class TankSettingsAttributeBuilder {

    /** 油品序号 → 名称，跟遥测 {@code tank{N}.oilType} 用同一套名称（真机实现见 {@code Const.getOilType}）。 */
    public interface OilTypeNames {
        String nameOf(int oilType);
    }

    private TankSettingsAttributeBuilder() {
    }

    public static Map<String, Object> build(List<OilCanSet> oilCanSets, OilTypeNames oilTypeNames) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (oilCanSets == null) {
            return attributes;
        }
        for (OilCanSet set : oilCanSets) {
            if (set.getEnable() != 1) {
                continue;
            }
            int tank = set.getPk_oilCanId();
            attributes.put(key(tank, "tankHeightMm"), set.getHeight());
            putFinite(attributes, key(tank, "sensorLengthMm"), floatToDouble(set.getChuanGanQiHight()));
            // 储罐类型下拉框序号：0 = 卧罐，1 = 立罐（res/values*/array.xml guan_type）；文字随界面语言变，不报文字
            attributes.put(key(tank, "tankShape"), set.getGunType());
            attributes.put(key(tank, "oilType"), oilTypeNames.nameOf(set.getOilType()));
            // 名称随界面语言变化，云端读写以序号为准（config.setTankSettings 下发的也是序号）
            attributes.put(key(tank, "oilTypeIndex"), set.getOilType());
            attributes.put(key(tank, "alarmHighHighLevelMm"), set.getHheightOilAlarm());
            attributes.put(key(tank, "alarmHighLevelMm"), set.getHeightOilAlarm());
            attributes.put(key(tank, "alarmLowLevelMm"), set.getLowOilAlarm());
            attributes.put(key(tank, "alarmLowLowLevelMm"), set.getLlowOilAlarm());
            attributes.put(key(tank, "alarmHighWaterMm"), set.getHeightWaterAlarm());
            attributes.put(key(tank, "alarmHighTempC"), set.getHighTempAlarm());
            attributes.put(key(tank, "alarmLowTempC"), set.getLowTempAlarm());
            putFinite(attributes, key(tank, "levelCorrectionMm"), set.getOilCorrection());
            putFinite(attributes, key(tank, "waterCorrectionMm"), set.getWaterCorrection());
            putFinite(attributes, key(tank, "tempCorrectionC"), set.getTempCorrection());
            // 原样上报：设置页标的单位是 kg/m³，库里实际存的是 0.56 这类值，单位待云端对齐
            putFinite(attributes, key(tank, "density"), set.getDensity());
        }
        return attributes;
    }

    /** JSON 表示不了 NaN/Infinity，默认 Gson 遇到会抛异常导致整条属性发不出去，所以跳过。 */
    private static void putFinite(Map<String, Object> attributes, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            attributes.put(key, value);
        }
    }

    /** float 直接转 double 会带尾数（2.6f → 2.5999999046325684），按 float 的十进制表示转。 */
    private static double floatToDouble(float value) {
        return Double.parseDouble(Float.toString(value));
    }

    private static String key(int tank, String field) {
        return "tank" + tank + "." + field;
    }
}
