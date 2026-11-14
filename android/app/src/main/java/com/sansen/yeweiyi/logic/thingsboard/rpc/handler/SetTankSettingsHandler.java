package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.OilColors;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.repository.OilTankRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code config.setTankSettings}：修改单个罐的设置，字段名与客户端属性相同，清单与取值范围见
 * {@code docs/远程配置下发-字段清单.md}。只改 args 里出现的字段；任一字段非法或属于不开放字段，整条拒绝
 * 不写入；不认识的字段按协议忽略，列在结果的 {@code ignored} 里。同步写库成功后才回成功，再刷新本地缓存。
 */
public final class SetTankSettingsHandler implements RpcMethodHandler {

    public static final String METHOD = "config.setTankSettings";
    static final int MAX_TANK = 16;
    static final int MAX_OIL_TYPE_INDEX = 39; // = oiltypeArray 条数 - 2（去掉"未设置""空白"）+ 8 个自定义位，改数组要同步
    /** 认识但不开放远程修改的字段：拒绝而不是忽略，云端发了是期望生效的，静默忽略会误以为改成功了。 */
    static final String FIELD_NAME = "customOilName";
    static final String FIELD_NAME_EN = "customOilNameEn";
    static final int CUSTOM_OIL_FROM = 32;

    static final Set<String> CLOSED_FIELDS = new HashSet<>(Arrays.asList(
            "alarmLowTempC", "tankHeightMm", "probeAddress", "tankShape", "sensorLengthMm"));

    private final OilTankRepository oilTankRepository;
    private final Runnable onSettingsUpdated;
    private final CustomOilNameStore customOilNames;
    private final OilColorRejectText colorText;

    public SetTankSettingsHandler(OilTankRepository oilTankRepository, Runnable onSettingsUpdated,
                                  CustomOilNameStore customOilNames, OilColorRejectText colorText) {
        this.oilTankRepository = oilTankRepository;
        this.onSettingsUpdated = onSettingsUpdated;
        this.customOilNames = customOilNames;
        this.colorText = colorText;
    }

    @Override
    public RpcResult handle(RpcRequestEnvelope request) {
        JsonObject args = request.getArgs();
        Integer tank = intValue(args.get("tank"));
        if (tank == null || tank < 1 || tank > MAX_TANK) {
            return invalid("tank 必须是 1～" + MAX_TANK + " 的整数");
        }
        OilCanSet set = oilTankRepository.findOilCanSetById(tank);
        if (set == null || set.getEnable() != 1) {
            return invalid("罐 " + tank + " 不存在或未启用");
        }

        // 先全部校验、再统一写到对象上：任一字段被拒时对象保持原样
        List<String> updated = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        try {
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
                String field = entry.getKey();
                if ("tank".equals(field) || FIELD_NAME.equals(field) || FIELD_NAME_EN.equals(field)) {
                    continue;
                }
                Change change = validate(set, field, entry.getValue(), colorText);
                if (change == null) {
                    ignored.add(field);
                } else {
                    changes.add(change);
                    updated.add(field);
                }
            }
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }
        if (updated.isEmpty()) {
            return invalid("没有可修改的字段" + (ignored.isEmpty() ? "" : "，已忽略: " + ignored));
        }
        // 只在本次改了液位阈值时校验顺序：本地设置页不校验顺序，库里可能本就不合规，
        // 不能因此挡住改密度这类无关修改
        boolean touchesLevels = false;
        int[] levels = {set.getLlowOilAlarm(), set.getLowOilAlarm(), set.getHeightOilAlarm(), set.getHheightOilAlarm()};
        for (Change change : changes) {
            touchesLevels |= change.applyToLevels(levels);
        }
        String orderError = touchesLevels ? levelOrderError(levels) : null;
        if (orderError != null) {
            return invalid(orderError);
        }
        String[] name;
        try {
            name = customOilName(args);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }
        for (Change change : changes) {
            change.applyTo(set);
        }

        // 名称和编号要么都生效要么都不生效（#27）：先写名称，罐设置写失败就恢复原名称
        int slot = name == null ? -1 : intValue(args.get("oilTypeIndex"));
        String[] previousName = null;
        try {
            if (name != null) {
                previousName = customOilNames.save(slot, name[0], name[1]);
                updated.add(FIELD_NAME);
            }
            oilTankRepository.updateOilCanSetSync(set);
        } catch (Exception e) {
            if (previousName != null) {
                customOilNames.save(slot, previousName[0], previousName[1]);
            }
            return RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "写入罐设置失败: " + e.getMessage());
        }
        onSettingsUpdated.run();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tank", tank);
        data.put("updated", updated);
        if (!ignored.isEmpty()) {
            data.put("ignored", ignored);
        }
        return RpcResult.succeeded("罐设置已更新", data);
    }

    /** 返回 {中文名, 英文名}；没带名称返回 null。名称只能和 32–39 的自定义编号一起下发。 */
    private static String[] customOilName(JsonObject args) {
        JsonElement zh = args.get(FIELD_NAME);
        JsonElement en = args.get(FIELD_NAME_EN);
        if (zh == null && en == null) {
            return null;
        }
        Integer index = intValue(args.get("oilTypeIndex"));
        if (zh == null || index == null || index < CUSTOM_OIL_FROM || index > MAX_OIL_TYPE_INDEX) {
            throw new IllegalArgumentException("介质名称只能和 32–39 的 oilTypeIndex 一起下发");
        }
        String name = requireName(FIELD_NAME, zh, 20);
        return new String[]{name, en == null ? name : requireName(FIELD_NAME_EN, en, 40)};
    }

    private static String requireName(String field, JsonElement value, int limit) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " 必须是文本");
        }
        String text = value.getAsString().trim();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > limit) {
            throw new IllegalArgumentException(field + " 长度必须是 1～" + limit);
        }
        return text;
    }

    private interface Applier {
        void apply(OilCanSet set);
    }

    /** 一个已校验通过、尚未写入的字段修改；液位阈值另记下标和值，供写入前的顺序校验。 */
    private static final class Change {
        final Applier applier;
        final int levelIndex;
        final int levelValue;

        Change(Applier applier) {
            this(applier, -1, 0);
        }

        Change(Applier applier, int levelIndex, int levelValue) {
            this.applier = applier;
            this.levelIndex = levelIndex;
            this.levelValue = levelValue;
        }

        void applyTo(OilCanSet set) {
            applier.apply(set);
        }

        /** levels 为 [低低, 低, 高, 高高]；返回本修改是否是液位阈值。 */
        boolean applyToLevels(int[] levels) {
            if (levelIndex < 0) {
                return false;
            }
            levels[levelIndex] = levelValue;
            return true;
        }
    }

    /**
     * 校验一个字段，返回待写入的修改；不认识的字段返回 null（忽略）；非法值或不开放字段抛
     * IllegalArgumentException，调用方整条拒绝。
     */
    private static Change validate(OilCanSet set, String field, JsonElement value, OilColorRejectText colorText) {
        int tankHeight = set.getHeight();
        switch (field) {
            case "alarmLowLowLevelMm": {
                int v = requireInt(field, value, 0, tankHeight);
                return new Change(s -> s.setLlowOilAlarm(v), 0, v);
            }
            case "alarmLowLevelMm": {
                int v = requireInt(field, value, 0, tankHeight);
                return new Change(s -> s.setLowOilAlarm(v), 1, v);
            }
            case "alarmHighLevelMm": {
                int v = requireInt(field, value, 0, tankHeight);
                return new Change(s -> s.setHeightOilAlarm(v), 2, v);
            }
            case "alarmHighHighLevelMm": {
                int v = requireInt(field, value, 0, tankHeight);
                return new Change(s -> s.setHheightOilAlarm(v), 3, v);
            }
            case "alarmHighWaterMm": {
                int v = requireInt(field, value, 0, tankHeight);
                return new Change(s -> s.setHeightWaterAlarm(v));
            }
            case "alarmHighTempC": {
                int v = requireInt(field, value, -50, 100);
                return new Change(s -> s.setHighTempAlarm(v));
            }
            case "levelCorrectionMm": {
                double v = requireFinite(field, value);
                return new Change(s -> s.setOilCorrection(v));
            }
            case "waterCorrectionMm": {
                double v = requireFinite(field, value);
                return new Change(s -> s.setWaterCorrection(v));
            }
            case "tempCorrectionC": {
                double v = requireFinite(field, value);
                return new Change(s -> s.setTempCorrection(v));
            }
            case "density": {
                double v = requireNumber(field, value, 0, 2);
                if (v <= 0) {
                    throw new IllegalArgumentException("density 必须大于 0");
                }
                return new Change(s -> s.setDensity(v));
            }
            case "oilTypeIndex": {
                int v = requireInt(field, value, 0, MAX_OIL_TYPE_INDEX);
                return new Change(s -> s.setOilType(v));
            }
            case "oilColor": {
                int v = requireInt(field, value, 0, 0xFFFFFF);
                if (!OilColors.isPaletteRgb(v)) {
                    throw new IllegalArgumentException(colorText.notInPalette(String.format("#%06X", v)));
                }
                return new Change(s -> s.setOilColor(OilColors.toArgb(v)));
            }
            case "capacityL": {
                int v = requireInt(field, value, 0, 1_000_000);
                return new Change(s -> s.setMaxVolume(v));
            }
            default:
                if (CLOSED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("不开放远程修改的字段: " + field);
                }
                return null;
        }
    }

    /** levels 为 [低低, 低, 高, 高高]；0 = 不启用、不参与比较；非 0 的必须 低低 < 低 < 高 < 高高。 */
    static String levelOrderError(int[] values) {
        String[] names = {"低低", "低", "高", "高高"};
        int prev = -1;
        String prevName = null;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) {
                continue;
            }
            if (prevName != null && values[i] <= prev) {
                return "液位阈值须满足 " + prevName + "(" + prev + ") < " + names[i] + "(" + values[i] + ")";
            }
            prev = values[i];
            prevName = names[i];
        }
        return null;
    }

    private static int requireInt(String field, JsonElement value, int min, int max) {
        Integer v = intValue(value);
        if (v == null) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(field + " 超出范围 " + min + "～" + max + ": " + v);
        }
        return v;
    }

    /** 修正值不设上下限（#28，与本地设置页一致）；超出 double 的值变成无穷大，仍要拒绝。 */
    private static double requireFinite(String field, JsonElement value) {
        return requireNumber(field, value, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    private static double requireNumber(String field, JsonElement value, double min, double max) {
        BigDecimal v = numberValue(value);
        if (v == null) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
        double d = v.doubleValue();
        if (d < min || d > max) {
            throw new IllegalArgumentException(field + " 超出范围 " + min + "～" + max + ": " + v);
        }
        return d;
    }

    /** 只接受 JSON 数字（不接受 "650" 字符串、650.5 这种带小数的值）。 */
    static Integer intValue(JsonElement value) {
        BigDecimal v = numberValue(value);
        if (v == null) {
            return null;
        }
        try {
            return v.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static BigDecimal numberValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsBigDecimal();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RpcResult invalid(String message) {
        return RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, message);
    }
}
