package com.sansen.yeweiyi.logic.shift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.sansen.yeweiyi.bean.OilWork;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 交班开始/结束数据组装（issue #49 第二步的缺陷修复）。纯 JVM 运行。 */
public class ShiftRecordBuilderTest {

    private static final double EPS = 1e-9;

    private static TankReading reading(int tank, double level, double volume, double density, String oilType) {
        return new TankReading(tank, level, volume, density, oilType, null);
    }

    private static OilWork byTank(List<OilWork> works, int tank) {
        for (OilWork w : works) {
            if (w.getOilId() == tank) {
                return w;
            }
        }
        throw new AssertionError("tank " + tank + " missing");
    }

    @Test
    public void beginRecordsLevelVolumeAndOilTypePerTank() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Arrays.asList(
                reading(1, 1520.0, 18230.5, 0.74, "92#"),
                reading(3, 800.0, 9000.0, 0.84, "0#")));

        assertEquals(2, begin.size());
        OilWork t3 = byTank(begin, 3);
        assertEquals(800.0, t3.getBeginHeight(), EPS);
        assertEquals(9000.0, t3.getBeginVolume(), EPS);
        assertEquals("0#", t3.getJieZhi());
    }

    @Test
    public void beginRecordsOilTypeIndex() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Collections.singletonList(
                new TankReading(1, 1.0, 1.0, 1.0, "92#", 5)));
        assertEquals(Integer.valueOf(5), begin.get(0).getOilTypeIndex());
    }

    @Test
    public void openShiftHasNullEndFieldsAndSerializesWithoutThem() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Collections.singletonList(
                reading(1, 1520.0, 18230.5, 0.74, "92#")));

        OilWork t1 = begin.get(0);
        assertNull(t1.getEndHeight());
        assertNull(t1.getEndVolume());
        assertNull(t1.getShellVolume());
        assertNull(t1.getShellWeight());
        String json = new Gson().toJson(begin);
        assertFalse(json, json.contains("endHeight"));
        assertFalse(json, json.contains("shellVolume"));
    }

    @Test
    public void endMatchesBeginByTankIdNotListPosition() {
        // 罐号不连续：旧代码按 oilWorks.get(key-1) 取，罐 3 会拿到下标 2（越界→begin 0）或别的罐的数据。
        List<OilWork> begin = ShiftRecordBuilder.begin(Arrays.asList(
                reading(1, 1500.0, 18000.0, 0.74, "92#"),
                reading(3, 800.0, 9000.0, 0.84, "0#")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Arrays.asList(
                reading(1, 1400.0, 17000.0, 0.75, "92#"),
                reading(3, 700.0, 8000.0, 0.85, "0#")));

        OilWork t3 = byTank(end, 3);
        assertEquals(800.0, t3.getBeginHeight(), EPS);
        assertEquals(9000.0, t3.getBeginVolume(), EPS);
        assertEquals(700.0, t3.getEndHeight(), EPS);
        assertEquals(8000.0, t3.getEndVolume(), EPS);
        assertEquals(1000.0, t3.getShellVolume(), EPS);
        assertEquals(1000.0 * 0.85, t3.getShellWeight(), EPS);
        assertEquals("0#", t3.getJieZhi());
        OilWork t1 = byTank(end, 1);
        assertEquals(1000.0, t1.getShellVolume(), EPS);
    }

    @Test
    public void endMatchesWhenBeginOrderDiffersFromEndOrder() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Arrays.asList(
                reading(2, 200.0, 2000.0, 0.7, "95#"),
                reading(1, 100.0, 1000.0, 0.7, "92#")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Arrays.asList(
                reading(1, 90.0, 900.0, 0.7, "92#"),
                reading(2, 150.0, 1500.0, 0.7, "95#")));

        assertEquals(100.0, byTank(end, 1).getShellVolume(), EPS);
        assertEquals(500.0, byTank(end, 2).getShellVolume(), EPS);
    }

    @Test
    public void tankAddedMidShiftHasMissingBeginAndOutVolumeNotZero() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Collections.singletonList(
                reading(1, 1500.0, 18000.0, 0.74, "92#")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Arrays.asList(
                reading(1, 1400.0, 17000.0, 0.74, "92#"),
                reading(2, 600.0, 6000.0, 0.84, "0#")));

        OilWork t2 = byTank(end, 2);
        assertNull(t2.getBeginHeight());
        assertNull(t2.getBeginVolume());
        assertNull(t2.getShellVolume());
        assertNull(t2.getShellWeight());
        assertNull("开始时没有该罐，介质未知，不得用结束时的当前值补", t2.getJieZhi());
        assertEquals(600.0, t2.getEndHeight(), EPS);
        assertEquals(6000.0, t2.getEndVolume(), EPS);
    }

    @Test
    public void tankRemovedMidShiftKeepsBeginWithMissingEnd() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Arrays.asList(
                reading(1, 1500.0, 18000.0, 0.74, "92#"),
                reading(2, 600.0, 6000.0, 0.84, "0#")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Collections.singletonList(
                reading(1, 1400.0, 17000.0, 0.74, "92#")));

        assertEquals(2, end.size());
        OilWork t2 = byTank(end, 2);
        assertEquals(600.0, t2.getBeginHeight(), EPS);
        assertNull(t2.getEndHeight());
        assertNull(t2.getEndVolume());
        assertNull(t2.getShellVolume());
    }

    @Test
    public void endResultIsSortedByTankId() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Arrays.asList(
                reading(3, 1, 1, 1, "a"), reading(1, 1, 1, 1, "b")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Collections.singletonList(
                reading(2, 1, 1, 1, "c")));

        assertEquals(1, end.get(0).getOilId());
        assertEquals(2, end.get(1).getOilId());
        assertEquals(3, end.get(2).getOilId());
    }

    @Test
    public void outVolumeIsRoundedToTwoDecimalsLikeBefore() {
        List<OilWork> begin = ShiftRecordBuilder.begin(Collections.singletonList(
                reading(1, 100.0, 1000.123, 1.0, "92#")));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Collections.singletonList(
                reading(1, 90.0, 900.0, 1.0, "92#")));

        assertEquals(100.12, byTank(end, 1).getShellVolume(), EPS);
    }

    @Test
    public void legacyBeginJsonWithPrimitiveZerosStillParsesAndMatches() {
        // 升级前开始的班：info 里 end 字段是 0.0，且没有 jieZhi。
        String legacy = "[{\"oilId\":1,\"beginHeight\":1500.0,\"beginVolume\":18000.0,"
                + "\"endHeight\":0.0,\"endVolume\":0.0,\"shellVolume\":0.0,\"shellWeight\":0.0}]";
        List<OilWork> begin = Arrays.asList(new Gson().fromJson(legacy, OilWork[].class));

        List<OilWork> end = ShiftRecordBuilder.end(begin, Collections.singletonList(
                reading(1, 1400.0, 17000.0, 0.74, "92#")));

        OilWork t1 = byTank(end, 1);
        assertEquals(1000.0, t1.getShellVolume(), EPS);
        assertNull(t1.getJieZhi());
    }

    @Test
    public void nullBeginListIsTreatedAsNoBeginData() {
        List<OilWork> end = ShiftRecordBuilder.end(null, Collections.singletonList(
                reading(1, 1400.0, 17000.0, 0.74, "92#")));

        assertEquals(1, end.size());
        assertNull(end.get(0).getBeginHeight());
    }

    @Test
    public void displayOilTypePrefersRecordedValue() {
        assertEquals("92#", ShiftRecordBuilder.displayOilType("92#", "95#"));
        assertEquals("95#", ShiftRecordBuilder.displayOilType(null, "95#"));
        assertEquals("95#", ShiftRecordBuilder.displayOilType("", "95#"));
    }

    @Test
    public void formatMissingValueAsDash() {
        assertEquals("-", ShiftRecordBuilder.formatOrDash(null));
        assertTrue(ShiftRecordBuilder.formatOrDash(12.34).startsWith("12.3"));
    }
}
