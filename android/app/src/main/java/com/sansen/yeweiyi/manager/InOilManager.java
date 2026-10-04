package com.sansen.yeweiyi.manager;

import android.util.Log;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.inout.InOutDirection;
import com.sansen.yeweiyi.logic.inout.InOutEvent;
import com.sansen.yeweiyi.logic.inout.InOutThresholds;
import com.sansen.yeweiyi.logic.inout.MultiTankInOutDetector;
import com.sansen.yeweiyi.logic.thingsboard.StockEventMapper;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.GsonUtil;
import com.sansen.yeweiyi.utils.Singleton;

import java.util.HashMap;
import java.util.Map;

/**
 * 出/入库事件检测的胶水层。
 *
 * <p>判定"一次液位变化是否构成一次出/入库事件"的规则已经下沉到不依赖 Android
 * 的纯状态机 {@link MultiTankInOutDetector}/{@link com.sansen.yeweiyi.logic.inout.TankLevelTracker}
 * （见 openspec/changes/rewrite-stock-inout-detection）。本类只负责：
 * <ol>
 *   <li>从 {@link Singleton} 组装阈值，把液位读数喂给状态机；</li>
 *   <li>在状态机判定"开始跟踪一段变化"时，从 {@link OilcanDataManager} 抓一份实时
 *       快照作为记录的起始字段（水位/温度等状态机本身不掌握的数据）；</li>
 *   <li>状态机产出事件后，补全结束字段并按事件自带的方向落库——不再重新推导方向。</li>
 * </ol>
 */
public class InOilManager {

    private static final String TAG = "InOilManager";

    private static final InOilManager INSTANCE = new InOilManager();

    private final MultiTankInOutDetector detector = new MultiTankInOutDetector();
    private final Map<Integer, DetailInoutRecord> pendingRecords = new HashMap<>();
    private final StockRepository stockRepository = StockRepositoryImpl.getInstance();

    private InOilManager() {
    }

    public static InOilManager getInstance() {
        return INSTANCE;
    }

    /**
     * 外部调用方法，需要实时地传入油罐号和当前高度。
     *
     * @param tankId   油罐号
     * @param curHeight 当前液位
     */
    public void setOil(int tankId, double curHeight) {
        Singleton singleton = Singleton.getInstance();
        if (!singleton.IsCreatIn && !singleton.IsCreateOut) {
            return;
        }

        InOutThresholds thresholds = InOutThresholds.of(
                singleton.InHight, singleton.InTime, singleton.OutHight, singleton.OutTime);

        boolean wasTracking = detector.isTracking(tankId);
        long now = System.currentTimeMillis();
        InOutEvent event = detector.onReading(tankId, curHeight, now, thresholds);
        boolean justStartedTracking = !wasTracking && detector.isTracking(tankId);

        if (justStartedTracking) {
            captureBeginSnapshot(tankId);
        }
        if (event != null) {
            handleEvent(event);
        }
    }

    /**
     * 暂停/恢复某个罐的自动检测——供 {@code InOilFragment} 人工卸油流程调用，
     * 暂停期间由人工维护记录，恢复后状态机以下一次读数重新建立基准。
     */
    public void setEnable(int tankId, boolean enable) {
        detector.setEnabled(tankId, enable);
        if (!enable) {
            pendingRecords.remove(tankId);
        }
    }

    private void captureBeginSnapshot(int tankId) {
        OilCan oilCan = OilcanDataManager.getInstance(MyApplication.getInstance()).getData(tankId);
        if (oilCan == null) {
            return;
        }
        DetailInoutRecord record = new DetailInoutRecord();
        record.setOilcanId(tankId);
        record.setBeginHeith(oilCan.getOilLevel());
        record.setBeginVolume(oilCan.getOilVolume());
        record.setBeginWater(oilCan.getWaterLevel());
        record.setBeginTcVolume(oilCan.getSurPlus());
        record.setBeginTemp(oilCan.getTemper());
        record.setBeginTime(DateUtils.getYYYMMddHHmmss());
        record.setBeginOilWeight(oilCan.getOilWeight());
        record.setBeginWaterVolume(oilCan.getWaterVolume());
        pendingRecords.put(tankId, record);
    }

    private void handleEvent(InOutEvent event) {
        Singleton singleton = Singleton.getInstance();
        boolean isIn = event.getDirection() == InOutDirection.IN;
        // 出/入库记录生成开关只影响是否落库，不影响判定与状态复位——状态机已经
        // 在产出事件的同时完成了复位，这里不落库也不会导致重复触发。
        if (isIn && !singleton.IsCreatIn) {
            pendingRecords.remove(event.getTankId());
            return;
        }
        if (!isIn && !singleton.IsCreateOut) {
            pendingRecords.remove(event.getTankId());
            return;
        }

        OilCan oilCan = OilcanDataManager.getInstance(MyApplication.getInstance()).getData(event.getTankId());
        if (oilCan == null) {
            pendingRecords.remove(event.getTankId());
            return;
        }

        double density = oilCan.getDensity();
        double beginVolume = DataUtils.getLitre(event.getStartHeight(), oilCan.getHightArray(), oilCan.getLitreArray());
        double endVolume = oilCan.getOilVolume();

        DetailInoutRecord detailInoutRecord = pendingRecords.remove(event.getTankId());
        if (detailInoutRecord == null) {
            // 理论上 justStartedTracking 时已经捕获过一次快照；这里只是防御性兜底
            // （比如服务重启后状态机是从中途状态起算的极端情况），避免空指针。
            detailInoutRecord = new DetailInoutRecord();
            detailInoutRecord.setOilcanId(event.getTankId());
            detailInoutRecord.setBeginHeith(event.getStartHeight());
            detailInoutRecord.setBeginVolume(beginVolume);
            detailInoutRecord.setBeginTime(DateUtils.getYYYMMddHHmmss());
        }
        detailInoutRecord.setOilType(Const.getOilType(oilCan.getOilType()));
        detailInoutRecord.setEndHeight(event.getEndHeight());
        detailInoutRecord.setEndVolume(endVolume);
        detailInoutRecord.setEndWater(oilCan.getWaterLevel());
        detailInoutRecord.setEndWaterVolume(oilCan.getWaterVolume());
        detailInoutRecord.setEndTcVolume(oilCan.getSurPlus());
        detailInoutRecord.setEndOilWeight(oilCan.getOilWeight());
        detailInoutRecord.setEndTemp(oilCan.getTemper());
        detailInoutRecord.setEndTime(DateUtils.getYYYMMddHHmmss());
        detailInoutRecord.setSaleVolume(endVolume - beginVolume);

        String time = DateUtils.getYYYMMddHHmmss();
        int savedId = 0;
        boolean savedIdFound;

        if (isIn) {
            OilInOut in = new OilInOut();
            in.setOilId(event.getTankId());
            in.setTime(time);
            in.setJieZhi(detailInoutRecord.getOilType());
            in.setBeginHeigth(DataUtils.doubleFoment(event.getStartHeight()));
            in.setEndHeigth(event.getEndHeight());
            in.setBeginVolum(beginVolume);
            in.setEndVolum(endVolume);
            // [已更正-2026-09-27] beginWeight 原来存的是容积差（没乘密度），跟 endWeight 不是同一种
            // 量纲，两者相减没有意义（真机测试暴露：算出来的质量是负数）。改成跟 beginVolum/endVolum
            // 一样的"事件始末快照"语义——beginWeight/endWeight 分别是事件开始/结束时刻的质量，
            // 结束-开始就是这次入库增加的质量，量纲上跟 beginVolum/endVolum 的关系完全对应。
            in.setBeginWeight(DataUtils.doubleFoment(beginVolume * density));
            in.setEndWeight(DataUtils.doubleFoment(endVolume * density));
            stockRepository.saveInRecord(in);
            OilInOut saved = stockRepository.findLastIn();
            savedIdFound = saved != null;
            if (savedIdFound) {
                savedId = saved.getId();
            }
        } else {
            OutStock out = new OutStock();
            out.setOilId(event.getTankId());
            out.setTime(time);
            // 说明：与重写前行为一致，出库记录不写 jieZhi（介质）字段——
            // 这是历史遗留的字段缺口，不在本次修复范围（见 design.md Non-Goals）。
            out.setBeginHeigth(DataUtils.doubleFoment(event.getStartHeight()));
            out.setEndHeigth(DataUtils.doubleFoment(event.getEndHeight()));
            out.setBeginVolum(beginVolume);
            out.setEndVolum(endVolume);
            // [已更正-2026-09-27] 同上——beginWeight/endWeight 改成事件始末的质量快照，
            // 跟 beginVolum/endVolum 的语义对应，而不是把容积差直接存进 beginWeight。
            out.setBeginWeight(DataUtils.doubleFoment(beginVolume * density));
            out.setEndWeight(DataUtils.doubleFoment(endVolume * density));
            stockRepository.saveOutRecord(out);
            OutStock saved = stockRepository.findLastOut();
            savedIdFound = saved != null;
            if (savedIdFound) {
                savedId = saved.getId();
            }
        }

        // 说明：saveInRecord/saveOutRecord 是异步落库（扔进线程池），
        // findLastIn/findLastOut 是同步查询，两者之间存在竞态——理论上可能读到
        // null（表为空时）或者上一条陈旧记录。DetailInoutRecord 目前入库、出库
        // 两个方向都没有真实读取方（详情弹窗的点击事件在 InOutAdapter/
        // OutStockAdapter 里是空实现，点击行没有任何反应，与本方法的改动无关，
        // 是更早就存在的问题），所以这里只做"不崩溃"的兜底：findLast 返回 null
        // 时跳过 DetailInoutRecord 的落库，不影响下面仍在正常使用的 socket 推送
        // 和报警提示。若日后详情弹窗被重新接通，需要连同这里的竞态一起用同步
        // 落库方式（如 saveBindingId）正式修复，而不是继续沿用这个兜底。
        if (savedIdFound) {
            detailInoutRecord.setId(savedId);
            stockRepository.saveDetail(detailInoutRecord);
        } else {
            Log.w(TAG, "processEvent: " + (isIn ? "findLastIn" : "findLastOut") + " 未取到刚保存的记录，跳过详情落库");
        }

        // 发给云端的必须是 StockEventMapper 产出的结构化 JSON，不能是 toString() 调试字符串——
        // 后者不是 JSON，云端完全解析不了，见 zhongchengyeweiyi#42 待办3。
        String stockEventJson = GsonUtil.toJsonStr(StockEventMapper.build(detailInoutRecord, isIn));
        SocketManager.getInstance(MyApplication.getInstance()).sendUnloadOil(stockEventJson);

        OilMessage oilMessage = new OilMessage();
        oilMessage.setDate(DateUtils.getYYYMMddHHmm());
        oilMessage.setOilCanId(event.getTankId());
        oilMessage.setType(isIn ? OilMessage.IN_OIL : OilMessage.OUT_OIL);
        AlarmManager.getInstance().addNoticesState(oilMessage);

        // 说明：重写前这里还有一段"云端上传出入库记录"的 TODO 脚手架
        // （SharePrefUtil.ISUPLOADING 分支），网络调用本体早已整段被注释掉，
        // 只留了一句 OutOilRecord 构造，且出库分支误用了入库事件残留的
        // in.getOilId()（本次要修的 NPE 隐患之一）。由于该分支从未真正执行过
        // 上传，这次重写直接连同已失效的脚手架一起去掉；如需恢复上传功能，
        // 需要单独评估并重新实现，不应该只是照抄这段死代码。
    }
}
