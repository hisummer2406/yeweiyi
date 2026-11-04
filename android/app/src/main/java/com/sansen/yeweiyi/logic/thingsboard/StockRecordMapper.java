package com.sansen.yeweiyi.logic.thingsboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次库存保存 → {@code stockRecord} 报文（一条携带全部罐），契约见 yeweiyi-cloud
 * {@code docs/shift-stock-records-contract.md} §2.2，fixture 为 {@code stock_record.json}。纯 Java。
 * recordId = {@code stock-{tsMs}}，tsMs 与本地 {@code StockSave.time} 是同一瞬间（到秒）。
 */
public final class StockRecordMapper {

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 保存那一刻一个罐的读数，单位同 {@code OilCan}：mm、L、℃、kg。 */
    public static final class Tank {
        final int tank;
        final String oilType;
        final Integer oilTypeIndex;
        final double levelMm;
        final double volumeL;
        final double waterLevelMm;
        final double tempC;
        final double weightKg;

        public Tank(int tank, String oilType, Integer oilTypeIndex, double levelMm, double volumeL,
                    double waterLevelMm, double tempC, double weightKg) {
            this.tank = tank;
            this.oilType = oilType;
            this.oilTypeIndex = oilTypeIndex;
            this.levelMm = levelMm;
            this.volumeL = volumeL;
            this.waterLevelMm = waterLevelMm;
            this.tempC = tempC;
            this.weightKg = weightKg;
        }
    }

    private StockRecordMapper() {
    }

    /** @param trigger {@link #TRIGGER_SCHEDULED}/{@link #TRIGGER_MANUAL}；null 表示未知，不出现在报文里。 */
    public static Map<String, Object> build(String trigger, long tsMs, List<Tank> tanks) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordId", "stock-" + tsMs);
        if (trigger != null) {
            record.put("trigger", trigger);
        }
        record.put("tsMs", tsMs);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tank t : tanks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tank", t.tank);
            if (t.oilType != null && !t.oilType.isEmpty()) {
                row.put("oilType", t.oilType);
            }
            if (t.oilTypeIndex != null) {
                row.put("oilTypeIndex", t.oilTypeIndex);
            }
            ShiftEventMapper.putRounded(row, "levelMm", t.levelMm);
            ShiftEventMapper.putRounded(row, "volumeL", t.volumeL);
            ShiftEventMapper.putRounded(row, "waterLevelMm", t.waterLevelMm);
            ShiftEventMapper.putRounded(row, "tempC", t.tempC);
            ShiftEventMapper.putRounded(row, "weightKg", t.weightKg);
            rows.add(row);
        }
        record.put("tanks", rows);
        return record;
    }

    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> stockRecord, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stockRecord", stockRecord);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }
}
