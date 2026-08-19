package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.Oil;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.utils.DateUtils;

import java.util.ArrayList;
import java.util.List;
public class SaveOilManager {
	private static SaveOilManager mInsatance;
	private MyApplication application;
	private DBDataManager dbDataManager;
	private OilcanDataManager oilcanDataManager;
	private SparseArray<OilCan> maps;

	private SaveOilManager(Context ct) {
		application = MyApplication.getInstance();
		dbDataManager = application.getmDBManager();
		oilcanDataManager = application.getmOilcanDataManager();
	}

	public static SaveOilManager getInstance(Context ct) {
		if (mInsatance == null) {
			mInsatance = new SaveOilManager(ct);
		}
		return mInsatance;
	}
	/**
	 * Save all the oilcan information 保存库存记录
	 */
	public void SaveOilDB() {

		ThreadManager.getShortPool().execute(new Runnable() {

			@Override
			public void run() {

				maps = oilcanDataManager.getData();
				if (maps != null) {
					List<Oil> oils = new ArrayList<Oil>();

					for (int i=0;i<maps.size();i++) {
						OilCan oilCan = maps.valueAt(i);
						//Oil oil = new Oil();
						//oil.setOilId(oilCan.getOilCanPos());
						//oil.setOilHeight(oilCan.getOilLevel() + "");
						//oil.setWaterHeight(oilCan.getWaterLevel() + "");
						//oil.setOilVolum(oilCan.getOilVolume() + "");
						//oil.setOilWeigh(oilCan.getOilWeight() + "");
						//oils.add(oil);
						StockSave stockSave=new StockSave();
						stockSave.setWaterheigth(oilCan.getWaterLevel());
						stockSave.setJieZhi(oilCan.getJieZhi());
						stockSave.setTemperature(oilCan.getTemper());
						stockSave.setWeight(oilCan.getOilWeight());
						stockSave.setVolum(oilCan.getOilVolume());
						stockSave.setOilId(oilCan.getOilCanPos());
						stockSave.setHeight(oilCan.getOilLevel());
						stockSave.setTime(DateUtils.getYYYMMddHHmmss());
						dbDataManager.saveSaveOilRecord(stockSave);
					}

					//String gosnInfo = dbDataManager.getListToStringByGson(oils);


				}

			}
		});

	}
	/**
	 * Save all the oilcan information 保存库存记录
	 */
	public void SaveOilDB(final String remark) {

		ThreadManager.getShortPool().execute(new Runnable() {

			@Override
			public void run() {
				
				maps = oilcanDataManager.getData();
				if (maps != null) {
					List<Oil> oils = new ArrayList<Oil>();

					for (int i=0;i<maps.size();i++) {
						OilCan oilCan = maps.valueAt(i);
						Oil oil = new Oil();
						oil.setOilId(oilCan.getOilCanPos());
						oil.setOilHeight(oilCan.getOilLevel() + "");
						oil.setWaterHeight(oilCan.getWaterLevel() + "");
						oil.setOilVolum(oilCan.getOilVolume() + "");
						oil.setOilWeigh(oilCan.getOilWeight() + "");
						oils.add(oil);
					}

					String gosnInfo = dbDataManager.getListToStringByGson(oils);

					dbDataManager.saveSaveOilRecord(gosnInfo,remark);
				}

			}
		});

	}

}
