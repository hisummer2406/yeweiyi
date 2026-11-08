package com.sansen.yeweiyi.logic.export

import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.bean.GuanBiaoData
import com.sansen.yeweiyi.bean.OilWork
import com.sansen.yeweiyi.db.Alarm
import com.sansen.yeweiyi.db.CommitWork
import com.sansen.yeweiyi.db.OilInOut
import com.sansen.yeweiyi.db.OutStock
import com.sansen.yeweiyi.db.StockSave
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 5 个记录页 + 罐表页导出：表头顺序 == 列表页布局表头（第一个 LinearLayout 里可见的 TextView），
 * 写出的 xls 首行就是这些列名，每行列数 == 表头列数。
 */
class RecordExportHeadersTest {

    private val tanks = object : TankLookup {
        override fun jieZhi(oilId: Int) = "介质"
        override fun density(oilId: Int) = 0.8
    }

    @Test
    fun alarmExportMatchesList() {
        verify(RecordExportColumns.ALARM, layoutHeader("fragment_recode_alarm"),
            Alarm().apply { guanHao = "1"; jieZhi = "j"; info = "i"; time = "t" })
    }

    @Test
    fun inStockExportMatchesList() {
        verify(RecordExportColumns.inStock(tanks), layoutHeader("fragment_recode_inout"), OilInOut().apply { oilId = 1; time = "t" })
    }

    @Test
    fun outStockExportMatchesList() {
        verify(RecordExportColumns.outStock(tanks), layoutHeader("fragment_recode_out"), OutStock().apply { oilId = 1; time = "t" })
    }

    @Test
    fun stockExportMatchesList() {
        verify(RecordExportColumns.stock(tanks), layoutHeader("fragment_recode_stock"), StockSave().apply { oilId = 1; time = "t" })
    }

    /** 交班导出每罐一行：交班列表表头去掉序号，再接交班详情页表头。 */
    @Test
    fun shiftExportMatchesListAndDetail() {
        val expected = layoutHeader("fragment_recode_commitwork").filter { it != "order_number" } +
            layoutHeader("fragment_recode_detailcommitwork")
        val shift = CommitWork().apply { time = "s"; endTime = "e"; info = "x" }
        verify(RecordExportColumns.shift(tanks), expected, ShiftTankRow(shift, OilWork().apply { oilId = 1 }))
    }

    /** 罐表页列表表头在页面中部（编号/高度/容积那一行），导出不含编号列。 */
    @Test
    fun oilTableExportMatchesList() {
        val expected = headerRowContaining("fragment_createtable", "bianhao").filter { it != "bianhao" }
        verify(RecordExportColumns.OIL_TABLE, expected, GuanBiaoData(0, 10f, 0.8))
    }

    private fun <T> verify(columns: List<ExportColumn<T>>, expectedKeys: List<String>, sample: T) {
        assertEquals("导出列名与列表表头不一致", expectedKeys, columns.map { stringName(it.titleRes) })
        val rows = RecordExportColumns.rows(columns, listOf(sample))

        val titles = expectedKeys.map { strings.getValue(it) }
        val out = ByteArrayOutputStream()
        ExcelRowsWriter.write(titles, rows, out)
        val sheet = HSSFWorkbook(ByteArrayInputStream(out.toByteArray())).getSheetAt(0)
        val headerRow = sheet.getRow(0)
        assertEquals("xls 首行列名", titles, (0 until headerRow.lastCellNum).map { headerRow.getCell(it).stringCellValue })
        rows.forEach { assertEquals("行列数与表头列数不一致", titles.size, it.size) }
        assertEquals(titles.size, sheet.getRow(1).lastCellNum.toInt())
    }

    companion object {
        private val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

        /** 默认语言 values/strings.xml：资源名 → 文案 */
        private val strings: Map<String, String> by lazy {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
        }

        private fun stringName(id: Int): String =
            R.string::class.java.fields.first { it.getInt(null) == id }.name

        /** 布局根节点下第一个 LinearLayout 即表头行，取其中可见 TextView 的 @string 名。 */
        private fun layoutHeader(layout: String): List<String> {
            val ns = "http://schemas.android.com/apk/res/android"
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(File(resDir, "layout/$layout.xml")).documentElement
            val header = root.childElements().first { it.tagName == "LinearLayout" }
            return header.childElements()
                .filter { it.tagName == "TextView" && it.getAttributeNS(ns, "visibility") != "gone" }
                .map { it.getAttributeNS(ns, "text").removePrefix("@string/") }
        }

        /** 含 @string/[key] 的 TextView 所在的那一行，取其中可见 TextView 的 @string 名。 */
        private fun headerRowContaining(layout: String, key: String): List<String> {
            val ns = "http://schemas.android.com/apk/res/android"
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(File(resDir, "layout/$layout.xml"))
            val nodes = doc.getElementsByTagName("TextView")
            val anchor = (0 until nodes.length).map { nodes.item(it) as Element }
                .first { it.getAttributeNS(ns, "text") == "@string/$key" }
            return (anchor.parentNode as Element).childElements()
                .filter { it.tagName == "TextView" && it.getAttributeNS(ns, "visibility") != "gone" }
                .map { it.getAttributeNS(ns, "text").removePrefix("@string/") }
        }

        private fun Element.childElements(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
    }
}
