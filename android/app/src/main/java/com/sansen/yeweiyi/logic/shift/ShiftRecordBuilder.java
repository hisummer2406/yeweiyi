package com.sansen.yeweiyi.logic.shift;

import com.sansen.yeweiyi.bean.OilWork;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 组装交班记录每罐的开始/结束数据（{@code CommitWork.info}）。纯 Java，不依赖 Android。
 * 结束时按罐号匹配开始数据（旧实现按列表下标取，罐号不连续会串罐）；缺的一侧为 null，不填 0（issue #49）。
 */
public final class ShiftRecordBuilder {

    private ShiftRecordBuilder() {
    }

    /** 开始交班：每罐记开始高度、容积和当时的介质（显示名与油品表下标）；结束字段保持 null。 */
    public static List<OilWork> begin(List<TankReading> readings) {
        List<OilWork> works = new ArrayList<>();
        for (TankReading r : readings) {
            OilWork w = new OilWork();
            w.setOilId(r.tank);
            w.setBeginHeight(r.levelMm);
            w.setBeginVolume(r.volumeL);
            w.setJieZhi(r.oilType);
            w.setOilTypeIndex(r.oilTypeIndex);
            works.add(w);
        }
        return works;
    }

    /**
     * 结束交班：以开始数据为底按罐号补结束数据。开始时没有的罐只有结束数据；结束时已不存在的罐保留开始数据。
     * 出库量 = 开始容积 − 结束容积（保留 2 位，同旧口径），出库重量 = 出库量 × 结束时密度；任一侧缺失则为 null。
     * 介质只取开始时记录的值，不用结束时的当前值补。结果按罐号升序。
     */
    public static List<OilWork> end(List<OilWork> begins, List<TankReading> readings) {
        Map<Integer, OilWork> byTank = new TreeMap<>();
        if (begins != null) {
            for (OilWork b : begins) {
                if (b != null) {
                    byTank.put(b.getOilId(), b);
                }
            }
        }
        for (TankReading r : readings) {
            OilWork w = byTank.get(r.tank);
            if (w == null) {
                w = new OilWork();
                w.setOilId(r.tank);
                byTank.put(r.tank, w);
            }
            w.setEndHeight(r.levelMm);
            w.setEndVolume(r.volumeL);
            if (w.getBeginVolume() != null) {
                double out = round2(w.getBeginVolume() - r.volumeL);
                w.setShellVolume(out);
                w.setShellWeight(out * r.density);
            }
        }
        return new ArrayList<>(byTank.values());
    }

    /** 显示用介质：优先记录里存的（开始交班时的），旧记录没有时才退回当前罐设置。 */
    public static String displayOilType(String recorded, String current) {
        return recorded != null && !recorded.isEmpty() ? recorded : current;
    }

    /** 显示用数值：缺失显示 "-"，否则保留 1 位小数（与 {@code DataUtils.doubleFoment} 同口径）。 */
    public static String formatOrDash(Double value) {
        if (value == null) {
            return "-";
        }
        return String.valueOf(Double.parseDouble(format("0.0", value)));
    }

    /** 同 {@code DoubleUtil.double2}。 */
    private static double round2(double value) {
        return Double.parseDouble(format("#0.00", value));
    }

    private static String format(String pattern, double value) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }
}
