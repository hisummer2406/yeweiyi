package com.sansen.yeweiyi.logic.oiltable

import com.sansen.yeweiyi.bean.GuanBiaoData
import com.sansen.yeweiyi.logic.export.ExcelRowsWriter
import com.sansen.yeweiyi.logic.export.RecordExportColumns
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OilTableSheetReaderTest {

    /** 生成 .xls：每个 sheet 第 0 行表头，其后每行 [高度, 容积]；值为 String 时写文本单元格 */
    private fun workbook(vararg sheets: Pair<String, List<List<Any?>>>): ByteArrayInputStream {
        val wb = HSSFWorkbook()
        for ((name, rows) in sheets) {
            val sheet = wb.createSheet(name)
            sheet.createRow(0).apply { createCell(0).setCellValue("毫米"); createCell(1).setCellValue("升数") }
            rows.forEachIndexed { i, values ->
                val row = sheet.createRow(i + 1)
                values.forEachIndexed { c, v ->
                    when (v) {
                        is Number -> row.createCell(c).setCellValue(v.toDouble())
                        is String -> row.createCell(c).setCellValue(v)
                    }
                }
            }
        }
        return ByteArrayInputStream(ByteArrayOutputStream().also { wb.write(it) }.toByteArray())
    }

    @Test
    fun readsEverySheetInsteadOfOnlyTheLast() {
        val sheets = OilTableSheetReader.read(workbook(
            "1" to listOf(listOf(10, 9), listOf(20, 24)),
            "2" to listOf(listOf(10, 10), listOf(20, 28), listOf(30, 50))))
        assertEquals(listOf("1", "2"), sheets.map { it.name })
        assertArrayEquals(intArrayOf(10, 20), sheets[0].heights)
        assertArrayEquals(doubleArrayOf(10.0, 28.0, 50.0), sheets[1].volumes, 0.0)
    }

    /** 与原 ExportExcel.readExcelOilCanTableContent 相同：跳表头、两列都为 0 跳过、文本按数字解析否则当 0、高度向下取整 */
    @Test
    fun keepsOriginalSheetParsingRules() {
        val sheet = OilTableSheetReader.read(workbook(
            "1" to listOf(listOf(0, 0), listOf(10.9, 5.5), listOf(" 20 ", "abc"), listOf(null, null)))).single()
        assertArrayEquals(intArrayOf(10, 20), sheet.heights)
        assertArrayEquals(doubleArrayOf(5.5, 0.0), sheet.volumes, 0.0)
    }

    @Test
    fun sheetNameIsTankNo() {
        val sheets = OilTableSheetReader.read(workbook(
            "3" to listOf(listOf(10, 1)), " 1 " to listOf(listOf(10, 2))))
        val ok = OilTableSheetReader.assignByName(sheets) as MultiSheetAssignment.Ok
        assertEquals(listOf(3, 1), ok.tanks.map { it.first })
        assertEquals("3, 1", OilTableSheetReader.joinTankNos(ok.tanks))
        assertEquals(1, ok.tanks[1].second.toOilCanTable(ok.tanks[1].first).pk_oilCanId)
    }

    @Test
    fun invalidNameRejectsWholeFile() {
        fun invalid(vararg names: String): String? {
            val sheets = OilTableSheetReader.read(workbook(*names.map { it to listOf(listOf<Any?>(10, 1)) }.toTypedArray()))
            return (OilTableSheetReader.assignByName(sheets) as? MultiSheetAssignment.InvalidName)?.sheetName
        }
        assertEquals("1号罐", invalid("1", "1号罐"))
        assertEquals("17", invalid("1", "17"))
        assertEquals("0", invalid("0", "1"))
        assertEquals("01", invalid("1", "01"))
        assertNull(invalid("1", "16"))
    }

    @Test
    fun emptySheetsAreSkippedBeforeNameCheck() {
        val sheets = OilTableSheetReader.read(workbook(
            "1" to listOf(listOf(10, 1)), "Sheet2" to emptyList(), "2" to listOf(listOf(0, 0))))
        assertTrue(OilTableSheetReader.isMultiSheet(sheets))
        val ok = OilTableSheetReader.assignByName(sheets) as MultiSheetAssignment.Ok
        assertEquals(listOf(1), ok.tanks.map { it.first })
    }

    @Test
    fun singleOrAllEmptySheetsKeepOldFlow() {
        assertFalse(OilTableSheetReader.isMultiSheet(OilTableSheetReader.read(workbook("Sheet1" to listOf(listOf(10, 1))))))
        assertFalse(OilTableSheetReader.isMultiSheet(OilTableSheetReader.read(workbook("a" to emptyList(), "b" to emptyList()))))
    }

    /** 罐表页"U盘导出"写出的文件，能被"U盘导入"按单 sheet 原样读回（表头行被跳过） */
    @Test
    fun exportedOilTableCanBeImportedBack() {
        val rows = listOf(GuanBiaoData(0, 10f, 0.8), GuanBiaoData(1, 20f, 2.4), GuanBiaoData(2, 900f, 741.4))
        val out = ByteArrayOutputStream()
        ExcelRowsWriter.write(listOf("高度(毫米)", "容积(升)"), RecordExportColumns.rows(RecordExportColumns.OIL_TABLE, rows), out)
        val sheets = OilTableSheetReader.read(ByteArrayInputStream(out.toByteArray()))
        assertFalse(OilTableSheetReader.isMultiSheet(sheets))
        assertArrayEquals(intArrayOf(10, 20, 900), sheets.single().heights)
        assertArrayEquals(doubleArrayOf(0.8, 2.4, 741.4), sheets.single().volumes, 0.0)
    }
}
