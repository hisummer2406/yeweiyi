package com.sansen.yeweiyi.logic;

import android.util.Log;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.Oil;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.listener.ExcelListener;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.utils.SystemUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFComment;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 利用开源组件POI3.0.2动态导出EXCEL文档 转载时请保留以下信息，注明出处！
 *
 * @param <T> 应用泛型，代表任意一个符合javabean风格的类
 *            注意这里为了简单起见，boolean型的属性xxx的get器方式为getXxx(),而不是isXxx()
 *            byte[]表jpg格式的图片数据
 * @author leno
 * @version v1.0
 */
public class ExportExcel<T> {

    private static final String TAG = "ExportExcel";
    private POIFSFileSystem fs;
    private HSSFWorkbook wb;
    private HSSFSheet sheet;

    public void exportExcel(String[] headers, Collection<T> dataset,
                            String[] params, OutputStream out, ExcelListener listener) {
        exportExcel("测试POI导出EXCEL文档", headers, dataset, params, out,
                "yyyy-MM-dd",listener);
    }

    public void exportExcel(String[] headers, Collection<T> dataset,
                            String[] params, OutputStream out, String pattern,ExcelListener listener) {
        exportExcel("测试POI导出EXCEL文档", headers, dataset, params, out, pattern,listener);
    }

    /**
     * 读取探针对照表中的数据内容
     *
     * @return Map 包含单元格数据内容的Map对象
     */
    public List<ProbeToOil> readExcelProbeToOilCanContent(InputStream is) {

        List<ProbeToOil> list = new ArrayList<ProbeToOil>();
        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelProbeToOilCanContent 执行失败", e);
        }
        sheet = wb.getSheetAt(0);
        // 得到总行数
        int rowNum = sheet.getLastRowNum();

        HSSFRow row = sheet.getRow(0);
        int colNum = row.getPhysicalNumberOfCells();

        ProbeToOil probeToOil;
        // 正文内容应该从第二行开始,第一行为表头的标题
        for (int i = 1; i <= rowNum; i++) {
            row = sheet.getRow(i);
            int j = 0;
            probeToOil = new ProbeToOil();
            while (j < colNum) {

                switch (j) {

                    case 1:
                        probeToOil.setPk_oilCanId(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 4:
                        probeToOil.setProbeId(getValue(row.getCell(j)));
                        break;
                    case 5:
                        probeToOil.setProbeType(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 6:
                        probeToOil.setEnable(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    default:
                        break;
                }
                j++;
            }
            list.add(probeToOil);
        }
        return list;
    }

    /**
     * 读取`罐表中的数据内容
     *
     * @return Map 包含单元格数据内容的Map对象
     * @p aram InputStream
     */
    public OilCanTable readExcelOilCanTableContent(InputStream is) throws IOException {
        OilCanTable oilCanTables = new OilCanTable();
        POIFSFileSystem fs = null;
        HSSFWorkbook wb = null;
        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
            // 遍历所有sheet（一个sheet=一个油罐）
            for (int sheetIndex = 0; sheetIndex < wb.getNumberOfSheets(); sheetIndex++) {
                HSSFSheet sheet = wb.getSheetAt(sheetIndex);
                List<Double> heightTemp = new ArrayList<>();
                List<Double> volumeTemp = new ArrayList<>();

                // 遍历所有数据行，跳过第0行表头
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    HSSFRow row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    // A列：高度
                    Cell heightCell = row.getCell(0);
                    // B列：容积
                    Cell volCell = row.getCell(1);

                    double height = getCellDoubleValue(heightCell);
                    double volume = getCellDoubleValue(volCell);
                    // 过滤空行（高度容积都为0跳过）
                    if (height == 0 && volume == 0) continue;

                    heightTemp.add(height);
                    volumeTemp.add(volume);
                    Log.e("Row"," height "+height+"volume "+volCell);
                }

                // 集合转数组存入实体
                int[] heightArr = new int[heightTemp.size()];
                double[] volArr = new double[volumeTemp.size()];
                for (int i = 0; i < heightArr.length; i++) {
                    heightArr[i] =(int) Math.floor(heightTemp.get(i));
                    volArr[i] = volumeTemp.get(i);
                }

              OilCanTable oilCanTable = new OilCanTable();
              oilCanTable.setPk_oilCanId(sheetIndex + 1);
              oilCanTable.setHeight(heightArr);
              oilCanTable.setVolum(volArr);
                oilCanTable.ArrayToString();
             oilCanTables=oilCanTable;
            }
        } finally {
//            if (wb != null) wb.clo();
//            if (fs != null) fs.close();
            if (is != null) is.close();
        }
        return oilCanTables;
    }

    /**
     * 通用单元格转浮点值（兼容数字、文本空单元格）
     */
    private double getCellDoubleValue(Cell cell) {
        if (cell == null) return 0.0;
        switch (cell.getCellType()) {
            case Cell.CELL_TYPE_NUMERIC:
                return cell.getNumericCellValue();
            case Cell.CELL_TYPE_STRING:
                String val = cell.getStringCellValue().trim();
                if (val.isEmpty()) return 0.0;
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            case Cell.CELL_TYPE_BLANK:
            default:
                return 0.0;
        }
    }
    public String getValue(HSSFCell cell) {
        String cellValue = "";
        DecimalFormat df = new DecimalFormat("#");
        switch (cell.getCellType()) {
            case HSSFCell.CELL_TYPE_STRING:
                cellValue = cell.getRichStringCellValue().getString().trim();
                break;
            case HSSFCell.CELL_TYPE_NUMERIC:
                cellValue = df.format(cell.getNumericCellValue()).toString();
                break;
            case HSSFCell.CELL_TYPE_BOOLEAN:
                cellValue = String.valueOf(cell.getBooleanCellValue()).trim();
                break;
            case HSSFCell.CELL_TYPE_FORMULA:
                cellValue = cell.getCellFormula();
                break;
            default:
                cellValue = "";
        }
        return cellValue;
    }

    /**
     * 读取探针对照表中的数据内容
     *
     * @return Map 包含单元格数据内容的Map对象
     */
    public List<OilCanSet> readExcelOilCanSetContent(InputStream is) {
        // System.out.println("读取Excel  开始读取了");
        List<OilCanSet> list = new ArrayList<OilCanSet>();
        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelOilCanSetContent 执行失败", e);
        }
        sheet = wb.getSheetAt(0);
        // 得到总行数
        int rowNum = sheet.getLastRowNum();

        HSSFRow row = sheet.getRow(0);
        int colNum = row.getPhysicalNumberOfCells();

        OilCanSet oilCanSet;
        // 正文内容应该从第二行开始,第一行为表头的标题
        for (int i = 1; i <= rowNum; i++) {
            row = sheet.getRow(i);
            int j = 0;
            oilCanSet = new OilCanSet();
            while (j < colNum) {

                switch (j) {

                    case 3:
                        oilCanSet.setPk_oilCanId(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 4:
                        oilCanSet.setHeight(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 5:
                        oilCanSet.setMaxVolume(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 6:
                        oilCanSet.setHeightOilAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 7:
                        oilCanSet.setHheightOilAlarm(Integer.parseInt(getValue(row.getCell(j))));
                        break;
                    case 8:
                        oilCanSet.setLowOilAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 9:
                        oilCanSet.setLlowOilAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 10:
                        oilCanSet.setHeightWaterAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 11:
                        oilCanSet.setHighTempAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 12:
                        oilCanSet.setLowTempAlarm(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 13:
                        oilCanSet.setOilCorrection(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 14:
                        oilCanSet.setWaterCorrection(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 15:
                        oilCanSet.setTempCorrection(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 16:
                        oilCanSet.setDensity(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 17:
                        oilCanSet.setDensVar(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 18:
                        oilCanSet.setAlarmByte(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 19:
                        oilCanSet.setOilType(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 20:
                        oilCanSet.setOilColor(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;

                    default:
                        break;
                }
                j++;
            }
            list.add(oilCanSet);
        }
        return list;
    }

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
    @SuppressWarnings("unchecked")
    public void exportExcel(String title, String[] headers,
                            Collection<T> dataset, String[] params, OutputStream out,
                            String pattern,ExcelListener listener) {

        // 声明一个工作薄
        HSSFWorkbook workbook = new HSSFWorkbook();
        // 生成一个表格
        HSSFSheet sheet = workbook.createSheet(title);
        // 设置表格默认列宽度为15个字节
        sheet.setDefaultColumnWidth(15);
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

        // 声明一个画图的顶级管理器
        HSSFPatriarch patriarch = sheet.createDrawingPatriarch();
        // 定义注释的大小和位置,详见文档
        HSSFComment comment = patriarch.createComment(new HSSFClientAnchor(0,
                0, 0, 0, (short) 4, 2, (short) 6, 5));
        // 设置注释内容
        comment.setString(new HSSFRichTextString("可以在POI中添加注释！"));
        // 设置注释作者，当鼠标移动到单元格上是可以在状态栏中看到该内容.
        comment.setAuthor("leno");

        // 产生表格标题行
        HSSFRow row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            HSSFCell cell = row.createCell(i);
            cell.setCellStyle(style);
            HSSFRichTextString text = new HSSFRichTextString(headers[i]);
            cell.setCellValue(text);
        }

        // 遍历集合数据，产生数据行
        Iterator<T> it = dataset.iterator();
        int index = 0;
        while (it.hasNext()) {
            index++;
            row = sheet.createRow(index);
            T t = (T) it.next();
            // 利用反射，根据javabean属性的先后顺序，动态调用getXxx()方法得到属性值
            Field[] fields = t.getClass().getDeclaredFields();
            System.out.println("方法多少  " + fields.length);

            for (Field field : fields) {
                System.out.println("方法名字 ==  " + field.getName());
            }
            for (int i = 0; i < fields.length; i++) {
                HSSFCell cell = row.createCell(i);
                cell.setCellStyle(style2);
                Field field = fields[i];

                String fieldName = params[i];
                String getMethodName = "get"
                        + fieldName.substring(0, 1).toUpperCase()
                        + fieldName.substring(1);
                System.out.println(" 方法"+getMethodName);
                if (!getMethodName.equals("getId")){
                    try {
                        Class tCls = t.getClass();
                        Method getMethod = tCls.getMethod(getMethodName,
                                new Class[]{});

                        Object value = getMethod.invoke(t, new Object[]{});
                        System.out.println("里面的数据" + value+" 方法"+getMethodName);
                        // 判断值的类型后进行强制类型转换
                        String textValue = null;
                        // if (value instanceof Integer) {
                        // int intValue = (Integer) value;
                        // cell.setCellValue(intValue);
                        // } else if (value instanceof Float) {
                        // float fValue = (Float) value;
                        // textValue = new HSSFRichTextString(
                        // String.valueOf(fValue));
                        // cell.setCellValue(textValue);
                        // } else if (value instanceof Double) {
                        // double dValue = (Double) value;
                        // textValue = new HSSFRichTextString(
                        // String.valueOf(dValue));
                        // cell.setCellValue(textValue);
                        // } else if (value instanceof Long) {
                        // long longValue = (Long) value;
                        // cell.setCellValue(longValue);
                        // }
                        if (value instanceof Boolean) {
                            boolean bValue = (Boolean) value;
                            textValue = "男";
                            if (!bValue) {
                                textValue = "女";
                            }
                        } else if (value instanceof Date) {
                            Date date = (Date) value;
                            SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                            textValue = sdf.format(date);
                        } else if (value instanceof byte[]) {
                            // 有图片时，设置行高为60px;
                            row.setHeightInPoints(60);
                            // 设置图片所在列宽度为80px,注意这里单位的一个换算
                            sheet.setColumnWidth(i, (short) (35.7 * 80));
                            // sheet.autoSizeColumn(i);
                            byte[] bsValue = (byte[]) value;
                            HSSFClientAnchor anchor = new HSSFClientAnchor(0, 0,
                                    1023, 255, (short) 6, index, (short) 6, index);
                            anchor.setAnchorType(2);
                            patriarch.createPicture(anchor, workbook.addPicture(
                                    bsValue, HSSFWorkbook.PICTURE_TYPE_JPEG));
                        } else {
                            // 其它数据类型都当作字符串简单处理
                            if (value==null){
                                value="";
                            }
                            textValue = value.toString();
                        }
                        // 如果不是图片数据，就利用正则表达式判断textValue是否全部由数字组成
                        if (textValue != null) {
                            Pattern p = Pattern.compile("^//d+(//.//d+)?$");
                            Matcher matcher = p.matcher(textValue);
                            if (matcher.matches()) {

                                // 是数字当作double处理
                                cell.setCellValue(Double.parseDouble(textValue));
                            } else {
                                HSSFRichTextString richString = new HSSFRichTextString(
                                        textValue);
                                HSSFFont font3 = workbook.createFont();
                                font3.setColor(HSSFColor.BLUE.index);
                                richString.applyFont(font3);

                                cell.setCellValue(richString);
                            }
                        }
                    } catch (SecurityException e) {
                        listener.Success(false);
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    } catch (NoSuchMethodException e) {
                        listener.Success(false);
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    } catch (IllegalArgumentException e) {
                        listener.Success(false);
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        listener.Success(false);
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        listener.Success(false);
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    } finally {

                        // 清理资源
                    }
                }

            }

        }
        try {
            workbook.write(out);
            out.flush();
            listener.Success(true);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            listener.Success(false);
            //e.printStackTrace();
        }

    }

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
            HSSFSheet sheet = workbook.createSheet(SystemUtils.getRsStr(R.string.tank) + table.getPk_oilCanId());
            // 设置表格默认列宽度为15个字节
            sheet.setDefaultColumnWidth(15);
            HSSFRow row = sheet.createRow(0);
            HSSFCell cell = row.createCell(0);
            cell.setCellStyle(style2);
            cell.setCellValue(SystemUtils.getRsStr(R.string.tank_table_points));
            cell = row.createCell(1);
            cell.setCellStyle(style2);
            cell.setCellValue(table.getHeight().length);
            row = sheet.createRow(1);
            cell = row.createCell(0);
            cell.setCellStyle(style2);
            cell.setCellValue(SystemUtils.getRsStr(R.string.height));
            cell = row.createCell(1);
            cell.setCellStyle(style2);
            cell.setCellValue(SystemUtils.getRsStr(R.string.volume));
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

    /**
     * 这是一个通用的方法，利用了JAVA的反射机制，可以将放置在JAVA集合中并且符号一定条件的数据以EXCEL 的形式输出到指定IO设备上
     *
     * @param
     * @param headers 表格属性列名数组
     * @param dataset 需要显示的数据集合,集合中一定要放置符合javabean风格的类的对象。此方法支持的
     *                javabean属性的数据类型有基本数据类型及String,Date,byte[](图片数据)
     * @param out     与输出设备关联的流对象，可以将EXCEL文档导出到本地文件或者网络中
     * @param pattern 如果有时间数据，设定输出格式。默认为"yyy-MM-dd"
     */
    @SuppressWarnings("unchecked")
    public boolean exportCommitWork(String[] headers,
                                    List<CommitWork> dataset, OutputStream out,
                                    String pattern) {

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
        // 生成一个表格
        int index = 0;
        sheet = workbook.createSheet();

        // 产生表格标题行
        HSSFRow row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            HSSFCell cell = row.createCell(i);
            cell.setCellStyle(style);
            HSSFRichTextString text = new HSSFRichTextString(headers[i]);
            cell.setCellValue(text);
        }
        int rowNum = 1;
        Cell cell;
        for (CommitWork commitWork : dataset) {
            // 如果油罐ID大于1，那么就不显示

            String time = commitWork.getTime();
            String user = commitWork.getEndTime();
            List<OilWork> oilWorks = DBDataManager.stringToArray(
                    commitWork.getInfo(), OilWork[].class);
            for (OilWork oilWork : oilWorks) {
                row = sheet.createRow(rowNum++);
                System.out.println(oilWork);
                for (int celNum = 0; celNum < headers.length; celNum++) {
                    switch (celNum) {
                        case 0:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            if (oilWork.getOilId() < 2) {
                                cell.setCellValue(time);
                            } else {
                                cell.setCellValue("");
                            }
                            break;
                        case 1:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            if (oilWork.getOilId() < 2) {
                                cell.setCellValue(user);
                            } else {
                                cell.setCellValue("");
                            }
                            break;
                        case 2:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    "NO." + oilWork.getOilId());
                            break;
                        case 3:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oilWork.getBeginHeight());
                            break;
                        case 4:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oilWork.getEndHeight());
                            break;
                        case 5:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oilWork.getShellVolume());
                            break;
                        case 6:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oilWork.getShellWeight());
                            break;
                    }
                }
            }

        }

        try {
            workbook.write(out);
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "readExcelOilCanSetContent 执行失败", e);
        }
        System.out.println("写入成功");
        return true;
    }

    public boolean exportOilStroage(String[] headers, List<OilSaveInfo> dataset,
                                    OutputStream out, String path) {
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
        // 生成一个表格
        int index = 0;
        sheet = workbook.createSheet();

        // 产生表格标题行
        HSSFRow row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            HSSFCell cell = row.createCell(i);
            cell.setCellStyle(style);
            HSSFRichTextString text = new HSSFRichTextString(headers[i]);
            cell.setCellValue(text);
        }
        int rowNum = 1;
        Cell cell;
        for (OilSaveInfo oilSaveInfo : dataset) {
            // 如果油罐ID大于1，那么就不显示

            String time = oilSaveInfo.getTime();
            String remak = oilSaveInfo.getRemark();
            List<Oil> oils = DBDataManager.stringToArray(
                    oilSaveInfo.getOilInfoGosn(), Oil[].class);
            for (Oil oil : oils) {
                row = sheet.createRow(rowNum++);
                System.out.println(oil);
                for (int celNum = 0; celNum < headers.length; celNum++) {
                    switch (celNum) {

                        case 0:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            if (oil.getOilId() < 2) {

                                cell.setCellValue(time);
                            } else {
                                cell.setCellValue("");
                            }
                            break;
                        case 1:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            if (oil.getOilId() < 2) {
                                cell.setCellValue(remak);
                            } else {
                                cell.setCellValue("");
                            }
                            break;
                        case 2:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    "NO." + oil.getOilId());
                            break;
                        case 3:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oil.getOilHeight());
                            break;
                        case 4:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oil.getWaterHeight());
                            break;
                        case 5:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oil.getOilVolum());
                            break;
                        case 6:
                            cell = row.createCell(celNum);
                            cell.setCellStyle(style);
                            cell.setCellValue(
                                    oil.getOilWeigh());
                            break;
                    }
                }
            }

        }

        try {
            workbook.write(out);
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "readExcelOilCanSetContent 执行失败", e);
        }
        System.out.println("写入成功");
        return true;
    }

    public List<Alarm> readExcelAlarmContent(InputStream is) {
        List<Alarm> list = new ArrayList<Alarm>();
        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelAlarmContent 执行失败", e);
        }
        sheet = wb.getSheetAt(0);
        // 得到总行数
        int rowNum = sheet.getLastRowNum();

        HSSFRow row = sheet.getRow(0);
        int colNum = row.getPhysicalNumberOfCells();

        Alarm alarm;
        // 正文内容应该从第二行开始,第一行为表头的标题
        for (int i = 1; i <= rowNum; i++) {
            row = sheet.getRow(i);
            int j = 0;
            alarm = new Alarm();
            while (j < colNum) {

                switch (j) {

                    case 0:
                        alarm.setId(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 1:
                        alarm.setTime(getValue(row.getCell(j)));
                        break;
                    case 2:
                        alarm.setInfo(getValue(row
                                .getCell(j)));
                        break;
                    default:
                        break;
                }
                j++;
            }
            list.add(alarm);
        }
        return list;
    }

    public List<CommitWork> readExcelCommitWorkContent(InputStream is) {
        List<CommitWork> list = new ArrayList<CommitWork>();

        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelCommitWorkContent 执行失败", e);
        }
        sheet = wb.getSheetAt(0);

        // 得到总行数
        CommitWork commitWork = null;
        int rowNum = sheet.getLastRowNum();
        System.out.println("====行数====" + rowNum);
        List<OilWork> oilworks = new ArrayList<OilWork>();
        commitWork = new CommitWork();
        for (int i = 1; i <= rowNum; i++) {
            HSSFRow row = sheet.getRow(i);
            int colNum = row.getPhysicalNumberOfCells();
            System.out.println("====列数====" + colNum);
            OilWork oilWork = new OilWork();

            for (int j = 0; j < colNum; j++) {
                String value = getValue(row.getCell(j));
                System.out.println("数据   ===  " + value);
                switch (j) {
                    case 0:
                        if (StringUtils.isNotEmpty(value)) {
                            commitWork = new CommitWork();
                            commitWork.setTime(value);
                        }
                        break;
                    case 1:
                        if (StringUtils.isNotEmpty(value)) {
                            commitWork.setEndTime(value);
                        }
                        break;
                    case 2:

                        if (StringUtils.isNotEmpty(value)) {
                            oilWork.setOilId(Integer.parseInt(value.substring(3)));
                        }
                        break;
                    case 3:
                        if (StringUtils.isNotEmpty(value)) {
                            oilWork.setBeginHeight(Double.parseDouble(value));
                        }
                        break;
                    case 4:
                        if (StringUtils.isNotEmpty(value)) {
                            oilWork.setEndHeight(Double.parseDouble(value));
                        }
                        break;
                    case 5:
                        if (StringUtils.isNotEmpty(value)) {
                            oilWork.setShellVolume(Double.parseDouble(value));
                        }
                        break;
                    case 6:
                        if (StringUtils.isNotEmpty(value)) {
                            oilWork.setShellWeight(Double.parseDouble(value));
                        }
                        //如果下一行不为空，并且下一行数据的
                        HSSFRow tempRow = sheet.getRow(i + 1);
                        if (tempRow != null && tempRow.getCell(0) != null && StringUtils.isEmpty(getValue(tempRow.getCell(0)))) {
                            System.out.println("下一行开头为空 " + oilWork);
                            oilworks.add(oilWork);
                        } else {
                            System.out.println("下一行开头不为空   " + oilWork);
                            oilworks.add(oilWork);
                            commitWork.setInfo(DBDataManager.getListToStringByGson(oilworks));
                            oilworks = new ArrayList<OilWork>();
                            list.add(commitWork);
                        }


                        break;
                }
            }
        }
        System.out.println("数据大小" + list.size());
        return list;
    }

    public List<OilInOut> readExcelInOutContent(InputStream is) {
        List<OilInOut> list = new ArrayList<OilInOut>();
        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelInOutContent 执行失败", e);
        }
        sheet = wb.getSheetAt(0);
        // 得到总行数
        int rowNum = sheet.getLastRowNum();

        HSSFRow row = sheet.getRow(0);
        int colNum = row.getPhysicalNumberOfCells();

        OilInOut oilInOut;
        // 正文内容应该从第二行开始,第一行为表头的标题
        for (int i = 1; i <= rowNum; i++) {
            row = sheet.getRow(i);
            int j = 0;
            oilInOut = new OilInOut();
            while (j < colNum) {

                switch (j) {

                    case 3:
                        oilInOut.setId(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 4:
                        oilInOut.setOilId(Integer.parseInt(getValue(row
                                .getCell(j))));
                        break;
                    case 5:
                        oilInOut.setTime(getValue(row.getCell(j)));
                        break;
                    case 6:
                        oilInOut.setBeginHeigth(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 7:
                        oilInOut.setEndHeigth(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 8:
                        oilInOut.setBeginVolum(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    case 9:
                        oilInOut.setBeginWeight(Double.parseDouble(getValue(row
                                .getCell(j))));
                        break;
                    default:
                        break;
                }
                j++;
            }
            System.out.println("进油数据" + oilInOut);
            list.add(oilInOut);
        }
        return list;
    }

    public List<OilSaveInfo> readExcelSaveInfo(InputStream is) {
        List<OilSaveInfo> list = new ArrayList<OilSaveInfo>();

        try {
            fs = new POIFSFileSystem(is);
            wb = new HSSFWorkbook(fs);
        } catch (IOException e) {
            Log.e(TAG, "readExcelSaveInfo 执行失败", e);
        }
        sheet = wb.getSheetAt(0);

        // 得到总行数
        OilSaveInfo oilSaveInfo = null;
        int rowNum = sheet.getLastRowNum();
        System.out.println("====行数====" + rowNum);
        List<Oil> oils = new ArrayList<Oil>();
        oilSaveInfo = new OilSaveInfo();
        for (int i = 1; i <= rowNum; i++) {
            HSSFRow row = sheet.getRow(i);
            int colNum = row.getPhysicalNumberOfCells();
            System.out.println("====列数====" + colNum);
            Oil oil = new Oil();

            for (int j = 0; j < colNum; j++) {
                String value = getValue(row.getCell(j));
                System.out.println("数据   ===  " + value);
                switch (j) {
                    case 0:
                        if (StringUtils.isNotEmpty(value)) {
                            oilSaveInfo = new OilSaveInfo();
                            oilSaveInfo.setTime(value);
                        }
                        break;
                    case 1:
                        if (StringUtils.isNotEmpty(value)) {
                            oilSaveInfo.setRemark(value);
                        }
                        break;
                    case 2:

                        if (StringUtils.isNotEmpty(value)) {
                            oil.setOilId(Integer.parseInt(value.substring(3)));
                        }
                        break;
                    case 3:
                        if (StringUtils.isNotEmpty(value)) {
                            oil.setOilHeight(value);
                        }
                        break;
                    case 4:
                        if (StringUtils.isNotEmpty(value)) {
                            oil.setWaterHeight(value);
                        }
                        break;
                    case 5:
                        if (StringUtils.isNotEmpty(value)) {
                            oil.setOilVolum(value);
                        }
                        break;
                    case 6:
                        if (StringUtils.isNotEmpty(value)) {
                            oil.setOilWeigh(value);
                        }
                        //如果下一行不为空，并且下一行数据的
                        HSSFRow tempRow = sheet.getRow(i + 1);
                        if (tempRow != null && tempRow.getCell(0) != null && StringUtils.isEmpty(getValue(tempRow.getCell(0)))) {
                            System.out.println("下一行开头为空 " + oil);
                            oils.add(oil);
                        } else {
                            System.out.println("下一行开头不为空   " + oil);
                            oils.add(oil);
                            oilSaveInfo.setOilInfoGosn(DBDataManager.getListToStringByGson(oils));
                            oils = new ArrayList<Oil>();
                            list.add(oilSaveInfo);
                        }


                        break;
                }
            }
        }
        System.out.println("数据大小" + list.size());
        return list;
    }
}
