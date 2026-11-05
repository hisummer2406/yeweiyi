package com.sansen.yeweiyi.logic.thingsboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.logic.shift.ShiftRecordBuilder;
import com.sansen.yeweiyi.logic.shift.TankReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** shiftEvent 报文（zhongchengyeweiyi#49 / cloud#85 契约 §2.1），与设备端手写 fixture 逐 key 比对。 */
public class ShiftEventMapperTest {

    private static final Gson GSON = new Gson();
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long BEGIN_MS = 1762146400000L; // 2025-11-03 13:06:40 +08
    private static final long END_MS = 1762175200000L;   // 2025-11-03 21:06:40 +08

    private static TankReading t1Begin() {
        return new TankReading(1, 1520.0, 18230.5, 0.74, "92#", 5);
    }

    private static TankReading t2Begin() {
        return new TankReading(2, 980.0, 11020.0, 0.84, "0#", 1);
    }

    private static TankReading t1End() {
        return new TankReading(1, 1310.0, 15710.5, 0.74, "92#", 5);
    }

    private static TankReading t2End() {
        return new TankReading(2, 905.0, 10180.0, 0.84, "0#", 1);
    }

    private static CommitWork openRow(List<OilWork> begin) {
        CommitWork row = new CommitWork();
        row.setId(12);
        row.setTime("2025-11-03 13:06:40");
        row.setBeginTimeMs(BEGIN_MS);
        row.setInfo(GSON.toJson(begin));
        return row;
    }

    private static CommitWork closedRow(List<OilWork> begin, List<TankReading> endReadings) {
        CommitWork row = openRow(begin);
        row.setEndTime("2025-11-03 21:06:40");
        row.setEndTimeMs(END_MS);
        row.setInfo(GSON.toJson(ShiftRecordBuilder.end(begin, endReadings)));
        return row;
    }

    private static JsonObject telemetry(CommitWork row, long ts) {
        Map<String, Object> event = ShiftEventMapper.build(row, SHANGHAI);
        return GSON.toJsonTree(ShiftEventMapper.wrapAsTelemetry(event, ts)).getAsJsonObject();
    }

    @Test
    public void openShiftMatchesFixture() {
        CommitWork row = openRow(ShiftRecordBuilder.begin(Arrays.asList(t1Begin(), t2Begin())));
        assertEquals(FixtureLoader.loadProtocolV1("shift_event_open.json"), telemetry(row, BEGIN_MS));
    }

    @Test
    public void closedShiftMatchesFixture() {
        CommitWork row = closedRow(ShiftRecordBuilder.begin(Arrays.asList(t1Begin(), t2Begin())),
                Arrays.asList(t1End(), t2End()));
        assertEquals(FixtureLoader.loadProtocolV1("shift_event_closed.json"), telemetry(row, END_MS));
    }

    @Test
    public void tankAddedMidShiftMatchesFixture() {
        CommitWork row = closedRow(ShiftRecordBuilder.begin(Collections.singletonList(t1Begin())),
                Arrays.asList(t1End(), new TankReading(3, 600.0, 6000.0, 0.84, "0#", 1)));
        assertEquals(FixtureLoader.loadProtocolV1("shift_event_tank_added.json"), telemetry(row, END_MS));
    }

    @Test
    public void tankRemovedMidShiftMatchesFixture() {
        CommitWork row = closedRow(ShiftRecordBuilder.begin(Arrays.asList(t1Begin(), t2Begin())),
                Collections.singletonList(t1End()));
        assertEquals(FixtureLoader.loadProtocolV1("shift_event_tank_removed.json"), telemetry(row, END_MS));
    }

    @Test
    public void legacyOpenRowWithZeroEndFieldsReportsThemAsMissing() {
        CommitWork row = new CommitWork();
        row.setTime("2025-11-03 13:06");
        row.setInfo("[{\"oilId\":1,\"beginHeight\":1500.0,\"beginVolume\":18000.0,"
                + "\"endHeight\":0.0,\"endVolume\":0.0,\"shellVolume\":0.0,\"shellWeight\":0.0}]");

        JsonObject tank = GSON.toJsonTree(ShiftEventMapper.build(row, SHANGHAI)).getAsJsonObject()
                .getAsJsonArray("tanks").get(0).getAsJsonObject();

        assertEquals(1500.0, tank.get("beginLevelMm").getAsDouble(), 0);
        assertFalse(tank.has("endLevelMm"));
        assertFalse(tank.has("endVolumeL"));
        assertFalse(tank.has("outVolumeL"));
        assertFalse(tank.has("outWeightKg"));
    }

    @Test
    public void rowWithoutEpochColumnsFallsBackToLocalTimeStrings() {
        // 升级前开始、升级后结束的班：begintimems/endtimems 为空，按设备时区解析时间字符串（开始只到分钟）。
        CommitWork row = new CommitWork();
        row.setTime("2025-11-03 13:06");
        row.setEndTime("2025-11-03 21:06:40");
        row.setInfo("[]");

        Map<String, Object> event = ShiftEventMapper.build(row, SHANGHAI);

        assertEquals("shift-1762146360000", event.get("shiftId"));
        assertEquals(1762146360000L, event.get("beginTimeMs"));
        assertEquals(END_MS, event.get("endTimeMs"));
        assertEquals("CLOSED", event.get("status"));
    }

    @Test
    public void epochColumnWinsOverTimeStringSoTimeZoneChangesDoNotMoveShiftId() {
        CommitWork row = openRow(Collections.<OilWork>emptyList());

        Map<String, Object> event = ShiftEventMapper.build(row, TimeZone.getTimeZone("UTC"));

        assertEquals("shift-" + BEGIN_MS, event.get("shiftId"));
    }

    @Test
    public void unparsableBeginTimeYieldsNoEvent() {
        CommitWork row = new CommitWork();
        row.setTime("garbage");
        row.setInfo("[]");
        assertNull(ShiftEventMapper.build(row, SHANGHAI));
    }
}
