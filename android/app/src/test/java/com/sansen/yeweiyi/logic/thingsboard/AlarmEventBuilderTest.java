package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.OilMessage;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AlarmEventBuilderTest {

    private static final Gson GSON = new Gson();
    private static final String TEXT = "1 号储罐 高高液位报警";

    private static OilMessage hhAlarm(double value) {
        OilMessage m = new OilMessage();
        m.setType(OilMessage.HHEIGHT_OIL_ALARM);
        m.setOilCanId(1);
        m.setValue(value);
        return m;
    }

    @Test
    public void raisedEventMatchesFixture() {
        Map<String, Object> event = AlarmEventBuilder.build(hhAlarm(9800.0), false, TEXT, 1735689600000L);
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, 1735689600000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("alarm_event_raised.json");
        assertEquals(expected, actual);
    }

    @Test
    public void recoveredEventMatchesFixtureAndSharesAlarmIdWithRaise() {
        String alarmId = AlarmEventBuilder.defaultAlarmId(hhAlarm(9800.0));

        Map<String, Object> event = AlarmEventBuilder.build(hhAlarm(9000.0), true, TEXT, 1735689630000L);
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, 1735689630000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("alarm_event_recovered.json");
        assertEquals(expected, actual);

        assertEquals(alarmId, event.get("alarmId"));
    }

    @Test
    public void alarmIdIsDeterministicByTypeAndOilCanId() {
        assertEquals(AlarmEventBuilder.defaultAlarmId(hhAlarm(1.0)), AlarmEventBuilder.defaultAlarmId(hhAlarm(2.0)));
    }

    @Test
    public void unmappableTypeReturnsNullInsteadOfGuessing() {
        OilMessage outOil = new OilMessage();
        outOil.setType(OilMessage.OUT_OIL); // 出入库——云端封闭报警类型集合里没有对应的家
        outOil.setOilCanId(1);

        assertNull(AlarmEventBuilder.build(outOil, false, "无所谓的文案", 1735689600000L));
        assertNull(AlarmEventBuilder.defaultAlarmId(outOil));
    }

    @Test
    public void allSevenMappedTypesProduceNonNullEvents() {
        int[] mappedTypes = {
                OilMessage.HHEIGHT_OIL_ALARM, OilMessage.LLOW_OIL_ALARM, OilMessage.HEIGHT_OIL_ALARM,
                OilMessage.LOW_OIL_ALARM, OilMessage.HEIGHT_WATER, OilMessage.HEIGHT_TEMPER,
                OilMessage.PRO_ERR_CONN
        };
        for (int type : mappedTypes) {
            OilMessage message = new OilMessage();
            message.setType(type);
            message.setOilCanId(1);
            Map<String, Object> event = AlarmEventBuilder.build(message, false, "x", 1L);
            org.junit.Assert.assertNotNull("type=" + type + " 应该有对应的云端报警类型", event);
        }
    }
}
