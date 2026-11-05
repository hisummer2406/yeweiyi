package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.Oil;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.logic.shift.ShiftTime;
import com.sansen.yeweiyi.logic.thingsboard.StockRecordMapper;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
public class SaveOilManager {
	private static SaveOilManager mInsatance;
	private MyApplication application;
	private DBDataManager dbDataManager;
	private OilcanDataManager oilcanDataManager;
	private StockRepository stockRepository;
	private SparseArray<OilCan> maps;

	private SaveOilManager(Context ct) {
		application = MyApplication.getInstance();
		dbDataManager = application.getmDBManager();
		oilcanDataManager = application.getmOilcanDataManager();
		stockRepository = StockRepositoryImpl.getInstance();
	}

	public static SaveOilManager getInstance(Context ct) {
		if (mInsatance == null) {
			mInsatance = new SaveOilManager(ct);
		}
		return mInsatance;
	}
	/**
	 * Save all the oilcan information 保存库存记录，并上报云端 stockRecord（一次一条、带全部罐，#49）。
	 *
	 * @param trigger {@link StockRecordMapper#TRIGGER_SCHEDULED}（周期）或 {@link StockRecordMapper#TRIGGER_MANUAL}（库存页手动）
	 */
	public void saveStock(final String trigger) {

		ThreadManager.getShortPool().execute(new Runnable() {

			@Override
			public void run() {

				maps = oilcanDataManager.getData();
				if (maps != null) {
					List<Oil> oils = new ArrayList<Oil>();
					// 一次保存的各罐共用同一时刻、精确到秒（issue #49）；本地时间字符串与上报的 tsMs 取自同一瞬间。
					ShiftTime now = ShiftTime.of(System.currentTimeMillis(), TimeZone.getDefault());
					String time = now.local;
					List<StockSave> records = new ArrayList<>();
					List<StockRecordMapper.Tank> reported = new ArrayList<>();

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
						stockSave.setTime(time);
						records.add(stockSave);
						// 上报值取自刚存的 stockSave，不再读一遍 oilCan：串口线程随时会改它，两次读可能不是同一份读数。
						reported.add(new StockRecordMapper.Tank(stockSave.getOilId(), stockSave.getJieZhi(),
								oilCan.getOilType(), stockSave.getHeight(), stockSave.getVolum(),
								stockSave.getWaterheigth(), stockSave.getTemperature(), stockSave.getWeight()));
					}
					// 本地整批写入成功才上报，写入失败则本次不上报（云端不会有本地没有的记录）。
					if (stockRepository.saveStockBatchNow(records)) {
						ThingsBoardManager.getInstance(application).reportStockRecord(trigger, now.epochMs, reported);
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
