package com.sansen.yeweiyi.logic.shift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 交班记录 Excel 导出的行组装：每罐一行、不重复，罐已删除时不崩。 */
public class ShiftExportRowsTest {

    private static CommitWork row(String time, String endTime, String info) {
        CommitWork w = new CommitWork();
        w.setTime(time);
        w.setEndTime(endTime);
        w.setInfo(info);
        return w;
    }

    private static final String TWO_TANKS = "[{\"oilId\":1,\"beginHeight\":1.0},{\"oilId\":2,\"beginHeight\":2.0}]";

    @Test
    public void eachTankOfEachShiftAppearsExactlyOnce() {
        List<OilWork> rows = ShiftExportRows.build(Arrays.asList(
                row("2025-11-03 08:00:00", "2025-11-03 16:00:00", TWO_TANKS),
                row("2025-11-03 16:00:00", null, "[{\"oilId\":1}]")), tank -> "92#");

        assertEquals(3, rows.size());
    }

    @Test
    public void rowsCarryTheShiftsStartAndEndTime() {
        List<OilWork> rows = ShiftExportRows.build(Collections.singletonList(
                row("2025-11-03 08:00:00", "2025-11-03 16:00:00", TWO_TANKS)), tank -> "92#");

        assertEquals("2025-11-03 08:00:00", rows.get(1).getTime());
        assertEquals("2025-11-03 16:00:00", rows.get(1).getEndTime());
    }

    @Test
    public void recordedOilTypeWinsOverCurrentSetting() {
        List<OilWork> rows = ShiftExportRows.build(Collections.singletonList(
                row("t", null, "[{\"oilId\":1,\"jieZhi\":\"0#\"}]")), tank -> "92#");

        assertEquals("0#", rows.get(0).getJieZhi());
    }

    @Test
    public void deletedTankWithoutRecordedOilTypeExportsBlankInsteadOfCrashing() {
        List<OilWork> rows = ShiftExportRows.build(Collections.singletonList(
                row("t", null, "[{\"oilId\":9}]")), tank -> null);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getJieZhi());
    }

    @Test
    public void shiftWithEmptyOrBrokenInfoIsSkipped() {
        List<OilWork> rows = ShiftExportRows.build(Arrays.asList(
                row("t", null, null), row("t", null, "not json")), tank -> "92#");

        assertEquals(0, rows.size());
    }
}
