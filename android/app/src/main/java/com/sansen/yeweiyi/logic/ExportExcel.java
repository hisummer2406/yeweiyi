package com.sansen.yeweiyi.logic;

import android.util.Log;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.utils.SystemUtils;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

/**
 * @deprecated 原基于反射的通用导出已删除（记录页、罐表页导出改用 logic/export，罐表导入改用 logic/oiltable）。
 * 剩下的 exportOilCanTable 无调用方；DBDataManager/OilTankRepositoryImpl 里两处 new ExportExcel 也都在死代码中。
 */
@Deprecated
public class ExportExcel<T> {

    private static final String TAG = "ExportExcel";
    private POIFSFileSystem fs;
    private HSSFWorkbook wb;
    private HSSFSheet sheet;

    /**
     * 这是一个通用的方法，利用了JAVA的反射机制，可以将放置在JAVA集合中并且符号一定条件的数据以EXCEL 的形式输出到指定IO设备上
     *
     * @param title   表格标题名
     * @param headers 表格属性列名数组
     * @param dataset 需要显示的数据集合,集合中一定要放置符合javabean风格的类的对象。此方法支持的
     *                javabean属性的数据类型有基本数据类型及String,Date,byte[](图片数据)
     * @param out     与输出设备关联的流对象，可以将EXCEL文档导出到本地文件或者网络中
     * @param pattern 如果有时间数据，设定输出格式。默认为"yyy-MM-dd"
     */
    // [不可达-2026-09-21] 唯一调用方是 DBDataManager.SaveOilCanTable()（已 @Deprecated 且本身也是死路径，
    // 见那边的注释），西语翻译覆盖率核查时发现。方法体里 R.string.tank / R.string.tank_table_points
    // 这两个字符串资源不用优先翻。
    @SuppressWarnings("unchecked")
    public boolean exportOilCanTable(String title, String[] headers,
                                     List<OilCanTable> dataset, String[] params, OutputStream out,
                                     String pattern) {
        Class clazz = dataset.iterator().next().getClass();

        // 声明一个工作薄
        HSSFWorkbook workbook = new HSSFWorkbook();

        // 生成一个样式
        HSSFCellStyle style = workbook.createCellStyle();
        // 设置这些样式
        style.setFillForegroundColor(HSSFColor.SKY_BLUE.index);
        style.setFillPattern(HSSFCellStyle.SOLID_FOREGROUND);
        style.setBorderBottom(HSSFCellStyle.BORDER_THIN);
        style.setBorderLeft(HSSFCellStyle.BORDER_THIN);
        style.setBorderRight(HSSFCellStyle.BORDER_THIN);
        style.setBorderTop(HSSFCellStyle.BORDER_THIN);
        style.setAlignment(HSSFCellStyle.ALIGN_CENTER);
        // 生成一个字体
        HSSFFont font = workbook.createFont();
        font.setColor(HSSFColor.VIOLET.index);
        font.setFontHeightInPoints((short) 12);
        font.setBoldweight(HSSFFont.BOLDWEIGHT_BOLD);
        // 把字体应用到当前的样式
        style.setFont(font);
        // 生成并设置另一个样式
        HSSFCellStyle style2 = workbook.createCellStyle();
        style2.setFillForegroundColor(HSSFColor.LIGHT_YELLOW.index);
        style2.setFillPattern(HSSFCellStyle.SOLID_FOREGROUND);
        style2.setBorderBottom(HSSFCellStyle.BORDER_THIN);
        style2.setBorderLeft(HSSFCellStyle.BORDER_THIN);
        style2.setBorderRight(HSSFCellStyle.BORDER_THIN);
        style2.setBorderTop(HSSFCellStyle.BORDER_THIN);
        style2.setAlignment(HSSFCellStyle.ALIGN_CENTER);
        style2.setVerticalAlignment(HSSFCellStyle.VERTICAL_CENTER);
        // 生成另一个字体
        HSSFFont font2 = workbook.createFont();
        font2.setBoldweight(HSSFFont.BOLDWEIGHT_NORMAL);
        // 把字体应用到当前的样式
        style2.setFont(font2);

        int index = 0;

        for (OilCanTable table : dataset) {
            table.StringToArray();
            index = (table.getPk_oilCanId() - 1) * 3;

            // 生成一个表格
            // [已屏蔽-2026-09-23] tank 已删除（这个方法不可达，见上方注释），用空串占位保持编译。
            HSSFSheet sheet = workbook.createSheet(/* SystemUtils.getRsStr(R.string.tank) */ "" + table.getPk_oilCanId());
            // 设置表格默认列宽度为15个字节
            sheet.setDefaultColumnWidth(15);
            HSSFRow row = sheet.createRow(0);
            HSSFCell cell = row.createCell(0);
            cell.setCellStyle(style2);
            // [已屏蔽-2026-09-23] tank_table_points 已删除（不可达，见上方注释）。
            // cell.setCellValue(SystemUtils.getRsStr(R.string.tank_table_points));
            cell = row.createCell(1);
            cell.setCellStyle(style2);
            cell.setCellValue(table.getHeight().length);
            row = sheet.createRow(1);
            cell = row.createCell(0);
            cell.setCellStyle(style2);
            // [已屏蔽-2026-09-23] height 已删除（不可达，见上方注释）。
            // cell.setCellValue(SystemUtils.getRsStr(R.string.height));
            cell = row.createCell(1);
            cell.setCellStyle(style2);
            // [已屏蔽-2026-09-23] volume 已删除（不可达，见上方注释）。
            // cell.setCellValue(SystemUtils.getRsStr(R.string.volume));
            // i行 j列
            for (int i = 2; i < table.getHeight().length + 2; i++) {
                // 得到某一行
                row = sheet.createRow(i);

                for (int j = 0; j < 2; j++) {
                    // 得到某个具体的表格
                    cell = row.createCell(j);

                    if (j % 2 == 0) {
                        cell.setCellStyle(style2);
                        cell.setCellValue(table.getHeight()[i - 2] + "");

                    } else {
                        cell.setCellStyle(style2);
                        cell.setCellValue(table.getVolum()[i - 2] + "");

                    }

                }

            }
        }

        try {

            workbook.write(out);

            out.flush();
            System.out.println("写入成功");
        } catch (IOException e) {
            Log.e(TAG, "readExcelOilCanSetContent 执行失败", e);
        }
        return true;
    }
}
