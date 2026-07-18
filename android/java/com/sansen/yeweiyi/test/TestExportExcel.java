package com.sansen.yeweiyi.test;

import android.os.Environment;
import android.test.AndroidTestCase;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.DBDataManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

public class TestExportExcel extends AndroidTestCase {

	public void TestExcel() {
		ExportExcel<OilCanSet> exportExcel = new ExportExcel<OilCanSet>();
		String headers[] = { "罐号", "油高", "最大体积", "高油位报警", "低油位报警", "高水位报警",
				"高温度报警", "低温度报警", "油位补偿", "水位补偿", "油品密度", "密度变化量", "报警允许位",
				"油品类型", "探针类型", "油品颜色" };
		DBDataManager db = MyApplication.getInstance().getmDBManager();
		List<OilCanSet> dataset = db.findAllObject(OilCanSet.class);

		File sd = Environment.getExternalStorageDirectory();
		String path = sd.getPath();

		File file = new File(path);

		if (!file.exists())
			file.mkdir();
		try {
			OutputStream out = new FileOutputStream(file);

		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}
}
