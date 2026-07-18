package com.sansen.yeweiyi.manager;

import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;

public class CommitWorkManager {
	private static CommitWorkManager mInstance;
	private DBDataManager dbManager;
	private MyApplication myApplication;

	private CommitWorkManager() {
		myApplication = MyApplication.getInstance();
		dbManager = myApplication.getmDBManager();

	}

	public static CommitWorkManager getInstance() {
		if (mInstance == null) {
			mInstance = new CommitWorkManager();
		}
		return mInstance;
	}

	public void commitWork(SparseArray<OilCan> oilMap,String startTime) {
		dbManager.CommitWork(oilMap,startTime);
	}
	public void commitWork(SparseArray<OilCan> oilMap) {
		dbManager.CommitWork(oilMap);
	}
}
