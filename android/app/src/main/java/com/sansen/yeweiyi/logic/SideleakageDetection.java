package com.sansen.yeweiyi.logic;


import android.util.Log;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.listener.SideleakageListener;
import com.sansen.yeweiyi.manager.DBDataManager;

import java.util.List;

public class SideleakageDetection {
	private long beginTime;
	private long endTime;

	private List<Integer> oilCans;

	private DBDataManager dbDataManager;
	private static SideleakageDetection instance;
	private boolean isEnd;



	public static SideleakageDetection getInstance(List<Integer> oilCans, int time, SideleakageListener sideleakageListener){
		if (instance==null) {
			instance = new SideleakageDetection(oilCans, time,sideleakageListener);
		}
		instance.beginDetection(time);
		return instance;
	}

	/**
	 * 开启测漏检测
	 *
	 * @param oilCan
	 * @param continueTime
	 *            分钟
	 */

	private double endCount;
	private SideleakageListener sideleakageListener;

	public SideleakageDetection(List<Integer> oilCans, int time, SideleakageListener sideleakageListener) {
		dbDataManager = MyApplication.getInstance().getmDBManager();
		this.oilCans = oilCans;
		this.sideleakageListener = sideleakageListener;
		beginDetection(time);

	}

	public void endDection(){
		isEnd = true;
	}

	private void beginDetection(final int continueTime) {
		isEnd = false;
		beginTime = System.currentTimeMillis();
		endTime = continueTime * 60 * 1000 + beginTime;
		endCount = continueTime * 60 * 1000;
		new Thread(){
			@Override
			public void run() {
				long cur;
				int curRatio;
				while(System.currentTimeMillis()<endTime&&!isEnd){
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
					    Log.e("SideleakageDetection", "run 执行失败", e);
					}
					cur= System.currentTimeMillis()-beginTime;

					curRatio = (int)((cur/endCount)*100);
					sideleakageListener.onRadio(curRatio);
				}
			}
		}.start();
	}
}
