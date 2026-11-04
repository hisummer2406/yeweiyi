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

    /** 交班记录新增或修改（开始、结束、手工修改）并已落库后回调，在后台线程上执行。删除不回调。 */
    interface ShiftChangedListener {
        void onShiftChanged(int localId);
    }

    void setShiftChangedListener(ShiftChangedListener listener);

    /**
     * 开始交班：记录各油罐的起始高度/体积/介质，时间到秒并存开始 epoch（issue #49）。
     */
    void startShift(SparseArray<OilCan> oilMap);

    /**
     * 结束交班：取 id 最大的一条交班记录（不论是否已结束），按罐号补全结束数据并更新；
     * 规则见 {@code logic.shift.ShiftRecordBuilder#end}。
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
