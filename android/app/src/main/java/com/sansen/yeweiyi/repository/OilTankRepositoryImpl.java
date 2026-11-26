package com.sansen.yeweiyi.repository;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link OilTankRepository} 的实现：内部直接调用现有的 {@code DaoHelp}/{@code DbUtils}，
 * 与 {@code DBDataManager} 当前访问的是同一套存储，不改表结构、不改行为。
 * 迁移方式见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries/design.md 决策1（绞杀者模式）。
 */
public class OilTankRepositoryImpl implements OilTankRepository {

    private static final String TAG = "OilTankRepositoryImpl";

    private static OilTankRepository instance;

    private final DbUtils db;

    // [不可达-2026-09-23] 唯一触发点在 exportOilCanTable(String) 方法体内，而那个方法的唯一调用方
    // CreateOilTableFragment.saveRunnable 全仓库从未被 execute() 过（西语翻译覆盖率核查时发现，
    // 详见 CreateOilTableFragment.java 里 saveRunnable 声明处）。R.string.data_save_success 不用优先翻。
    private final Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            // [已屏蔽-2026-09-23] data_save_success 已删除（不可达，见上方注释）；解封时记得放回原文案。
            // SystemUtils.showRsToast(R.string.data_save_success);
        }
    };

    private OilTankRepositoryImpl(Context ct) {
        db = DaoHelp.getInstance(ct);
    }

    public static synchronized OilTankRepository getInstance() {
        if (instance == null) {
            instance = new OilTankRepositoryImpl(MyApplication.getInstance());
        }
        return instance;
    }

    @Override
    public List<OilCanSet> findAllOilCanSet() {
        return findAllObject(OilCanSet.class);
    }

    @Override
    public OilCanSet findOilCanSetById(int id) {
        OilCanSet object = null;
        try {
            object = db.findById(OilCanSet.class, id);
        } catch (DbException e) {
            Log.e(TAG, "findOilCanSetById 数据库操作失败", e);
        }
        return object;
    }

    @Override
    public void updateOilCanSet(final OilCanSet oilCanSet) {
        update(oilCanSet, this::notifyOilCanSetChanged);
    }

    @Override
    public void updateOilCanSetSync(OilCanSet oilCanSet) throws DbException {
        db.update(oilCanSet);
        notifyOilCanSetChanged();
    }

    private volatile Runnable oilCanSetChangedListener;

    /**
     * 所有罐设置保存入口（设置页、下拉框、复制、报警开关、RPC）都经过本类，挂在这里一处即可。
     * 罐表（OilCanTable）写入也触发（现场生成、U 盘导入、远程下发，#41），让云端标定镜像不必等到下次重连。
     */
    @Override
    public void setOilCanSetChangedListener(Runnable listener) {
        this.oilCanSetChangedListener = listener;
    }

    private void notifyOilCanSetChanged() {
        Runnable listener = oilCanSetChangedListener;
        if (listener != null) {
            listener.run();
        }
    }

    @Override
    public List<OilCanTable> findAllOilCanTable() {
        return findAllObject(OilCanTable.class);
    }

    @Override
    public void updateOilCanTable(final OilCanTable oilCanTable) {
        update(oilCanTable, this::notifyOilCanSetChanged);
    }

    @Override
    public void updateOilCanTableSync(OilCanTable oilCanTable) throws DbException {
        db.update(oilCanTable);
        notifyOilCanSetChanged();
    }

    @Override
    public void exportOilCanTable(String path) {
        ExportExcel<OilCanTable> exportExcel = new ExportExcel<OilCanTable>();
        String headers[] = new String[31];
        // [已屏蔽-2026-09-23] tan_no 已删除（不可达，exportOilCanTable 整个方法无调用方，见上方注释）。
        // headers[0] = SystemUtils.getRsStr(R.string.tan_no);
        for (int i = 1; i < headers.length; i++) {
            headers[i] = i + "";
        }
        List<OilCanTable> dataset = findAllOilCanTable();

        File file = new File(path);
        if (!file.exists())
            file.mkdir();
        OutputStream out = null;
        try {
            file = new File(path + "/" + "OilCan_set" + ".xls");
            out = new FileOutputStream(file);
            // [已屏蔽-2026-09-23] tan_table_data 已删除（不可达，见上方注释）。
            // exportExcel.exportOilCanTable(SystemUtils.getRsStr(R.string.tan_table_data), headers, dataset,
            //         new OilCanTable().getParams(), out, "");
        } catch (Exception e) {
            Log.e(TAG, "exportOilCanTable 数据库操作失败", e);
        }
        try {
            out.close();
            handler.sendEmptyMessageDelayed(0, 3000);
        } catch (IOException e) {
            // [已屏蔽-2026-09-23] io_no_close 已删除（不可达，见上方注释）。
            // SystemUtils.showRsToast(R.string.io_no_close);
        }
        double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
    }

    @Override
    public List<ProbeToOil> findAllProbeToOil() {
        return findAllObject(ProbeToOil.class);
    }

    @Override
    public Sideleakage findLastSideleakage() {
        Sideleakage object = null;
        try {
            object = db.findFirst(Selector.from(Sideleakage.class).orderBy("id", true));
        } catch (DbException e) {
            Log.e(TAG, "findLastSideleakage 数据库操作失败", e);
        }
        return object;
    }

    @Override
    public List<Sideleakage> findSideleakageBetween(int limit, int offset, boolean desc, String beginTime, String endTime) {
        List<Sideleakage> records = null;
        try {
            records = db.findAll(Selector.from(Sideleakage.class)
                    .where("time", "between", new String[]{beginTime, endTime})
                    .limit(limit).offset(offset * limit).orderBy("id", desc));
        } catch (DbException e) {
            Log.e(TAG, "findSideleakageBetween 数据库操作失败", e);
        }
        if (records != null) {
            return records;
        }
        return new ArrayList<>();
    }

    @Override
    public List<OilCanMapper> findAllOilCanMapper() {
        return findAllObject(OilCanMapper.class);
    }

    @Override
    public List<OilExploration> findAllOilExploration() {
        return findAllObject(OilExploration.class);
    }

    // ---- shared helpers (mirror the equivalent generic DBDataManager methods) ----

    private <T> List<T> findAllObject(Class<T> clazz) {
        List<T> object = null;
        try {
            object = db.findAll(clazz);
        } catch (DbException e) {
            Log.e(TAG, "findAllObject 数据库操作失败", e);
        }
        // 与本类其它查询方法（如 findSideleakageBetween）保持一致：异常时兜底空
        // 列表而不是 null，避免调用方（如 CreateOilTableFragment 未判空的
        // .get(resultCode)）在偶发 DbException 时被 NPE 崩溃。
        if (object != null) {
            return object;
        }
        return new ArrayList<>();
    }

    private <T> void update(final T object) {
        update(object, null);
    }

    /** @param afterWrite 写库成功后在写库线程上执行，可为 null */
    private <T> void update(final T object, final Runnable afterWrite) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.update(object);
                    } catch (DbException e) {
                        Log.e(TAG, "update 数据库操作失败", e);
                        return;
                    }
                    if (afterWrite != null) {
                        afterWrite.run();
                    }
                }
            });
        }
    }
}
