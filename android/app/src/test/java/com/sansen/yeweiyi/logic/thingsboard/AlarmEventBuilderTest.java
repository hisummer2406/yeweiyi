package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.OilMessage;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AlarmEventBuilderTest {

    private static final Gson GSON = new Gson();

    private static OilMessage hhAlarm(double value) {
        OilMessage m = new OilMessage();
        m.setType(OilMessage.HHEIGHT_OIL_ALARM);
        m.setOilCanId(1);
        m.setValue(value);
        return m;
    }

    @Test
    public void raisedEventMatchesFixture() {
        Map<String, Object> event = AlarmEventBuilder.build(hhAlarm(9800.0), false, 1735689600000L);
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, 1735689600000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("alarm_event_raised.json");
        assertEquals(expected, actual);
    }

    @Test
    public void recoveredEventMatchesFixtureAndSharesEventIdWithRaise() {
        String eventId = AlarmEventBuilder.defaultEventId(hhAlarm(9800.0));

        Map<String, Object> event = AlarmEventBuilder.build(hhAlarm(9000.0), true, 1735689630000L);
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, 1735689630000L);

        JsonObject actual = GSON.toJsonTree(payload).getAsJsonObject();
        JsonObject expected = FixtureLoader.loadObject("alarm_event_recovered.json");
        assertEquals(expected, actual);

        assertEquals(eventId, event.get("eventId"));
    }

    @Test
    public void eventIdIsDeterministicByTypeAndOilCanId() {
        assertEquals(AlarmEventBuilder.defaultEventId(hhAlarm(1.0)), AlarmEventBuilder.defaultEventId(hhAlarm(2.0)));
    }
}
