package com.sansen.yeweiyi.repository;

import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.db.Alarm;

import java.util.HashMap;
import java.util.List;

/**
 * 报警数据的唯一读写入口，取代对 {@code DBDataManager} 报警相关方法的直接调用。
 * 见 openspec/changes/layer-data-and-hardware-boundaries §5。
 */
public interface AlarmRepository {

    /**
     * 等价于原 {@code DBDataManager.saveAlarm(int, OilMessage)}：
     * {@code PRO_ERR_CONN} 类型不落库，其余类型写入一条报警记录。
     */
    void saveAlarm(int oilCanPos, OilMessage oilMessage);

    /**
     * 等价于原 {@code DBDataManager.setAramRecord}：仅在 {@code Singleton.IsCreateAlarm} 打开时写入。
     */
    void saveAlarmRecord(String info, String guanHao, String jieZhi);

    void delete(Alarm alarm);

    void deleteAll();

    List<Alarm> findAll();

    List<Alarm> findPage(int pageSize, int pageIndex, boolean desc);

    List<Alarm> findBetween(int limit, int offset, boolean desc, String beginTime, String endTime);

    HashMap<String, Object> findPaged(String id, String beginTime, String endTime, int limit, int offset, boolean order);
}
