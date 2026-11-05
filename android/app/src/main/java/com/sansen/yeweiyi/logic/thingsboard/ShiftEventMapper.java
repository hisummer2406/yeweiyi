package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 交班记录（{@code commitwork} 一行）→ {@code shiftEvent} 报文，契约见 yeweiyi-cloud
 * {@code docs/shift-stock-records-contract.md} §2.1，fixture 为 {@code shift_event_*.json}。纯 Java。
 * <p>
 * 缺失值一律不出现（Gson 默认不写 null），读方把缺键当 null。endTime 为空即进行中（OPEN），此时
 * 不论 info 里存了什么都不报结束数据——升级前的旧记录进行中时结束字段存的是 0.0。
 */
public final class ShiftEventMapper {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Gson GSON = new Gson();

    private ShiftEventMapper() {
    }

    /** @return 开始时刻无法确定（理论上不会发生）时返回 null，调用方丢弃该条。 */
    public static Map<String, Object> build(CommitWork row, TimeZone tz) {
        Long beginTimeMs = epochOrParse(row.getBeginTimeMs(), row.getTime(), tz);
        if (beginTimeMs == null) {
            return null;
        }
        boolean closed = row.getEndTime() != null && !row.getEndTime().isEmpty();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("shiftId", shiftIdFor(beginTimeMs));
        event.put("status", closed ? STATUS_CLOSED : STATUS_OPEN);
        event.put("beginTimeMs", beginTimeMs);
        if (closed) {
            Long endTimeMs = epochOrParse(row.getEndTimeMs(), row.getEndTime(), tz);
            if (endTimeMs != null) {
                event.put("endTimeMs", endTimeMs);
            }
        }
        List<Map<String, Object>> tanks = new ArrayList<>();
        for (OilWork work : parseWorks(row.getInfo())) {
            tanks.add(tank(work, closed));
        }
        event.put("tanks", tanks);
        return event;
    }

    /** 同一班开始与结束报文共用；开始时刻来自 {@code commitwork.begintimems}，终身不变。 */
    public static String shiftIdFor(long beginTimeMs) {
        return "shift-" + beginTimeMs;
    }

    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> shiftEvent, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("shiftEvent", shiftEvent);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }

    private static Map<String, Object> tank(OilWork work, boolean closed) {
        Map<String, Object> tank = new LinkedHashMap<>();
        tank.put("tank", work.getOilId());
        if (work.getJieZhi() != null && !work.getJieZhi().isEmpty()) {
            tank.put("oilType", work.getJieZhi());
        }
        putIfPresent(tank, "oilTypeIndex", work.getOilTypeIndex());
        putRounded(tank, "beginLevelMm", work.getBeginHeight());
        putRounded(tank, "beginVolumeL", work.getBeginVolume());
        if (closed) {
            putRounded(tank, "endLevelMm", work.getEndHeight());
            putRounded(tank, "endVolumeL", work.getEndVolume());
            putRounded(tank, "outVolumeL", work.getShellVolume());
            putRounded(tank, "outWeightKg", work.getShellWeight());
        }
        return tank;
    }

    private static List<OilWork> parseWorks(String info) {
        List<OilWork> works = new ArrayList<>();
        if (info == null || info.isEmpty()) {
            return works;
        }
        try {
            OilWork[] parsed = GSON.fromJson(info, OilWork[].class);
            if (parsed != null) {
                for (OilWork w : parsed) {
                    if (w != null) {
                        works.add(w);
                    }
                }
            }
        } catch (JsonParseException e) {
            // 旧数据损坏：照样报出班的起止，tanks 为空
        }
        return works;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** 保留 0.1，与 stockEvent 的体积口径一致；罐表换算出的 float 尾数不带进协议。 */
    static void putRounded(Map<String, Object> map, String key, Double value) {
        if (value != null && !value.isNaN() && !value.isInfinite()) {
            map.put(key, Math.round(value * 10) / 10.0);
        }
    }

    /** epoch 列优先（不受时区变化影响）；旧记录没有该列时按设备时区解析本地时间字符串（秒或分钟）。 */
    private static Long epochOrParse(Long epochMs, String local, TimeZone tz) {
        if (epochMs != null) {
            return epochMs;
        }
        if (local == null || local.isEmpty()) {
            return null;
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern);
            format.setTimeZone(tz);
            format.setLenient(false);
            try {
                return format.parse(local).getTime();
            } catch (ParseException e) {
                // 试下一个格式
            }
        }
        return null;
    }
}
