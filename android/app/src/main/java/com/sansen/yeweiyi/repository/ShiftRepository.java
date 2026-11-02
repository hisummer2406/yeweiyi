package com.sansen.yeweiyi.repository;

import android.util.SparseArray;

import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.CommitWork;

import java.util.List;

/**
 * 交班数据的唯一读写入口，取代对 {@code DBDataManager} 交班相关方法的直接调用。
 * 见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries §3。
 */
public interface ShiftRepository {

    /**
     * 开始交班：记录各油罐的起始高度/体积。等价于原
     * {@code DBDataManager.CommitWork(SparseArray)} -> {@code CommitWorkStart(0, oilMap)}。
     */
    void startShift(SparseArray<OilCan> oilMap);

    /**
     * 结束交班：取最近一条未结束的交班记录，补全结束高度/体积并更新。等价于原
     * {@code DBDataManager.CommitWork(SparseArray, String)} -> {@code CommitWork(0, startTime, oilMap)}。
     */
    void endShift(SparseArray<OilCan> oilMap, String startTime);

    List<CommitWork> findAll();

    List<CommitWork> findAllSorted(boolean desc);

    List<CommitWork> findPage(int pageSize, int pageIndex, boolean desc);

    List<CommitWork> findBetween(int limit, int offset, boolean desc, String beginTime, String endTime);

    CommitWork findLast();

    CommitWork findById(int id);

    void update(CommitWork commitWork);

    void delete(CommitWork commitWork);

    void deleteAll();
}
