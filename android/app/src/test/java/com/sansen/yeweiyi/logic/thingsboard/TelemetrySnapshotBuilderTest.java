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

public class TelemetrySnapshotBuilderTest {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @Test
    public void matchesFixturePayloadShape() {
        NetOilCan tank = new NetOilCan();
        tank.setId(1);
        tank.setOiltype("0#柴油");
        tank.setOilLevel(1234.5);
        tank.setWaterLevel(12.3);
        tank.setTemp(26.5);
        tank.setOilVolume(5000.0);
        tank.setWaterVolume(10.0);
        tank.setOilWeight(4200.0);
        tank.setConnect(true);
        tank.setProbeAddress("01");
        // alarmState 留空：对应 fixture 里的 null，代表当前无报警。

        List<NetOilCan> tanks = Collections.singletonList(tank);
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1735689600000L, "online");
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, 1735689600000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("telemetry_snapshot.json");
        assertEquals(expected, actual);
    }

    @Test
    public void unitsCoverEveryNumericTankField() {
        assertEquals("mm", TelemetrySnapshotBuilder.UNITS.get("oilLevel"));
        assertEquals("mm", TelemetrySnapshotBuilder.UNITS.get("waterLevel"));
        assertEquals("C", TelemetrySnapshotBuilder.UNITS.get("temp"));
        assertEquals("L", TelemetrySnapshotBuilder.UNITS.get("oilVolume"));
        assertEquals("L", TelemetrySnapshotBuilder.UNITS.get("waterVolume"));
        assertEquals("kg", TelemetrySnapshotBuilder.UNITS.get("oilWeight"));
    }
}
