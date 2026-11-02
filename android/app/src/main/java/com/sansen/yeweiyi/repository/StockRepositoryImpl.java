package com.sansen.yeweiyi.repository;

import android.content.Context;
import android.util.Log;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.manager.ThreadManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * {@link StockRepository} 的实现：内部直接调用现有的 {@code DaoHelp}/{@code DbUtils}，
 * 与 {@code DBDataManager} 当前访问的是同一套存储，不改表结构、不改行为。
 * 迁移方式见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries/design.md 决策1（绞杀者模式）。
 */
public class StockRepositoryImpl implements StockRepository {

    private static final String TAG = "StockRepositoryImpl";

    private static StockRepository instance;

    private final DbUtils db;

    private StockRepositoryImpl(Context ct) {
        db = DaoHelp.getInstance(ct);
    }

    public static synchronized StockRepository getInstance() {
        if (instance == null) {
            instance = new StockRepositoryImpl(MyApplication.getInstance());
        }
        return instance;
    }

    // ---- OilInOut ----

    @Override
    public void saveInRecord(OilInOut in) {
        save(in);
    }

    @Override
    public void updateIn(OilInOut in) {
        update(in);
    }

    @Override
    public void deleteIn(OilInOut in) {
        delete(in);
    }

    @Override
    public void deleteAllIn() {
        deleteAll(OilInOut.class);
    }

    @Override
    public OilInOut findLastIn() {
        return findLast(OilInOut.class);
    }

    @Override
    public OilInOut findInById(int id) {
        return findById(OilInOut.class, id);
    }

    @Override
    public List<OilInOut> findAllIn() {
        return findAllObject(OilInOut.class);
    }

    @Override
    public List<OilInOut> findAllInSorted(boolean desc) {
        return findAllSorted(OilInOut.class, desc);
    }

    @Override
    public List<OilInOut> findInPage(int pageSize, int pageIndex, boolean desc) {
        return findPage(OilInOut.class, pageSize, pageIndex, desc);
    }

    @Override
    public List<OilInOut> findInPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc) {
        return findPageByOil(OilInOut.class, oilCanId, pageSize, pageIndex, desc);
    }

    @Override
    public List<OilInOut> findInBetween(int limit, int offset, boolean desc, String beginTime, String endTime) {
        return findBetween(OilInOut.class, limit, offset, desc, beginTime, endTime);
    }

    @Override
    public HashMap<String, Object> findInPaged(String oilId, String beginTime, String endTime, int limit, int offset, boolean order) {
        return findPaged(OilInOut.class, oilId, beginTime, endTime, limit, offset, order);
    }

    // ---- OutStock ----

    @Override
    public void saveOutRecord(OutStock out) {
        save(out);
    }

    @Override
    public void deleteOut(OutStock out) {
        delete(out);
    }

    @Override
    public void deleteAllOut() {
        deleteAll(OutStock.class);
    }

    @Override
    public OutStock findLastOut() {
        return findLast(OutStock.class);
    }

    @Override
    public List<OutStock> findAllOut() {
        return findAllObject(OutStock.class);
    }

    @Override
    public List<OutStock> findAllOutSorted(boolean desc) {
        return findAllSorted(OutStock.class, desc);
    }

    @Override
    public List<OutStock> findOutPage(int pageSize, int pageIndex, boolean desc) {
        return findPage(OutStock.class, pageSize, pageIndex, desc);
    }

    @Override
    public List<OutStock> findOutPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc) {
        return findPageByOil(OutStock.class, oilCanId, pageSize, pageIndex, desc);
    }

    // ---- StockSave ----

    @Override
    public void saveStockRecord(StockSave stockSave) {
        save(stockSave);
    }

    @Override
    public void deleteStock(StockSave stockSave) {
        delete(stockSave);
    }

    @Override
    public void deleteAllStock() {
        deleteAll(StockSave.class);
    }

    @Override
    public List<StockSave> findAllStock() {
        return findAllObject(StockSave.class);
    }

    @Override
    public List<StockSave> findAllStockSorted(boolean desc) {
        return findAllSorted(StockSave.class, desc);
    }

    @Override
    public List<StockSave> findStockPage(int pageSize, int pageIndex, boolean desc) {
        return findPage(StockSave.class, pageSize, pageIndex, desc);
    }

    @Override
    public List<StockSave> findStockPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc) {
        return findPageByOil(StockSave.class, oilCanId, pageSize, pageIndex, desc);
    }

    // ---- DetailInoutRecord ----

    @Override
    public void saveDetail(DetailInoutRecord record) {
        save(record);
    }

    @Override
    public void updateDetail(DetailInoutRecord record) {
        update(record);
    }

    @Override
    public DetailInoutRecord findDetailById(int id) {
        return findById(DetailInoutRecord.class, id);
    }

    // ---- shared helpers (mirror the equivalent generic DBDataManager methods) ----

    private <T> void save(final T object) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.save(object);
                    } catch (DbException e) {
                        Log.e(TAG, "save 数据库操作失败", e);
                    }
                }
            });
        }
    }

    private <T> void update(final T object) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.update(object);
                    } catch (DbException e) {
                        Log.e(TAG, "update 数据库操作失败", e);
                    }
                }
            });
        }
    }

    private <T> void delete(final T object) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.delete(object);
                    } catch (DbException e) {
                        Log.e(TAG, "delete 数据库操作失败", e);
                    }
                }
            });
        }
    }

    private void deleteAll(final Class<?> entityType) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.deleteAll(entityType);
                } catch (DbException e) {
                    Log.e(TAG, "deleteAll 数据库操作失败", e);
                }
            }
        });
    }

    private <T> T findLast(Class<T> clazz) {
        T object = null;
        try {
            object = db.findFirst(Selector.from(clazz).orderBy("id", true));
        } catch (DbException e) {
            Log.e(TAG, "findLast 数据库操作失败", e);
        }
        return object;
    }

    private <T> T findById(Class<T> clazz, int id) {
        T object = null;
        try {
            object = db.findById(clazz, id);
        } catch (DbException e) {
            Log.e(TAG, "findById 数据库操作失败", e);
        }
        return object;
    }

    private <T> List<T> findAllObject(Class<T> clazz) {
        List<T> object = null;
        try {
            object = db.findAll(clazz);
        } catch (DbException e) {
            Log.e(TAG, "findAllObject 数据库操作失败", e);
        }
        return object;
    }

    /**
     * 与 {@link #findAllObject(Class)} 取同一批数据，但按 time 排序——{@code findAllObject}
     * 不带 orderBy，等价于按插入顺序（旧→新），与界面列表统一用的 orderBy("time", desc) 方向相反，
     * 导出时若直接复用 {@code findAllObject} 会导致导出行顺序与界面显示顺序不一致。
     */
    private <T> List<T> findAllSorted(Class<T> clazz, boolean desc) {
        List<T> object = null;
        try {
            object = db.findAll(Selector.from(clazz).orderBy("time", desc));
        } catch (DbException e) {
            Log.e(TAG, "findAllSorted 数据库操作失败", e);
        }
        if (object != null) {
            return object;
        }
        return new ArrayList<>();
    }

    private <T> List<T> findPage(Class<T> clazz, int pageSize, int pageIndex, boolean desc) {
        List<T> temps = null;
        try {
            temps = db.findAll(Selector.from(clazz).limit(pageSize).offset(pageIndex * pageSize).orderBy("time", desc));
        } catch (DbException e) {
            Log.e(TAG, "findPage 数据库操作失败", e);
        }
        if (temps != null) {
            return temps;
        }
        return new ArrayList<>();
    }

    private <T> List<T> findPageByOil(Class<T> clazz, int oilCanId, int pageSize, int pageIndex, boolean desc) {
        // desc is intentionally unused below, matching the pre-migration
        // DBDataManager.findLimit(Class, int oilid, int, int, boolean) behavior — the
        // by-oilId query always orders "id"/"time" descending regardless of the flag.
        // Not fixed here (see design.md 决策1: 行为不变优先于修正).
        List<T> objects = null;
        try {
            objects = db.findAll(Selector.from(clazz).where("oilId", "=", oilCanId).limit(pageSize).offset(pageIndex * pageSize).orderBy("time", true));
        } catch (DbException e) {
            Log.e(TAG, "findPageByOil 数据库操作失败", e);
        }
        if (objects != null) {
            return objects;
        }
        return new ArrayList<>();
    }

    private <T> List<T> findBetween(Class<T> clazz, int limit, int offset, boolean desc, String beginTime, String endTime) {
        List<T> records = null;
        try {
            records = db.findAll(Selector.from(clazz)
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

    @SuppressWarnings("unchecked")
    private <T> HashMap<String, Object> findPaged(Class<T> clazz, String id, String beginTime, String endTime, int limit, int offset, boolean order) {
        HashMap<String, Object> maps = new HashMap<>();
        List<T> records = new ArrayList<>();
        long count = 0;
        try {
            if (id != null) {
                records = db.findAll(Selector.from(clazz).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(clazz).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}));
            } else {
                records = db.findAll(Selector.from(clazz).where("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(clazz).where("time", "between",
                        new String[]{beginTime, endTime}));
            }
        } catch (DbException e) {
            Log.e(TAG, "findPaged 数据库操作失败", e);
        }
        maps.put("list", records);
        maps.put("count", count);
        return maps;
    }
}
