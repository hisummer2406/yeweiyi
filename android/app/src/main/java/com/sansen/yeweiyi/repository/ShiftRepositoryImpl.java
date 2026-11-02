package com.sansen.yeweiyi.repository;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.google.gson.Gson;
import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ShiftRepository} 的实现：内部直接调用现有的 {@code DaoHelp}/{@code DbUtils}，
 * 与 {@code DBDataManager} 当前访问的是同一套存储，不改表结构、不改行为。
 * 迁移方式见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries/design.md 决策1（绞杀者模式）。
 */
public class ShiftRepositoryImpl implements ShiftRepository {

    private static final String TAG = "ShiftRepositoryImpl";

    private static ShiftRepository instance;

    private final DbUtils db;
    private final MyApplication myApplication;

    private ShiftRepositoryImpl(Context ct) {
        db = DaoHelp.getInstance(ct);
        myApplication = MyApplication.getInstance();
    }

    public static synchronized ShiftRepository getInstance() {
        if (instance == null) {
            instance = new ShiftRepositoryImpl(MyApplication.getInstance());
        }
        return instance;
    }

    @Override
    public void startShift(SparseArray<OilCan> oilMap) {
        String curTime = DateUtils.getYYYMMddHHmmss();
        List<OilWork> oils = new ArrayList<>();

        CommitWork commitWork = new CommitWork();
        commitWork.setTime(curTime);
        commitWork.setInfo("[{\"shellVolume\":0.0,\"shellWeight\":0.0,\"beginHeight\":0.0,\"endHeight\":0.0,\"oilId\":0}]");

        int key;
        for (int index = 0; index < oilMap.size(); index++) {
            key = oilMap.keyAt(index);
            OilWork oilWork = new OilWork();
            oilWork.setOilId(oilMap.get(key).getOilCanPos());
            oilWork.setBeginHeight(oilMap.get(key).getOilLevel());
            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(oilWork.getOilId());
            double curOilVolume = DataUtils.getLitre(oilWork.getBeginHeight(), oilCan.getHightArray(), oilCan.getLitreArray());
            oilWork.setBeginVolume(curOilVolume);
            oils.add(oilWork);
        }

        commitWork.setInfo(new Gson().toJson(oils));
        save(commitWork);
    }

    @Override
    public void endShift(SparseArray<OilCan> oilMap, String startTime) {
        // startTime is unused, matching the pre-migration DBDataManager.CommitWork(int, String, SparseArray)
        // behavior — endTime is always recomputed from the current time instead. Not fixed here (see
        // design.md 决策1: 行为不变优先于修正).
        String curTime = DateUtils.getYYYMMddHHmmss();
        List<OilWork> oils = new ArrayList<>();

        List<CommitWork> commitWorks = findAllSorted(true);
        CommitWork commitWork;
        String gsonInfo;
        if (commitWorks != null && commitWorks.size() != 0) {
            gsonInfo = commitWorks.get(0).getInfo();
            commitWork = commitWorks.get(0);
        } else {
            ToastUtils.showToast("未开始交接", 5);
            return;
        }
        commitWork.setEndTime(curTime);

        List<OilWork> oilWorks = Arrays.asList(new Gson().fromJson(gsonInfo, OilWork[].class));

        int key;
        for (int index = 0; index < oilMap.size(); index++) {
            key = oilMap.keyAt(index);
            OilWork oilWork;
            if (oilWorks.size() > key - 1) {
                oilWork = oilWorks.get(key - 1);
            } else {
                oilWork = new OilWork();
                oilWork.setOilId(key);
            }
            oilWork.setOilId(oilMap.get(key).getOilCanPos());
            oilWork.setEndHeight(oilMap.get(key).getOilLevel());
            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(oilWork.getOilId());
            double curOilVolume = DataUtils.getLitre(oilWork.getEndHeight(), oilCan.getHightArray(), oilCan.getLitreArray());
            oilWork.setEndVolume(curOilVolume);
            oilWork.setShellVolume(DoubleUtil.double2(-curOilVolume + oilWork.getBeginVolume()));
            oilWork.setShellWeight(oilWork.getShellVolume() * oilCan.getDensity());
            oils.add(oilWork);
        }

        commitWork.setInfo(new Gson().toJson(oils));
        update(commitWork);
    }

    @Override
    public List<CommitWork> findAll() {
        List<CommitWork> object = null;
        try {
            object = db.findAll(CommitWork.class);
        } catch (DbException e) {
            Log.e(TAG, "findAll 数据库操作失败", e);
        }
        return object;
    }

    @Override
    public List<CommitWork> findAllSorted(boolean desc) {
        List<CommitWork> findAll = new ArrayList<>();
        try {
            findAll = db.findAll(Selector.from(CommitWork.class).orderBy("id", desc));
        } catch (DbException e) {
            Log.e(TAG, "findAllSorted 数据库操作失败", e);
        }
        if (findAll == null) {
            findAll = new ArrayList<>();
        }
        return findAll;
    }

    @Override
    public List<CommitWork> findPage(int pageSize, int pageIndex, boolean desc) {
        List<CommitWork> temps = null;
        try {
            temps = db.findAll(Selector.from(CommitWork.class).limit(pageSize).offset(pageIndex * pageSize).orderBy("time", desc));
        } catch (DbException e) {
            Log.e(TAG, "findPage 数据库操作失败", e);
        }
        if (temps != null) {
            return temps;
        }
        return new ArrayList<>();
    }

    @Override
    public List<CommitWork> findBetween(int limit, int offset, boolean desc, String beginTime, String endTime) {
        List<CommitWork> records = null;
        try {
            records = db.findAll(Selector.from(CommitWork.class)
                    .where("time", "between", new String[]{beginTime, endTime})
                    .limit(limit).offset(offset * limit).orderBy("id", desc));
        } catch (DbException e) {
            Log.e(TAG, "findBetween 数据库操作失败", e);
        }
        if (records != null) {
            return records;
        }
        return new ArrayList<>();
    }

    @Override
    public CommitWork findLast() {
        CommitWork commitWork = null;
        try {
            commitWork = db.findFirst(Selector.from(CommitWork.class).orderBy("id", true));
        } catch (DbException e) {
            Log.e(TAG, "findLast 数据库操作失败", e);
        }
        return commitWork;
    }

    @Override
    public CommitWork findById(int id) {
        CommitWork commitWork = null;
        try {
            commitWork = db.findById(CommitWork.class, id);
        } catch (DbException e) {
            Log.e(TAG, "findById 数据库操作失败", e);
        }
        return commitWork;
    }

    @Override
    public void update(final CommitWork commitWork) {
        if (commitWork != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.update(commitWork);
                    } catch (DbException e) {
                        Log.e(TAG, "update 数据库操作失败", e);
                    }
                }
            });
        }
    }

    @Override
    public void delete(final CommitWork commitWork) {
        if (commitWork != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.delete(commitWork);
                    } catch (DbException e) {
                        Log.e(TAG, "delete 数据库操作失败", e);
                    }
                }
            });
        }
    }

    @Override
    public void deleteAll() {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.deleteAll(CommitWork.class);
                } catch (DbException e) {
                    Log.e(TAG, "deleteAll 数据库操作失败", e);
                }
            }
        });
    }

    private void save(final CommitWork commitWork) {
        if (commitWork != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.save(commitWork);
                    } catch (DbException e) {
                        Log.e(TAG, "save 数据库操作失败", e);
                    }
                }
            });
        }
    }
}
