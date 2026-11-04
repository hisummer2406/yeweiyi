package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.db.DetailInoutRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 对照 {@code yeweiyi-cloud} 的共享 fixture（{@code src/test/resources/protocol/v1/} 下的
 * {@code stock_event_in.json}/{@code stock_event_out.json}，原样副本）——不是 Android 单方面定义的样例。
 * <p>
 * {@link StockEventMapper#build} 把 {@code DetailInoutRecord.beginTime}/{@code endTime}
 * （{@code yyyy-MM-dd HH:mm[:ss]} 本地时间字符串，无时区）反解成 epoch 毫秒，结果依赖 JVM 默认
 * 时区——固定为 {@code Asia/Shanghai}（这批硬件终端的实际部署时区），避免测试结果随运行
 * 机器的系统时区漂移。
 */
public class StockEventMapperTest {

    private static final Gson GSON = new Gson();
    private TimeZone originalTimeZone;

    @Before
    public void pinDeviceTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    private static DetailInoutRecord inRecord() {
        DetailInoutRecord r = new DetailInoutRecord();
        r.setOilcanId(1);
        r.setOilType("0号柴油");
        r.setBeginTime("2025-09-01 16:00");
        r.setEndTime("2025-09-01 17:00");
        r.setBeginHeith(402.0);
        r.setEndHeight(1642.5);
        r.setBeginVolume(6030.0);
        r.setEndVolume(24030.0);
        r.setBeginWater(18.3);
        r.setEndWater(18.5);
        r.setBeginWaterVolume(210.4);
        r.setEndWaterVolume(212.7);
        r.setBeginOilWeight(5065.2);
        r.setEndOilWeight(20185.2);
        r.setBeginTemp(22.1);
        r.setEndTemp(23.4);
        r.setDuringDelivery(420.0); // 补录过的边卸边卖销量，见类注释
        return r;
    }

    private static DetailInoutRecord outRecord() {
        DetailInoutRecord r = new DetailInoutRecord();
        r.setOilcanId(1);
        r.setOilType("0号柴油");
        r.setBeginTime("2025-09-01 20:00");
        r.setEndTime("2025-09-01 21:00");
        r.setBeginHeith(1642.5);
        r.setEndHeight(1298.0);
        r.setBeginVolume(24030.0);
        r.setEndVolume(19030.0);
        r.setBeginWater(18.5);
        r.setEndWater(18.5);
        r.setBeginWaterVolume(212.7);
        r.setEndWaterVolume(212.7);
        r.setBeginOilWeight(20185.2);
        r.setEndOilWeight(15985.2);
        r.setBeginTemp(23.4);
        r.setEndTemp(23.1);
        // duringDelivery 留默认 0——出库没有"边卸边卖"，字段应被省略
        return r;
    }

    @Test
    public void inEventMatchesSharedFixture() {
        DetailInoutRecord record = inRecord();
        Map<String, Object> event = StockEventMapper.build(record, true);
        Map<String, Object> payload = StockEventMapper.wrapAsTelemetry(event, (Long) event.get("tsMs"));

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadProtocolV1("stock_event_in.json");
        assertEquals(expected, actual);
    }

    @Test
    public void outEventMatchesSharedFixture() {
        DetailInoutRecord record = outRecord();
        Map<String, Object> event = StockEventMapper.build(record, false);
        Map<String, Object> payload = StockEventMapper.wrapAsTelemetry(event, (Long) event.get("tsMs"));

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadProtocolV1("stock_event_out.json");
        assertEquals(expected, actual);
    }

    @Test
    public void duringDeliveryIsOmittedWhenNeverReconciled() {
        Map<String, Object> event = StockEventMapper.build(outRecord(), false);
        assertFalse("未核对过边卸边卖销量时不应该带这个字段（omitempty 语义）",
                event.containsKey("duringDeliveryVolumeL"));
    }

    @Test
    public void duringDeliveryIsPresentOnceReconciled() {
        Map<String, Object> event = StockEventMapper.build(inRecord(), true);
        assertTrue(event.containsKey("duringDeliveryVolumeL"));
        assertEquals(420.0, (Double) event.get("duringDeliveryVolumeL"), 0.0001);
    }

    @Test
    public void eventIdIsDeterministicByDirectionTankAndBeginTime() {
        String a = StockEventMapper.defaultEventId(inRecord(), true, 1756713600000L);
        String b = StockEventMapper.defaultEventId(inRecord(), true, 1756713600000L);
        assertEquals(a, b);
        assertEquals("in-1-1756713600000", a);
    }

    @Test
    public void secondPrecisionTimesAreKept() {
        DetailInoutRecord record = inRecord();
        record.setBeginTime("2025-09-01 16:00:12");
        record.setEndTime("2025-09-01 17:00:34");
        Map<String, Object> event = StockEventMapper.build(record, true);

        assertEquals("in-1-1756713612000", event.get("eventId"));
        assertEquals(1756713612000L, event.get("beginTimeMs"));
        assertEquals(1756717234000L, event.get("endTimeMs"));
        assertEquals(1756717234000L, event.get("tsMs"));
    }

    @Test
    public void sameTankSameMinuteGetsDistinctEventIdsWithSecondPrecision() {
        DetailInoutRecord first = inRecord();
        first.setBeginTime("2025-09-01 16:00:01");
        DetailInoutRecord second = inRecord();
        second.setBeginTime("2025-09-01 16:00:40");
        assertFalse("同一罐同一分钟内开始的两笔，eventId 不能相同，否则云端会把两笔合并成一笔",
                StockEventMapper.build(first, true).get("eventId")
                        .equals(StockEventMapper.build(second, true).get("eventId")));
    }

    @Test
    public void netVolumeIsSignedByDirectionNotAbsoluteValue() {
        Map<String, Object> inEvent = StockEventMapper.build(inRecord(), true);
        Map<String, Object> outEvent = StockEventMapper.build(outRecord(), false);
        assertEquals(18000.0, (Double) inEvent.get("netVolumeL"), 0.0001);
        assertEquals(-5000.0, (Double) outEvent.get("netVolumeL"), 0.0001);
    }

    @Test
    public void netVolumeIsRoundedToOneDecimal() {
        DetailInoutRecord record = outRecord();
        record.setBeginVolume(649.3);
        record.setEndVolume(527.7);
        // 真机上报过 -121.59999999999991
        assertEquals(Double.valueOf(-121.6), StockEventMapper.build(record, false).get("netVolumeL"));
    }

    @Test
    public void sourceIsOmittedForAutoDetectedEvents() {
        DetailInoutRecord record = inRecord();
        assertEquals("DetailInoutRecord.source 默认应为 AUTO", StockEventMapper.SOURCE_AUTO, record.getSource());
        Map<String, Object> event = StockEventMapper.build(record, true);
        assertFalse("自动检测事件不应该带 source，保持跟共享 fixture 字节相同",
                event.containsKey("source"));
    }

    @Test
    public void sourceIsPresentForManualBackfillEvents() {
        DetailInoutRecord record = inRecord();
        record.setSource(StockEventMapper.SOURCE_MANUAL);
        Map<String, Object> event = StockEventMapper.build(record, true);
        assertEquals(StockEventMapper.SOURCE_MANUAL, event.get("source"));
    }

    // ---- zhongchengyeweiyi#49：deliveredWeightKg / oilTypeIndex（可选字段）----

    @Test
    public void inEventWithDensityAndOilTypeIndexMatchesFixture() {
        JsonObject actual = GSON.toJsonTree(StockEventMapper.wrapAsTelemetry(
                StockEventMapper.build(inRecord(), true, 1, 0.84), 1756717200000L)).getAsJsonObject();
        assertEquals(FixtureLoader.loadProtocolV1("stock_event_in_delivered_weight.json"), actual);
    }

    @Test
    public void outEventDeliveredWeightIsPositive() {
        Map<String, Object> event = StockEventMapper.build(outRecord(), false, 1, 0.84);
        // −(−5000.0) × 0.84
        assertEquals(4200.0, (Double) event.get("deliveredWeightKg"), 1e-9);
    }

    @Test
    public void withoutDensityTheNewFieldsAreOmitted() {
        Map<String, Object> event = StockEventMapper.build(inRecord(), true, null, null);
        assertFalse(event.containsKey("deliveredWeightKg"));
        assertFalse(event.containsKey("oilTypeIndex"));
    }

    @Test
    public void nonPositiveDensityIsTreatedAsUnknown() {
        assertFalse(StockEventMapper.build(inRecord(), true, 1, 0.0).containsKey("deliveredWeightKg"));
    }
}
