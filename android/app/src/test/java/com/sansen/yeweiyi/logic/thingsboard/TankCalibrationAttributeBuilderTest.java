package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 这里只验证"扁平键+启用过滤+下标对应关系"这套已有约定被正确复用，期望值是自己构造的。
 * 与共享 fixture（{@code client_attributes.json}）的逐 key 比对在 {@link ProtocolV1ContractTest}。
 */
public class TankCalibrationAttributeBuilderTest {

    private static OilCanSet enabledSet(int pkOilCanId) {
        OilCanSet set = new OilCanSet();
        set.setPk_oilCanId(pkOilCanId);
        set.setEnable(1);
        return set;
    }

    private static OilCanSet disabledSet(int pkOilCanId) {
        OilCanSet set = new OilCanSet();
        set.setPk_oilCanId(pkOilCanId);
        set.setEnable(0);
        return set;
    }

    private static OilCanTable tableWith(String heights, String volumes) {
        OilCanTable table = new OilCanTable();
        table.setHeightstrarray(heights);
        table.setVolumstrarray(volumes);
        return table;
    }

    @Test
    public void mapsEnabledTankToItsOwnCalibrationTableByPosition() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(enabledSet(1));

        List<OilCanTable> tables = new ArrayList<>();
        tables.add(tableWith("10 20 30", "100.0 200.0 300.0"));

        Map<String, Object> attributes = TankCalibrationAttributeBuilder.build(sets, tables);

        assertArrayEquals(new int[]{10, 20, 30}, (int[]) attributes.get("tank1.calibrationHeightMm"));
        assertArrayEquals(new double[]{100.0, 200.0, 300.0},
                (double[]) attributes.get("tank1.calibrationVolumeL"), 0.0001);
    }

    @Test
    public void skipsDisabledTanks() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(disabledSet(1));

        List<OilCanTable> tables = new ArrayList<>();
        tables.add(tableWith("10 20", "100.0 200.0"));

        Map<String, Object> attributes = TankCalibrationAttributeBuilder.build(sets, tables);

        assertFalse(attributes.containsKey("tank1.calibrationHeightMm"));
    }

    @Test
    public void secondTankReadsSecondTableByOneBasedPosition() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(enabledSet(1));
        sets.add(enabledSet(2));

        List<OilCanTable> tables = new ArrayList<>();
        tables.add(tableWith("10 20", "100.0 200.0"));
        tables.add(tableWith("11 21", "110.0 210.0"));

        Map<String, Object> attributes = TankCalibrationAttributeBuilder.build(sets, tables);

        assertArrayEquals(new int[]{11, 21}, (int[]) attributes.get("tank2.calibrationHeightMm"));
        assertEquals(4, attributes.size());
    }

    @Test
    public void dropsNonFinitePointsTogetherWithTheirHeights() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(enabledSet(1));
        List<OilCanTable> tables = new ArrayList<>();
        // 真机 1 号罐罐表最后一点就是 900 → NaN
        tables.add(tableWith("880 890 900", "739.8 741.4 NaN"));

        Map<String, Object> attributes = TankCalibrationAttributeBuilder.build(sets, tables);

        assertArrayEquals(new int[]{880, 890}, (int[]) attributes.get("tank1.calibrationHeightMm"));
        assertArrayEquals(new double[]{739.8, 741.4},
                (double[]) attributes.get("tank1.calibrationVolumeL"), 0.0001);
    }

    @Test
    public void resultSerializesWithDefaultGsonEvenWhenTableHasNaN() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(enabledSet(1));
        List<OilCanTable> tables = new ArrayList<>();
        tables.add(tableWith("10 20 30", "NaN 200.0 Infinity"));

        // 默认 Gson 遇到 NaN/Infinity 会抛 IllegalArgumentException，真机正是因此崩溃
        String json = new com.google.gson.Gson().toJson(TankCalibrationAttributeBuilder.build(sets, tables));
        assertEquals("{\"tank1.calibrationHeightMm\":[20],\"tank1.calibrationVolumeL\":[200.0]}", json);
    }
}
