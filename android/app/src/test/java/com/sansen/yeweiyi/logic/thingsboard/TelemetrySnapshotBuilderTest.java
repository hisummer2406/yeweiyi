package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.NetOilCan;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TelemetrySnapshotBuilderTest {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

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
        tank.setOilRadio(80);
        tank.setWaterRadio(1);
        tank.setConnect(true);
        tank.setProbeAddress("01"); // 有意保留：验证 probeAddress 不出现在 telemetry 里
        return tank;
    }

    @Test
    public void matchesFixturePayloadShape() {
        List<NetOilCan> tanks = Collections.singletonList(fixtureTank());
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 7L);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, 1735689600000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("telemetry_snapshot.json");
        assertEquals(expected, actual);
    }

    @Test
    public void tankCountAndSnapshotSeqAreDeviceLevelKeys() {
        List<NetOilCan> tanks = Collections.singletonList(fixtureTank());
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 42L);

        assertEquals(1, values.get("tankCount"));
        assertEquals(42L, values.get("snapshotSeq"));
    }

    @Test
    public void doesNotEmitAlarmStateOrProbeAddress() {
        List<NetOilCan> tanks = Collections.singletonList(fixtureTank());
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, 1L);

        assertFalse(values.containsKey("tank1.alarmState"));
        assertFalse(values.containsKey("tank1.probeAddress"));
    }
}
