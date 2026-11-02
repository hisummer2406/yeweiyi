package com.sansen.yeweiyi.repository;

import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;

import java.util.List;

/**
 * 油罐配置数据的唯一读写入口，覆盖 {@code OilCanSet}（油罐配置）/{@code OilCanTable}（罐表标定）/
 * {@code ProbeToOil}（探针地址映射）/{@code Sideleakage}（侧漏检测记录）/{@code OilCanMapper}
 * （测试数据映射表）/{@code OilExploration}（油气探测报警值）六个实体，取代对 {@code DBDataManager}
 * 油罐相关方法的直接调用。
 * 见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries §6。
 */
public interface OilTankRepository {

    // ---- OilCanSet（油罐配置，行数固定，只查不增删） ----

    List<OilCanSet> findAllOilCanSet();

    OilCanSet findOilCanSetById(int id);

    void updateOilCanSet(OilCanSet oilCanSet);

    /**
     * 与 {@link #updateOilCanSet} 不同：在调用方线程同步写入，写入失败时抛出异常而不是
     * 记日志吞掉。供需要在返回执行结果前确认是否真的落盘的调用方使用（比如 RPC handler：
     * 谎报写入成功会被 {@code ProcessedCommandStore} 当成终态缓存，同一条命令之后不会重试）。
     * UI 侧现有的乐观更新继续用上面那个 fire-and-forget 版本，不受影响。
     */
    void updateOilCanSetSync(OilCanSet oilCanSet) throws DbException;

    /** 罐设置写库完成后回调（{@link #updateOilCanSet} 在写库线程上、写完之后才回调），传 null 取消。 */
    void setOilCanSetChangedListener(Runnable listener);

    // ---- OilCanTable（罐表标定，行数固定，只查不增删） ----

    List<OilCanTable> findAllOilCanTable();

    void updateOilCanTable(OilCanTable oilCanTable);

    /**
     * 等价于原 {@code DBDataManager.SaveOilCanTable(String)}：导出全部罐表数据到 Excel。
     */
    void exportOilCanTable(String path);

    // ---- ProbeToOil ----

    List<ProbeToOil> findAllProbeToOil();

    // ---- Sideleakage ----

    Sideleakage findLastSideleakage();

    List<Sideleakage> findSideleakageBetween(int limit, int offset, boolean desc, String beginTime, String endTime);

    // ---- OilCanMapper（测试数据映射表） ----

    List<OilCanMapper> findAllOilCanMapper();

    // ---- OilExploration（油气探测报警值，只读，无写入路径） ----

    List<OilExploration> findAllOilExploration();
}
