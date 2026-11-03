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
import com.sansen.yeweiyi.logic.shift.ShiftRecordBuilder;
import com.sansen.yeweiyi.logic.shift.ShiftTime;
import com.sansen.yeweiyi.logic.shift.TankReading;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

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
        ShiftTime now = ShiftTime.of(System.currentTimeMillis(), TimeZone.getDefault());
        CommitWork commitWork = new CommitWork();
        commitWork.setTime(now.local);
        commitWork.setBeginTimeMs(now.epochMs);
        commitWork.setInfo(new Gson().toJson(ShiftRecordBuilder.begin(readings(oilMap))));
        save(commitWork);
    }

    @Override
    public void endShift(SparseArray<OilCan> oilMap, String startTime) {
        // startTime 一直没用到（迁移前 DBDataManager 也一样），结束时间取当前时间。
        List<CommitWork> commitWorks = findAllSorted(true);
        if (commitWorks == null || commitWorks.isEmpty()) {
            ToastUtils.showToast("未开始交接", 5);
            return;
        }
        // 仍取 id 最大的一行，不论它是否已结束——重复结束的行为按 issue #49 暂不改。
        CommitWork commitWork = commitWorks.get(0);
        commitWork.setEndTime(ShiftTime.of(System.currentTimeMillis(), TimeZone.getDefault()).local);
        OilWork[] begins = new Gson().fromJson(commitWork.getInfo(), OilWork[].class);
        List<OilWork> oils = ShiftRecordBuilder.end(
                begins == null ? null : Arrays.asList(begins), readings(oilMap));
        commitWork.setInfo(new Gson().toJson(oils));
        update(commitWork);
    }

    /** 把当前各罐转换成交班读数；容积由当前罐表换算（同旧实现）。 */
    private List<TankReading> readings(SparseArray<OilCan> oilMap) {
        List<TankReading> readings = new ArrayList<>();
        for (int index = 0; index < oilMap.size(); index++) {
            OilCan live = oilMap.valueAt(index);
            int tank = live.getOilCanPos();
            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(tank);
            double volume = DataUtils.getLitre(live.getOilLevel(), oilCan.getHightArray(), oilCan.getLitreArray());
            readings.add(new TankReading(tank, live.getOilLevel(), volume, oilCan.getDensity(), oilCan.getJieZhi()));
        }
        return readings;
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
