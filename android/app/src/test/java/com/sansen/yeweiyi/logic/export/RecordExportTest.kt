package com.sansen.yeweiyi.logic.export

import com.sansen.yeweiyi.bean.OilWork
import com.sansen.yeweiyi.db.Alarm
import com.sansen.yeweiyi.db.CommitWork
import com.sansen.yeweiyi.db.OilInOut
import com.sansen.yeweiyi.db.OutStock
import com.sansen.yeweiyi.db.StockSave
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RecordExportTest {

    private val tanks = object : TankLookup {
        override fun jieZhi(oilId: Int) = "介质$oilId"
        override fun density(oilId: Int) = 0.8
    }

    @Test
    fun alarmRowFollowsListColumnOrder() {
        val a = Alarm().apply { guanHao = "2"; jieZhi = "柴油"; info = "高液位报警"; time = "2026-10-31 10:00:00" }
        assertEquals(listOf("2", "柴油", "高液位报警", "2026-10-31 10:00:00"), RecordExportColumns.rows(RecordExportColumns.ALARM, listOf(a))[0])
    }

    @Test
    fun inStockRowComputesAmountLikeAdapter() {
        val r = OilInOut().apply {
            oilId = 1; time = "t"; beginHeigth = 100.0; endHeigth = 300.0; beginVolum = 1000.0; endVolum = 3500.0
            beginWeight = 999.0; endWeight = 999.0 // 质量快照不参与导出
        }
        assertEquals(listOf<Any?>(1, "介质1", "t", 100.0, 1000.0, 300.0, 3500.0, 2500.0, 2000.0),
            RecordExportColumns.rows(RecordExportColumns.inStock(tanks), listOf(r))[0])
    }

    @Test
    fun outStockAmountIsBeginMinusEnd() {
        val r = OutStock().apply { oilId = 3; time = "t"; beginHeigth = 300.0; endHeigth = 100.0; beginVolum = 3500.0; endVolum = 1000.0 }
        assertEquals(listOf<Any?>(3, "介质3", "t", 300.0, 3500.0, 100.0, 1000.0, 2500.0, 2000.0),
            RecordExportColumns.rows(RecordExportColumns.outStock(tanks), listOf(r))[0])
    }

    @Test
    fun stockRowFollowsListColumnOrder() {
        val s = StockSave().apply { oilId = 1; time = "t"; height = 1.0; volum = 2.0; waterheigth = 3.0; temperature = 4.0; weight = 5.0 }
        assertEquals(listOf<Any?>(1, "介质1", "t", 1.0, 2.0, 3.0, 4.0, 5.0), RecordExportColumns.rows(RecordExportColumns.stock(tanks), listOf(s))[0])
    }

    @Test
    fun shiftRowsOnePerTankWithoutDuplicates() {
        val shift = CommitWork().apply { time = "s"; endTime = "e"; info = "x" }
        val works = listOf(1, 2).map { id ->
            OilWork().apply { oilId = id; beginHeight = 1.0; beginVolume = 2.0; endHeight = 3.0; endVolume = 4.0; shellVolume = 5.0; shellWeight = 6.0 }
        }
        val rows = RecordExportColumns.rows(RecordExportColumns.shift(tanks), RecordExportColumns.shiftTankRows(listOf(shift)) { works })
        assertEquals(2, rows.size)
        assertEquals(listOf<Any?>("s", "e", 2, "介质2", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0), rows[1])
    }

    @Test
    fun writerKeepsIntegersAndRoundsDecimalsToOnePlace() {
        val out = ByteArrayOutputStream()
        ExcelRowsWriter.write(listOf("A", "B", "C", "D"), listOf(listOf(7, 12.34, "x", Double.NaN)), out)
        val sheet = HSSFWorkbook(ByteArrayInputStream(out.toByteArray())).getSheetAt(0)
        assertEquals("A", sheet.getRow(0).getCell(0).stringCellValue)
        val row = sheet.getRow(1)
        assertEquals(7.0, row.getCell(0).numericCellValue, 0.0)
        assertEquals(12.3, row.getCell(1).numericCellValue, 0.0)
        assertEquals("x", row.getCell(2).stringCellValue)
        assertEquals(Cell.CELL_TYPE_BLANK, row.getCell(3).cellType)
    }
}
