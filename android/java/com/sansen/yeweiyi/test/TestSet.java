package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.utils.Arith;
import com.sansen.yeweiyi.utils.DataUtils;

import java.util.List;

import atg.jni.ATGJNI;
import serialport.help.SerialPortHelper;

public class TestSet extends AndroidTestCase {
	DbUtils db;

	public void set() {
		db = DbUtils.create(getContext());

		try {

			// 一：增加油罐探针对照表
			// db.save(new ProbeToOil(1,"25",1,1));

			// 得到当前设置了几个探针
			List<ProbeToOil> probes = db.findAll(ProbeToOil.class);
			for (ProbeToOil probe : probes) {
				System.out.println(probe.toString());
			}

			int i = 0;
			// OilCanTable olican = new OilCanTable(
			// 1
			// ,i=1,i=0+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			//
			// ,i=1,i=0+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// ,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30,i=i+30
			// );
			// db.update(olican);

			int cout = db.findAll(OilCanTable.class).size();
			List<OilCanTable> findAll = db.findAll(OilCanTable.class);
			for (OilCanTable oilCanTable : findAll) {

				System.out.println(oilCanTable.toString());
			}
			System.out.println("罐表的数量" + cout + "多少");
			OilCan oilCan = new OilCan();
			DBDataManager help = DBDataManager.getInstance();
			help.setOilTableToOilCan(oilCan);
			double oilVolume = ATGJNI.getLitre((float) 622.37,
					oilCan.getHightArray(), oilCan.getLitreArray());

			/**
			 * 水的体积
			 */
			double waterVolume = DataUtils.getLitre(30.59,
					oilCan.getHightArray(), oilCan.getLitreArray());

			double oilv = Arith.sub(oilVolume, waterVolume);

			/**
			 * 油体积 可以使用
			 */
			System.out.println("体积" + oilv);

			/**
			 * 存油重量 体积x密度 密度为0.8
			 */
			System.out.println(oilv * 0.8f);

		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}

	}

}
