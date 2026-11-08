package com.sansen.yeweiyi.logic.oiltable

import com.sansen.yeweiyi.db.OilCanTable
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Sheet
import java.io.InputStream

/** 罐表 Excel 里的一个 sheet：A 列高度（毫米，向下取整）、B 列容积。 */
class OilTableSheet(val name: String, val heights: IntArray, val volumes: DoubleArray) {

    val isEmpty: Boolean get() = heights.isEmpty()

    fun toOilCanTable(tankNo: Int): OilCanTable = OilCanTable().apply {
        pk_oilCanId = tankNo
        height = heights
        volum = volumes
        ArrayToString()
    }
}

/** 多 sheet 罐表的导入结果：要么得到"罐号 → 罐表"，要么指出第一个名称不合规的 sheet。 */
sealed class MultiSheetAssignment {
    class Ok(val tanks: List<Pair<Int, OilTableSheet>>) : MultiSheetAssignment()
    class InvalidName(val sheetName: String) : MultiSheetAssignment()
}

/**
 * U 盘导入罐表（.xls，HSSF）。每个 sheet 的解析规则沿用原 ExportExcel.readExcelOilCanTableContent：
 * 第 0 行当表头跳过；数字单元格取值，文本能解析成数字就取，其余（含公式）当 0；高度、容积都为 0 的行跳过。
 * 多 sheet 时 sheet 名称即罐号（纯数字 1～[MAX_TANK_NO]，不能重复），空 sheet 跳过。
 */
object OilTableSheetReader {

    /** 数据库里建了 16 张罐表（DaoHelp.createOilCan(16)） */
    const val MAX_TANK_NO = 16

    @JvmStatic
    fun read(input: InputStream): List<OilTableSheet> =
        input.use {
            val workbook = HSSFWorkbook(POIFSFileSystem(it))
            (0 until workbook.numberOfSheets).map { i -> parseSheet(workbook.getSheetAt(i)) }
        }

    /** 多于一个 sheet 且至少一个有数据时走多罐导入；否则按原单表流程（预览后"复制到"）。 */
    @JvmStatic
    fun isMultiSheet(sheets: List<OilTableSheet>): Boolean = sheets.size > 1 && sheets.any { !it.isEmpty }

    /** 跳过空 sheet 后，按 sheet 名称对应罐号；任一名称不合规（非纯数字、超范围、重复）则整份不导入。 */
    @JvmStatic
    fun assignByName(sheets: List<OilTableSheet>): MultiSheetAssignment {
        val tanks = ArrayList<Pair<Int, OilTableSheet>>()
        for (sheet in sheets.filterNot { it.isEmpty }) {
            val no = tankNo(sheet.name)
            if (no == null || tanks.any { it.first == no }) return MultiSheetAssignment.InvalidName(sheet.name)
            tanks.add(no to sheet)
        }
        return MultiSheetAssignment.Ok(tanks)
    }

    /** 提示文案里的罐号列表，如 "1, 2, 3" */
    @JvmStatic
    fun joinTankNos(tanks: List<Pair<Int, OilTableSheet>>): String = tanks.joinToString(", ") { it.first.toString() }

    /** 日志用：各罐点数，如 "1:160,2:220" */
    @JvmStatic
    fun describePoints(tanks: List<Pair<Int, OilTableSheet>>): String =
        tanks.joinToString(",") { "${it.first}:${it.second.heights.size}" }

    /** 纯数字（允许首尾空格）且在 1～[MAX_TANK_NO] 内返回罐号，否则 null。 */
    @JvmStatic
    fun tankNo(sheetName: String): Int? {
        val text = sheetName.trim()
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        return text.toIntOrNull()?.takeIf { it in 1..MAX_TANK_NO }
    }

    private fun parseSheet(sheet: Sheet): OilTableSheet {
        val heights = ArrayList<Int>()
        val volumes = ArrayList<Double>()
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            val height = cellValue(row.getCell(0))
            val volume = cellValue(row.getCell(1))
            if (height == 0.0 && volume == 0.0) continue
            heights.add(Math.floor(height).toInt())
            volumes.add(volume)
        }
        return OilTableSheet(sheet.sheetName, heights.toIntArray(), volumes.toDoubleArray())
    }

    private fun cellValue(cell: Cell?): Double {
        if (cell == null) return 0.0
        return when (cell.cellType) {
            Cell.CELL_TYPE_NUMERIC -> cell.numericCellValue
            Cell.CELL_TYPE_STRING -> cell.stringCellValue.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
