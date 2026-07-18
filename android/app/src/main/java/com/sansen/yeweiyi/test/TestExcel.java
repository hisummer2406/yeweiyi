package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.logic.ExportExcel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

public class TestExcel extends AndroidTestCase {
	public void TestExcel() {

		ExportExcel<String> excel = new ExportExcel<String>();
		InputStream is = null;
		String path = "/storage/udisk";
		File file = new File(path + "/探针设置表.xls");
		try {
			is = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		List<ProbeToOil> readExcelProbeToOilCanContent = excel
				.readExcelProbeToOilCanContent(is);
		for (ProbeToOil probeToOil : readExcelProbeToOilCanContent) {
			System.out.println("读取Excel" + probeToOil);
		}
	}
}
