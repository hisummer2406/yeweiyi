package com.sansen.yeweiyi.logic.export

import org.apache.poi.hssf.usermodel.HSSFCellStyle
import org.apache.poi.hssf.usermodel.HSSFFont
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.util.HSSFColor
import java.io.OutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 把表头 + 行数据写成 .xls。整数写整数单元格；小数保留 1 位（跟原 ExportExcel 一样用 DecimalFormat("0.0")，
 * 即 HALF_EVEN 取整，固定 Locale.US 小数点）；
 * 其余按文本写，null 和非有限数写空单元格。
 */
object ExcelRowsWriter {

    private const val SHEET_NAME = "Sheet1"

    @JvmStatic
    fun write(headers: List<String>, rows: List<List<Any?>>, out: OutputStream) {
        val workbook = HSSFWorkbook()
        val sheet = workbook.createSheet(SHEET_NAME)
        sheet.defaultColumnWidth = 15

        val headerStyle = workbook.createCellStyle().apply {
            bordered()
            fillForegroundColor = HSSFColor.SKY_BLUE.index
            fillPattern = HSSFCellStyle.SOLID_FOREGROUND
            setFont(workbook.createFont().apply {
                color = HSSFColor.VIOLET.index
                fontHeightInPoints = 12
                boldweight = HSSFFont.BOLDWEIGHT_BOLD
            })
        }
        val textStyle = workbook.createCellStyle().apply {
            bordered()
            fillForegroundColor = HSSFColor.LIGHT_YELLOW.index
            fillPattern = HSSFCellStyle.SOLID_FOREGROUND
            verticalAlignment = HSSFCellStyle.VERTICAL_CENTER
        }
        val format = workbook.createDataFormat()
        val integerStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(textStyle)
            dataFormat = format.getFormat("0")
        }
        val decimalStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(textStyle)
            dataFormat = format.getFormat("0.0")
        }

        val oneDecimal = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US))

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, title ->
            headerRow.createCell(i).apply {
                setCellStyle(headerStyle)
                setCellValue(title)
            }
        }
        rows.forEachIndexed { r, values ->
            val row = sheet.createRow(r + 1)
            values.forEachIndexed { i, value ->
                val cell = row.createCell(i)
                when (value) {
                    is Int, is Long, is Short, is Byte -> {
                        cell.setCellStyle(integerStyle)
                        cell.setCellValue((value as Number).toDouble())
                    }
                    is Double, is Float -> {
                        val d = (value as Number).toDouble()
                        if (d.isNaN() || d.isInfinite()) {
                            cell.setCellStyle(textStyle)
                        } else {
                            cell.setCellStyle(decimalStyle)
                            cell.setCellValue(oneDecimal.format(d).toDouble())
                        }
                    }
                    else -> {
                        cell.setCellStyle(textStyle)
                        cell.setCellValue(value?.toString() ?: "")
                    }
                }
            }
        }
        workbook.write(out)
        out.flush()
    }

    private fun HSSFCellStyle.bordered() {
        borderBottom = HSSFCellStyle.BORDER_THIN
        borderLeft = HSSFCellStyle.BORDER_THIN
        borderRight = HSSFCellStyle.BORDER_THIN
        borderTop = HSSFCellStyle.BORDER_THIN
        alignment = HSSFCellStyle.ALIGN_CENTER
    }
}
