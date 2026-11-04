package com.sansen.yeweiyi.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sansen.yeweiyi.bean.OilWork;

import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** 导出按 params 列出的列取值，实体多出的字段（如 OilWork.oilTypeIndex）不应让导出失败。 */
public class ExportExcelTest {

    @Test
    public void exportsOnlyConfiguredColumnsWhenEntityHasExtraFields() throws Exception {
        OilWork row = new OilWork();
        row.setOilId(3);
        row.setJieZhi("92#");
        row.setTime("2026-11-04 08:00:00");
        row.setBeginHeight(1500.0);
        row.setOilTypeIndex(5);
        String[] params = new OilWork().getParams();
        String[] headers = new String[params.length];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = params[i];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicReference<Boolean> result = new AtomicReference<>();
        new ExportExcel<OilWork>().exportExcel(headers, Collections.singletonList(row), params, out,
                result::set);

        assertEquals(Boolean.TRUE, result.get());
        assertTrue(out.size() > 0);
        HSSFRow data = new HSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheetAt(0).getRow(1);
        assertEquals(params.length, data.getLastCellNum());
        assertEquals("92#", data.getCell(0).getStringCellValue());
        assertEquals(3.0, data.getCell(1).getNumericCellValue(), 0);
    }
}
