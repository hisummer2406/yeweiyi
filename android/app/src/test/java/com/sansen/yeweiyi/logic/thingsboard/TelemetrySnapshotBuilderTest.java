package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.NetOilCan;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class TelemetrySnapshotBuilderTest {

    private static NetOilCan fixtureTank() {
        NetOilCan tank = new NetOilCan();
        tank.setId(1);
        tank.setOiltype("0#柴油");
        tank.setOilLevel(1234.5);
        tank.setWaterLevel(12.3);
        tank.setTemp(26.5);
        tank.setOilVolume(5000.0);
        tank.setWaterVolume(10.0);
        tank.setOilWeight(4200.0);
        tank.setNullVolume(300.0);
        tank.setOilRatioPct(80.0);
        tank.setConnect(true);
        tank.setProbeAddress("01"); // 有意保留：验证 probeAddress 不出现在 telemetry 里
        return tank;
    }

    // 报文整体形状跟共享 fixture 的逐 key 比对在 ProtocolV1ContractTest，这里不重复。

    @Test
    public void heightRatioPctKeepsOneDecimalWithoutFloatDrift() {
        assertEquals(Double.valueOf(94.8), TelemetrySnapshotBuilder.heightRatioPct(948, 1000));
        assertEquals("跟液位一样向下截断，不四舍五入", Double.valueOf(33.3), TelemetrySnapshotBuilder.heightRatioPct(1, 3));
        assertEquals(Double.valueOf(20.1), TelemetrySnapshotBuilder.heightRatioPct(402.0, 2000));
    }

    @Test
    public void heightRatioPctClampsLikeTheLocalTankDrawing() {
        assertEquals("超过罐高按 100，跟本机 OilCan.oilRatio 一致", Double.valueOf(100.0),
                TelemetrySnapshotBuilder.heightRatioPct(2100, 2000));
        assertEquals(Double.valueOf(0.0), TelemetrySnapshotBuilder.heightRatioPct(-5, 2000));
    }

    @Test
    public void heightRatioPctIsNullWhenTankHeightNotConfigured() {
        assertNull(TelemetrySnapshotBuilder.heightRatioPct(1000, 0));
    }

    /** 异常读数不能抛异常，否则整轮快照（所有罐）都发不出去。 */
    @Test
    public void heightRatioPctIsNullForNonFiniteLevel() {
        assertNull(TelemetrySnapshotBuilder.heightRatioPct(Double.NaN, 2000));
        assertNull(TelemetrySnapshotBuilder.heightRatioPct(Double.POSITIVE_INFINITY, 2000));
    }

    @Test
    public void waterRatioPctIsNotReported() {
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(
                Collections.singletonList(fixtureTank()), 1735689600000L, 1L);

        assertEquals(80.0, values.get("tank1.oilRatioPct"));
        assertFalse("真实模式下 waterRatio 从未被计算，暂不上报", values.containsKey("tank1.waterRatioPct"));
    }

    @Test
    public void tankCountAndSnapshotSeqAreDeviceLevelKeys() {
        List<NetOilCan> tanks = Collections.singletonList(fixtureTank());
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 42L);

        assertEquals(1, values.get("tankCount"));
        assertEquals(42L, values.get("snapshotSeq"));
    }

    @Test
    public void tankCountIsMaxTankIdNotEnabledTankCountWhenIdsAreSparse() {
        NetOilCan tank1 = fixtureTank();
        tank1.setId(1);
        NetOilCan tank5 = fixtureTank();
        tank5.setId(5);
        List<NetOilCan> tanks = java.util.Arrays.asList(tank1, tank5);

        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 1L);

        assertEquals("只启用 1 号和 5 号罐时，tankCount 必须是 5（最大罐号）才能让消费方"
                + "按 1..tankCount 遍历时不漏掉 tank5，而不是启用罐的数量 2", 5, values.get("tankCount"));
        assertEquals(1234.5, values.get("tank5.oilLevelMm"));
    }

    @Test
    public void omitsAlarmStateWhenNotFilledAndNeverEmitsProbeAddress() {
        List<NetOilCan> tanks = Collections.singletonList(fixtureTank());
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 1L);

        assertFalse("没有填 alarmState 时不能编造一个 NORMAL", values.containsKey("tank1.alarmState"));
        assertFalse(values.containsKey("tank1.probeAddress"));
    }

    @Test
    public void emitsAlarmStateWhenFilled() {
        NetOilCan tank = fixtureTank();
        tank.setAlarmState("ALARM");
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(
                Collections.singletonList(tank), 1735689600000L, 1L);

        assertEquals("ALARM", values.get("tank1.alarmState"));
    }
}
