package com.sansen.yeweiyi.logic.thingsboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** stockRecord 报文（契约 §2.2）。 */
public class StockRecordMapperTest {

    private static final Gson GSON = new Gson();
    private static final long TS = 1762153200000L;

    @Test
    public void multiTankRecordMatchesFixture() {
        Map<String, Object> record = StockRecordMapper.build(StockRecordMapper.TRIGGER_SCHEDULED, TS, Arrays.asList(
                new StockRecordMapper.Tank(1, "92#", 5, 1498.0, 17960.2, 12.0, 18.6, 13290.5),
                new StockRecordMapper.Tank(2, "0#", 1, 960.0, 10790.0, 0.0, 17.9, 10790.0 * 0.84)));

        JsonObject actual = GSON.toJsonTree(StockRecordMapper.wrapAsTelemetry(record, TS)).getAsJsonObject();

        assertEquals(FixtureLoader.loadProtocolV1("stock_record.json"), actual);
    }

    @Test
    public void unknownTriggerAndMissingOilTypeAreOmitted() {
        Map<String, Object> record = StockRecordMapper.build(null, TS, Collections.singletonList(
                new StockRecordMapper.Tank(1, null, null, 1.0, 1.0, 0.0, 1.0, 1.0)));

        JsonObject json = GSON.toJsonTree(record).getAsJsonObject();
        assertFalse(json.has("trigger"));
        JsonObject tank = json.getAsJsonArray("tanks").get(0).getAsJsonObject();
        assertFalse(tank.has("oilType"));
        assertFalse(tank.has("oilTypeIndex"));
    }

    @Test
    public void recordIdIsDerivedFromTsMs() {
        assertEquals("stock-" + TS, StockRecordMapper.build(StockRecordMapper.TRIGGER_MANUAL, TS,
                Collections.<StockRecordMapper.Tank>emptyList()).get("recordId"));
    }
}
