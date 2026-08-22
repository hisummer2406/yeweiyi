package com.sansen.yeweiyi.repository;

import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.db.StockSave;

import java.util.HashMap;
import java.util.List;

/**
 * 出入库数据的唯一读写入口，覆盖 {@code OilInOut}（入库）/{@code OutStock}（出库）/
 * {@code StockSave}（库存快照）/{@code DetailInoutRecord}（出入库详情，随事件一起写入）
 * 四个实体，取代对 {@code DBDataManager} 出入库相关方法的直接调用。
 * 见 openspec/changes/layer-data-and-hardware-boundaries §4。
 */
public interface StockRepository {

    // ---- OilInOut（入库） ----

    void saveInRecord(OilInOut in);

    void updateIn(OilInOut in);

    void deleteIn(OilInOut in);

    void deleteAllIn();

    OilInOut findLastIn();

    OilInOut findInById(int id);

    List<OilInOut> findAllIn();

    List<OilInOut> findInPage(int pageSize, int pageIndex, boolean desc);

    List<OilInOut> findInPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc);

    List<OilInOut> findInBetween(int limit, int offset, boolean desc, String beginTime, String endTime);

    HashMap<String, Object> findInPaged(String oilId, String beginTime, String endTime, int limit, int offset, boolean order);

    // ---- OutStock（出库） ----

    void saveOutRecord(OutStock out);

    void deleteOut(OutStock out);

    void deleteAllOut();

    OutStock findLastOut();

    List<OutStock> findAllOut();

    List<OutStock> findOutPage(int pageSize, int pageIndex, boolean desc);

    List<OutStock> findOutPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc);

    // ---- StockSave（库存快照） ----

    void saveStockRecord(StockSave stockSave);

    void deleteStock(StockSave stockSave);

    void deleteAllStock();

    List<StockSave> findAllStock();

    List<StockSave> findStockPage(int pageSize, int pageIndex, boolean desc);

    List<StockSave> findStockPageByOil(int oilCanId, int pageSize, int pageIndex, boolean desc);

    // ---- DetailInoutRecord（出入库详情） ----

    void saveDetail(DetailInoutRecord record);

    void updateDetail(DetailInoutRecord record);

    DetailInoutRecord findDetailById(int id);
}
