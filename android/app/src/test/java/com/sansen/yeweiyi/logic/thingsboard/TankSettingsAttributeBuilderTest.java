package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.sansen.yeweiyi.db.OilCanSet;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** key 名和单位是本仓库自定的，云端尚未对齐，这里只校验取值来源与过滤规则。 */
public class TankSettingsAttributeBuilderTest {

    private static final TankSettingsAttributeBuilder.OilTypeNames NAMES = type -> "type" + type;

    private static OilCanSet tank(int pos, int enable) {
        OilCanSet set = new OilCanSet();
        set.setPk_oilCanId(pos);
        set.setEnable(enable);
        return set;
    }

    @Test
    public void mapsEverySettingsPageFieldOfEnabledTank() {
        OilCanSet set = tank(1, 1);
        set.setHeight(2000);
        set.setChuanGanQiHight(2.6f);
        set.setGunType(1); // 立罐
        set.setOilType(3);
        set.setHheightOilAlarm(1500);
        set.setHeightOilAlarm(600);
        set.setLowOilAlarm(100);
        set.setLlowOilAlarm(5);
        set.setHeightWaterAlarm(300);
        set.setHighTempAlarm(60);
        set.setLowTempAlarm(-10);
        set.setOilCorrection(1.5);
        set.setWaterCorrection(-0.5);
        set.setTempCorrection(0.2);
        set.setDensity(0.56);
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(set);

        Map<String, Object> a = TankSettingsAttributeBuilder.build(sets, NAMES);

        assertEquals(2000, a.get("tank1.tankHeightMm"));
        assertEquals("float 不能带出 2.5999999046325684 这种尾数", 2.6, a.get("tank1.sensorLengthMm"));
        assertEquals(1, a.get("tank1.tankShape"));
        assertEquals("type3", a.get("tank1.oilType"));
        assertEquals(1500, a.get("tank1.alarmHighHighLevelMm"));
        assertEquals(600, a.get("tank1.alarmHighLevelMm"));
        assertEquals(100, a.get("tank1.alarmLowLevelMm"));
        assertEquals(5, a.get("tank1.alarmLowLowLevelMm"));
        assertEquals(300, a.get("tank1.alarmHighWaterMm"));
        assertEquals(60, a.get("tank1.alarmHighTempC"));
        assertEquals(-10, a.get("tank1.alarmLowTempC"));
        assertEquals(1.5, a.get("tank1.levelCorrectionMm"));
        assertEquals(-0.5, a.get("tank1.waterCorrectionMm"));
        assertEquals(0.2, a.get("tank1.tempCorrectionC"));
        assertEquals(0.56, a.get("tank1.density"));
        assertEquals(15, a.size());
    }

    @Test
    public void skipsDisabledTanksAndUsesTankPositionInKey() {
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(tank(1, 0));
        sets.add(tank(3, 1));

        Map<String, Object> a = TankSettingsAttributeBuilder.build(sets, NAMES);

        assertFalse(a.containsKey("tank1.tankHeightMm"));
        assertTrue(a.containsKey("tank3.tankHeightMm"));
    }

    @Test
    public void nonFiniteValuesAreOmittedSoGsonDoesNotThrow() {
        OilCanSet set = tank(1, 1);
        set.setDensity(Double.NaN);
        set.setOilCorrection(Double.POSITIVE_INFINITY);
        List<OilCanSet> sets = new ArrayList<>();
        sets.add(set);

        Map<String, Object> a = TankSettingsAttributeBuilder.build(sets, NAMES);

        assertFalse(a.containsKey("tank1.density"));
        assertFalse(a.containsKey("tank1.levelCorrectionMm"));
        new Gson().toJson(a); // 默认 Gson 遇到 NaN/Infinity 会抛异常
    }

    @Test
    public void deviceSettingsCarryInOutThresholds() {
        Map<String, Object> a = DeviceSettingsAttributeBuilder.build(10, 60, 20, 120);
        assertEquals(10, a.get("inStockHeightThresholdMm"));
        assertEquals(60, a.get("inStockTimeThresholdSec"));
        assertEquals(20, a.get("outStockHeightThresholdMm"));
        assertEquals(120, a.get("outStockTimeThresholdSec"));
        assertEquals(4, a.size());
    }
}
