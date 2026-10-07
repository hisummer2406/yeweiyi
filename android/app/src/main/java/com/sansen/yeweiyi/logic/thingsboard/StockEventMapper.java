package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.db.DetailInoutRecord;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出/入库事件（{@code stockEvent}）的立即上报 payload：{@code DetailInoutRecord} 关表的那一刻
 * 单独发一条，不进 30 秒快照队列。角色与 {@link AlarmEventBuilder} 完全对称——见该类顶部说明。
 * <p>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接构造 {@link DetailInoutRecord} 校验字段。
 * <p>
 * 字段形状对齐 {@code yeweiyi-cloud} 侧 {@code internal/protocol.StockEvent}
 * （{@code docs/protocol-v1-stock-event.md}，核对于 zhongchengyeweiyi#42）。三点必须记住：
 * <ul>
 *   <li>{@code beginTcVolume}/{@code endTcVolume}（名字读作标定体积，实际赋值来自
 *       {@code OilCan.getSurPlus()}，含义未经设备端确认）与 {@code saleVolume}（{@code duringDelivery}
 *       的计算输入，云端只要结果不要输入）——两列刻意不进协议，见该文档 §3 "两列刻意不进 V1"；</li>
 *   <li>{@code duringDeliveryVolumeL} 只有在 {@code InOilFragment} 的"边卸边卖"人工核对流程
 *       （补录 during-delivery 销量、点保存）跑完之后才有真实值——事件首次落库、首次发送的那一刻
 *       这个数必然是 0。0 与"从未核对过"在协议里无法区分（{@code omitempty} 语义，云端 Go 结构体
 *       同样区分不了），这是双方都接受的已知限制，不是这次改动引入的新问题；</li>
 *   <li>{@code beginTimeMs}/{@code endTimeMs} 由 {@link DetailInoutRecord#getBeginTime()}/
 *       {@link DetailInoutRecord#getEndTime()} 反解得来。自动检测（{@code InOilManager}）写的是
 *       {@code yyyy-MM-dd HH:mm:ss}，精确到秒——只到分钟时，同一罐同一方向同一分钟内开始的两笔
 *       {@code eventId} 相同，会被云端按 {@code eventId} 合并成一笔；人工补录（{@code BuLuFragment}）
 *       只有操作员填的 {@code yyyy-MM-dd HH:mm}。两种格式都接受。</li>
 * </ul>
 * <p>
 * <b>{@code source} 字段是新增的、尚未跨仓库确认的协议扩展</b>（zhongchengyeweiyi#42 待办2）：
 * {@code BuLuFragment} 手动补录（断电/故障导致自动检测漏记后人工补一条）之前完全绕开这条
 * 上报通道——补录事件对云端/小程序不可见。现在补录也复用这条通道上报。跟
 * {@code duringDeliveryVolumeL} 一样用 {@code omitempty} 语义：只有 {@link #SOURCE_MANUAL}
 * （补录）才带这个字段，{@link #SOURCE_AUTO}（{@code InOilManager} 自动检测，默认值）时不带——
 * 这样已经和 yeweiyi-cloud 核对过、字节同步的两份 fixture（自动检测样例）不用改，新字段只在
 * 新场景（补录）里出现，云端在决定怎么消费之前，未知字段会被 {@code encoding/json} 直接忽略、
 * 不报错也不存，不影响现有自动检测事件的解析。
 */
public final class StockEventMapper {

    public static final String DIRECTION_IN = "IN";
    public static final String DIRECTION_OUT = "OUT";

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    // DetailInoutRecord.beginTime/endTime 的两种写入格式：自动检测到秒（DateUtils.getYYYMMddHHmmssWithSeconds()），
    // 人工补录到分钟。必须先试到秒的：按分钟格式解析带秒的字符串不会报错，只会把秒丢掉。
    private static final String DEVICE_TIME_PATTERN_SECONDS = "yyyy-MM-dd HH:mm:ss";
    private static final String DEVICE_TIME_PATTERN_MINUTES = "yyyy-MM-dd HH:mm";

    private StockEventMapper() {
    }

    /**
     * @param isIn true=入库（{@code InOilManager}/{@code InOilFragment} 的"进油"分支），
     *             false=出库。方向在调用点是已知量（{@code InOilManager.handleEvent} 里的
     *             {@code isIn}），记录本身不携带方向，所以必须由调用方传入，不能反推。
     * @return 对齐云端 {@code internal/protocol.StockEvent} 的字段表，交给
     * {@code ThingsBoardManager} 包成 telemetry payload。
     */
    public static Map<String, Object> build(DetailInoutRecord record, boolean isIn) {
        long beginTimeMs = parseDeviceTime(record.getBeginTime());
        long endTimeMs = parseDeviceTime(record.getEndTime());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", defaultEventId(record, isIn, beginTimeMs));
        event.put("tank", record.getOilcanId());
        event.put("direction", isIn ? DIRECTION_IN : DIRECTION_OUT);
        event.put("oilType", record.getOilType());
        event.put("beginTimeMs", beginTimeMs);
        event.put("endTimeMs", endTimeMs);
        event.put("beginLevelMm", record.getBeginHeith());
        event.put("endLevelMm", record.getEndHeight());
        event.put("beginVolumeL", record.getBeginVolume());
        event.put("endVolumeL", record.getEndVolume());
        event.put("beginWaterLevelMm", record.getBeginWater());
        event.put("endWaterLevelMm", record.getEndWater());
        event.put("beginWaterVolumeL", record.getBeginWaterVolume());
        event.put("endWaterVolumeL", record.getEndWaterVolume());
        event.put("beginWeightKg", record.getBeginOilWeight());
        event.put("endWeightKg", record.getEndOilWeight());
        event.put("beginTempC", record.getBeginTemp());
        event.put("endTempC", record.getEndTemp());
        // 保留 1 位小数，跟其它体积字段一致；直接相减会带出 -121.59999999999991 这种浮点误差
        event.put("netVolumeL", Math.round((record.getEndVolume() - record.getBeginVolume()) * 10) / 10.0);
        // omitempty 语义：只有人工核对过 during-delivery 销量（非 0）才带这个字段，
        // 跟云端 Go 结构体的 `json:"duringDeliveryVolumeL,omitempty"` 行为保持一致。
        if (record.getDuringDelivery() != 0.0) {
            event.put("duringDeliveryVolumeL", record.getDuringDelivery());
        }
        // omitempty：自动检测（默认值）不带这个字段，跟已核对过的两份共享 fixture 保持字节相同；
        // 只有补录（SOURCE_MANUAL）才带，见类注释。
        if (SOURCE_MANUAL.equals(record.getSource())) {
            event.put("source", SOURCE_MANUAL);
        }
        event.put("tsMs", endTimeMs);
        return event;
    }

    /**
     * 建议式 eventId：{@code "{in|out}-{罐号}-{beginTimeMs}"}——文档要求"补投重发保持不变"，
     * 只要 {@code beginTime} 不变这个值就是确定性的；补投发送的是已经序列化好的报文，不会重新计算。
     * 人工补录只到分钟，同一罐同一方向同一分钟补录两次会得到相同的 eventId（云端当作同一笔的重发）。
     * <b>已知缺口</b>：{@code InOilManager}
     * 里有一条防御性兜底分支（服务重启导致快照丢失时）会用"现在"顶替真正的 beginTime，
     * 这种情况下 eventId 会和"如果没重启"本该算出的值不一样——跨掉电重启的稳定性没有
     * 完全解决，见 zhongchengyeweiyi#42 待办3 的待确认项之一。
     */
    public static String defaultEventId(DetailInoutRecord record, boolean isIn, long beginTimeMs) {
        return (isIn ? "in" : "out") + "-" + record.getOilcanId() + "-" + beginTimeMs;
    }

    /** ThingsBoard telemetry 上行 payload：{@code {"ts":..., "values": {"stockEvent": {...}}}}。 */
    public static Map<String, Object> wrapAsTelemetry(Map<String, Object> stockEvent, long tsMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stockEvent", stockEvent);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
    }

    /** @return 解析失败（理论上不会发生，格式完全由内部生成）时返回 0，不抛异常、不崩事件流水线。 */
    private static long parseDeviceTime(String deviceTime) {
        if (deviceTime == null || deviceTime.isEmpty()) {
            return 0L;
        }
        try {
            return new SimpleDateFormat(DEVICE_TIME_PATTERN_SECONDS).parse(deviceTime).getTime();
        } catch (ParseException e) {
            // 人工补录的分钟格式，往下试
        }
        try {
            return new SimpleDateFormat(DEVICE_TIME_PATTERN_MINUTES).parse(deviceTime).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }
}
