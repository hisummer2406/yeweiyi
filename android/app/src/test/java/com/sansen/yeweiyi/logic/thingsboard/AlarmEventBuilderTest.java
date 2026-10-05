package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.OilMessage;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 报文跟共享 fixture 的逐 key 比对在 {@link ProtocolV1ContractTest}，这里只测 alarmId 生成和类型映射。
 */
public class AlarmEventBuilderTest {

    @Test
    public void alarmIdIsPerOccurrence() {
        String first = AlarmEventBuilder.alarmIdFor(OilMessage.HHEIGHT_OIL_ALARM, 1, 1735689600000L);
        String second = AlarmEventBuilder.alarmIdFor(OilMessage.HHEIGHT_OIL_ALARM, 1, 1735689630000L);

        assertEquals("HIGH_HIGH_LEVEL-1-1735689600000", first);
        assertNotEquals("同一罐同一类型的两次报警必须是不同的 alarmId（协议 §4.1）", first, second);
    }

    @Test
    public void unmappableTypeReturnsNullInsteadOfGuessing() {
        // 出入库——云端封闭报警类型集合里没有对应的家
        assertFalse(AlarmEventBuilder.isReportable(OilMessage.OUT_OIL));
        assertNull(AlarmEventBuilder.alarmIdFor(OilMessage.OUT_OIL, 1, 1L));
        assertNull(AlarmEventBuilder.build(OilMessage.OUT_OIL, 1, "x", false, "无所谓的文案", 0.0, 1L));
    }

    @Test
    public void lowTemperatureIsNotReportable() {
        assertFalse(AlarmEventBuilder.isReportable(OilMessage.LOW_TEMPER));
    }

    @Test
    public void allSevenMappedTypesProduceNonNullEvents() {
        int[] mappedTypes = {
                OilMessage.HHEIGHT_OIL_ALARM, OilMessage.LLOW_OIL_ALARM, OilMessage.HEIGHT_OIL_ALARM,
                OilMessage.LOW_OIL_ALARM, OilMessage.HEIGHT_WATER, OilMessage.HEIGHT_TEMPER,
                OilMessage.PRO_ERR_CONN
        };
        for (int type : mappedTypes) {
            assertTrue("type=" + type + " 应该可上报", AlarmEventBuilder.isReportable(type));
            Map<String, Object> event = AlarmEventBuilder.build(type, 1, "id", false, "x", 0.0, 1L);
            assertNotNull("type=" + type + " 应该有对应的云端报警类型", event);
        }
    }

    /** 补发队列淘汰策略：只有"产生"是低优先级；"产生"和"恢复"按 alarmId 同组。 */
    @Test
    public void evictionPolicyMarksOnlyActiveAsLowPriorityAndGroupsByAlarmId() {
        PendingAlarmStore.EvictionPolicy policy = AlarmEventBuilder.EVICTION_POLICY;
        String active = FixtureLoader.loadProtocolV1("alarm_active.json").toString();
        String cleared = FixtureLoader.loadProtocolV1("alarm_cleared.json").toString();
        String stock = FixtureLoader.loadProtocolV1("stock_event_in.json").toString();
        String cmdResult = FixtureLoader.loadProtocolV1("cmd_result_succeeded.json").toString();

        assertTrue(policy.isLowPriority(active));
        assertFalse("恢复事件不能单独被丢", policy.isLowPriority(cleared));
        assertFalse(policy.isLowPriority(stock));
        assertFalse(policy.isLowPriority(cmdResult));
        assertFalse("解析失败按非报警处理，宁可多留不误删", policy.isLowPriority("not json"));

        assertEquals(policy.groupKey(active), policy.groupKey(cleared));
        assertNull(policy.groupKey(stock));
        assertNull(policy.groupKey("not json"));

        assertTrue("恢复事件受保护，兜底淘汰也跳过（代码审核 M3）", policy.isProtected(cleared));
        assertFalse(policy.isProtected(active));
        assertFalse(policy.isProtected(stock));
        assertFalse(policy.isProtected("not json"));
    }

    @Test
    public void recoveredUsesClearedStatusAndKeepsGivenAlarmId() {
        Map<String, Object> event = AlarmEventBuilder.build(OilMessage.HEIGHT_OIL_ALARM, 2, "HIGH_LEVEL-2-5", true,
                "2号罐高液位报警", 1500.0, 10L);

        assertEquals("CLEARED", event.get("status"));
        assertEquals("HIGH_LEVEL-2-5", event.get("alarmId"));
        assertEquals(2, event.get("tank"));
        assertEquals(1500.0, event.get("value"));
    }
}
