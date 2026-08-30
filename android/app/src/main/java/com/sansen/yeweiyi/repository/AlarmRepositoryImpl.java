package com.sansen.yeweiyi.repository;

import android.content.Context;
import android.util.Log;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * {@link AlarmRepository} 的实现：内部直接调用现有的 {@code DaoHelp}/{@code DbUtils}，
 * 与 {@code DBDataManager} 当前访问的是同一套存储，不改表结构、不改行为。
 * 迁移方式见 openspec/changes/layer-data-and-hardware-boundaries/design.md 决策1（绞杀者模式）。
 */
public class AlarmRepositoryImpl implements AlarmRepository {

    private static final String TAG = "AlarmRepositoryImpl";

    private static AlarmRepository instance;

    private final DbUtils db;

    private AlarmRepositoryImpl(Context ct) {
        db = DaoHelp.getInstance(ct);
    }

    public static synchronized AlarmRepository getInstance() {
        if (instance == null) {
            instance = new AlarmRepositoryImpl(MyApplication.getInstance());
        }
        return instance;
    }

    @Override
    public void saveAlarm(int oilCanPos, OilMessage oilMessage) {
        // 原 DBDataManager.saveAlarm 这里还调用了 sendOilMsg(oilMessage)，但那是个空实现
        // （public void sendOilMsg(OilMessage msg) {}），没有任何可观察副作用，故未在此复刻；
        // sendOilMsg 本身继续留在 DBDataManager 上（AlarmManager 仍在直接调用它，不属于本次范围）。
        if (oilMessage.getType() == OilMessage.PRO_ERR_CONN) {
            return;
        }
        saveAlarmRecord(oilMessage.toString(), oilMessage.getOilCanId() + "", oilMessage.getJieZhi());
    }

    @Override
    public void saveAlarmRecord(String info, String guanHao, String jieZhi) {
        if (Singleton.getInstance().IsCreateAlarm) {
            Alarm alarm = new Alarm(DateUtils.getYYYMMddHHmmss(), info, guanHao, jieZhi);
            save(alarm);
        }
    }

    @Override
    public void delete(final Alarm alarm) {
        if (alarm != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.delete(alarm);
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
                    db.deleteAll(Alarm.class);
                } catch (DbException e) {
                    Log.e(TAG, "deleteAll 数据库操作失败", e);
                }
            }
        });
    }

    @Override
    public List<Alarm> findAll() {
        List<Alarm> object = null;
        try {
            object = db.findAll(Alarm.class);
        } catch (DbException e) {
            Log.e(TAG, "findAll 数据库操作失败", e);
        }
        return object;
    }

    @Override
    public List<Alarm> findAllSorted(boolean desc) {
        List<Alarm> object = null;
        try {
            object = db.findAll(Selector.from(Alarm.class).orderBy("time", desc));
        } catch (DbException e) {
            Log.e(TAG, "findAllSorted 数据库操作失败", e);
        }
        if (object != null) {
            return object;
        }
        return new ArrayList<>();
    }

    @Override
    public List<Alarm> findPage(int pageSize, int pageIndex, boolean desc) {
        List<Alarm> temps = null;
        try {
            temps = db.findAll(Selector.from(Alarm.class).limit(pageSize).offset(pageIndex * pageSize).orderBy("time", desc));
        } catch (DbException e) {
            Log.e(TAG, "findPage 数据库操作失败", e);
        }
        if (temps != null) {
            return temps;
        }
        return new ArrayList<>();
    }

    @Override
    public List<Alarm> findBetween(int limit, int offset, boolean desc, String beginTime, String endTime) {
        List<Alarm> records = null;
        try {
            records = db.findAll(Selector.from(Alarm.class)
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
    public HashMap<String, Object> findPaged(String id, String beginTime, String endTime, int limit, int offset, boolean order) {
        HashMap<String, Object> maps = new HashMap<>();
        List<Alarm> records = new ArrayList<>();
        long count = 0;
        try {
            if (id != null) {
                records = db.findAll(Selector.from(Alarm.class).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(Alarm.class).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}));
            } else {
                records = db.findAll(Selector.from(Alarm.class).where("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(Alarm.class).where("time", "between",
                        new String[]{beginTime, endTime}));
            }
        } catch (DbException e) {
            Log.e(TAG, "findPaged 数据库操作失败", e);
        }
        maps.put("list", records);
        maps.put("count", count);
        return maps;
    }

    private void save(final Alarm alarm) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.save(alarm);
                } catch (DbException e) {
                    Log.e(TAG, "save 数据库操作失败", e);
                }
            }
        });
    }
}
