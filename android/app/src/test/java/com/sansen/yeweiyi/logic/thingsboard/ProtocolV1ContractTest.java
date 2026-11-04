package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcWirePayloads;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 跨仓库契约测试：Android 产出的报文必须跟 {@code yeweiyi-cloud/fixtures/protocol/v1/} 的共享
 * fixture 逐 key 相等（副本在 {@code src/test/resources/protocol/v1/}，来源与同步方法见该目录
 * README.md）。出入库事件的比对在 {@link StockEventMapperTest}，这里不重复。
 * <p>
 * <b>已知缺口</b>：Android 目前还做不到跟 fixture 完全一致的字段，从逐 key 比对里排除，并由
 * {@code knownGap_*} 测试固定当前行为。缺口补上后对应测试会变红——那时删掉它，并把字段加回比对。
 */
public class ProtocolV1ContractTest {

    private static final Gson GSON = new Gson();
    private static final String COMMAND_ID = "c1a7e3d8-5b62-4d19-8f30-1e9a4c77b201";

    // ---------------------------------------------------------------- 遥测快照

    /** 执行方案 V1-T4：waterRatioPct 暂不上报——真实模式下 OilCan.waterRatio 从未被计算，恒为 0。 */
    private static final List<String> GAP_WATER_RATIO_PCT = Arrays.asList("tank1.waterRatioPct", "tank2.waterRatioPct");

    private static NetOilCan tank(int id, String oilType, double oilLevel, double waterLevel, double temp,
                                  double oilVolume, double waterVolume, double oilWeight, double nullVolume,
                                  double oilRatioPct, String alarmState, boolean connected) {
        NetOilCan t = new NetOilCan();
        t.setId(id);
        t.setOiltype(oilType);
        t.setOilLevel(oilLevel);
        t.setWaterLevel(waterLevel);
        t.setTemp(temp);
        t.setOilVolume(oilVolume);
        t.setWaterVolume(waterVolume);
        t.setOilWeight(oilWeight);
        t.setNullVolume(nullVolume);
        t.setOilRatioPct(oilRatioPct);
        t.setAlarmState(alarmState);
        t.setConnect(connected);
        return t;
    }

    /** 跟 telemetry_snapshot.json 同一组读数。 */
    private static Map<String, Object> buildSnapshotLikeFixture() {
        List<NetOilCan> tanks = Arrays.asList(
                tank(1, "0号柴油", 1842.5, 18.3, 23.6, 28450.2, 210.4, 23901.7, 1549.8, 94.8,
                        ActiveAlarmTracker.STATE_NORMAL, true),
                tank(2, "92号汽油", 402.0, 0.0, 22.1, 6030.0, 0.0, 4522.5, 23970.0, 20.1,
                        ActiveAlarmTracker.STATE_FAULT, false));
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, 1756713600000L, 4271L);
        return TelemetrySnapshotBuilder.wrapWithTimestamp(values, 1756713600000L);
    }

    @Test
    public void telemetrySnapshotMatchesSharedFixtureExceptKnownGaps() {
        JsonObject expected = FixtureLoader.loadProtocolV1("telemetry_snapshot.json");
        JsonObject actual = GSON.toJsonTree(buildSnapshotLikeFixture()).getAsJsonObject();

        removeValues(expected, GAP_WATER_RATIO_PCT);
        assertEquals(expected, actual);
    }

    @Test
    public void knownGap_waterRatioPctNotReported() {
        JsonObject values = GSON.toJsonTree(buildSnapshotLikeFixture()).getAsJsonObject().getAsJsonObject("values");
        for (String key : GAP_WATER_RATIO_PCT) {
            assertFalse("开始上报 waterRatioPct 后删除本测试，并把 " + key + " 加回逐 key 比对", values.has(key));
        }
    }

    private static void removeValues(JsonObject payload, List<String> keys) {
        JsonObject values = payload.getAsJsonObject("values");
        for (String key : keys) {
            values.remove(key);
        }
    }

    // ---------------------------------------------------------------- 报警事件

    /** fixture 里的 alarmId 是 UUID；协议只要求设备生成、同一次 ACTIVE/CLEARED 相同，格式不限。 */
    private static final String FIXTURE_ALARM_ID = "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa";

    private static JsonObject alarmPayload(double value, boolean recovered, String text, long ts) {
        Map<String, Object> event = AlarmEventBuilder.build(OilMessage.HHEIGHT_OIL_ALARM, 1, FIXTURE_ALARM_ID,
                recovered, text, value, ts);
        return GSON.toJsonTree(AlarmEventBuilder.wrapAsTelemetry(event, ts)).getAsJsonObject();
    }

    @Test
    public void alarmActiveMatchesSharedFixture() {
        JsonObject actual = alarmPayload(1980.4, false, "1号罐高高液位报警", 1756713612345L);
        assertEquals(FixtureLoader.loadProtocolV1("alarm_active.json"), actual);
    }

    @Test
    public void alarmClearedMatchesSharedFixture() {
        JsonObject actual = alarmPayload(1842.5, true, "1号罐高高液位报警恢复", 1756713890123L);
        assertEquals(FixtureLoader.loadProtocolV1("alarm_cleared.json"), actual);
    }

    @Test
    public void alarmActiveWithOilTypeMatchesDeviceFixture() {
        long ts = 1756713612345L;
        Map<String, Object> event = AlarmEventBuilder.build(OilMessage.HHEIGHT_OIL_ALARM, 1, FIXTURE_ALARM_ID,
                false, "1号罐高高液位报警", 1980.4, ts, "0#", 1);
        JsonObject actual = GSON.toJsonTree(AlarmEventBuilder.wrapAsTelemetry(event, ts)).getAsJsonObject();
        assertEquals(FixtureLoader.loadProtocolV1("alarm_active_oil_type.json"), actual);
    }

    /** 跟踪器产出的一对 ACTIVE/CLEARED 共用同一个 alarmId——fixture 两个文件的 alarmId 也相同。 */
    @Test
    public void trackerPairsActiveAndClearedWithSameAlarmId() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new ActiveAlarmTrackerTest.MemoryPersistence());
        ActiveAlarmTracker.Transition raised = tracker.raise(1, OilMessage.HHEIGHT_OIL_ALARM, 1980.4,
                "1号罐高高液位报警", 1756713612345L);
        ActiveAlarmTracker.Transition cleared = tracker.recover(1, OilMessage.HHEIGHT_OIL_ALARM, 1842.5,
                1756713890123L);

        assertEquals(raised.entry.alarmId, cleared.entry.alarmId);
        assertEquals(
                FixtureLoader.loadProtocolV1("alarm_active.json").getAsJsonObject("values")
                        .getAsJsonObject("alarm").get("alarmId"),
                FixtureLoader.loadProtocolV1("alarm_cleared.json").getAsJsonObject("values")
                        .getAsJsonObject("alarm").get("alarmId"));
    }

    // ---------------------------------------------------------------- 客户端属性

    /**
     * 执行方案 V1-T3：没有数据来源、不发的 key——deviceModel/hardwareVersion 没人定义取值，
     * localControlMode 设备上没有这个状态，current_fw_* 属于 OTA 客户端（单独立项）。
     */
    private static final List<String> GAP_UNSOURCED_ATTRIBUTES = Arrays.asList(
            "deviceModel", "hardwareVersion", "localControlMode", "current_fw_title", "current_fw_version");
    /** probeType 取值待跟云端约定：Android 发通讯协议名（ZHONGCHENG/LD/LD2），fixture 写的是测量原理。 */
    private static final List<String> GAP_PROBE_TYPE = Arrays.asList("tank1.probeType", "tank2.probeType");

    private static JsonObject clientAttributesLikeFixture() {
        Map<String, Object> attributes = ClientAttributesBuilder.build(Arrays.asList(
                new ClientAttributesBuilder.Tank(1, "01", 0, 30000),
                new ClientAttributesBuilder.Tank(2, "02", 0, 30000)));
        return GSON.toJsonTree(attributes).getAsJsonObject();
    }

    @Test
    public void clientAttributesMatchSharedFixtureExceptKnownGaps() {
        JsonObject expected = FixtureLoader.loadProtocolV1("client_attributes.json");
        JsonObject actual = clientAttributesLikeFixture();

        for (String key : GAP_UNSOURCED_ATTRIBUTES) {
            expected.remove(key);
        }
        for (String key : GAP_PROBE_TYPE) {
            expected.remove(key);
            actual.remove(key);
        }
        assertEquals(expected, actual);
    }

    @Test
    public void knownGap_unsourcedClientAttributesNotPublished() {
        JsonObject actual = clientAttributesLikeFixture();
        for (String key : GAP_UNSOURCED_ATTRIBUTES) {
            assertFalse("有了数据来源后删除本测试，并把 " + key + " 加回逐 key 比对", actual.has(key));
        }
    }

    @Test
    public void knownGap_probeTypeNamingDiffersFromFixture() {
        JsonObject expected = FixtureLoader.loadProtocolV1("client_attributes.json");
        JsonObject actual = clientAttributesLikeFixture();
        for (String key : GAP_PROBE_TYPE) {
            assertNotEquals("跟云端约定好 probeType 取值后删除本测试，并把 " + key + " 加回逐 key 比对",
                    expected.get(key), actual.get(key));
        }
    }

    // ---------------------------------------------------------------- RPC 请求（解码）

    @Test
    public void rpcRequestFixtureParses() {
        JsonObject body = FixtureLoader.loadProtocolV1("rpc_request.json");
        RpcRequestEnvelope request = RpcRequestEnvelope.parse("42", body.toString());

        assertEquals("pump.start", request.getMethod());
        assertEquals(COMMAND_ID, request.getCommandId());
        assertTrue(request.isProtocolVersionSupported());
        assertEquals(1756713600000L, request.getIssuedAt());
        assertEquals(1756713660000L, request.getExpireAt());
        assertEquals("zhangsan@customer-a", request.getOperator());
        assertEquals("手动补油，值班室确认", request.getReason());
        assertEquals(1, request.getArgs().get("pump").getAsInt());
        assertEquals(1, request.getArgs().get("tank").getAsInt());
    }

    // ---------------------------------------------------------------- RPC ACK

    @Test
    public void rpcAckReceivedMatchesSharedFixture() {
        Map<String, Object> ack = RpcWirePayloads.ack(COMMAND_ID, RpcResultStatus.RECEIVED, RpcErrorCode.NONE,
                null, false, 1756713600412L);
        assertEquals(FixtureLoader.loadProtocolV1("rpc_ack_received.json"), GSON.toJsonTree(ack));
    }

    @Test
    public void rpcAckRejectedMatchesSharedFixture() {
        Map<String, Object> ack = RpcWirePayloads.ack(COMMAND_ID, RpcResultStatus.REJECTED,
                RpcErrorCode.INTERLOCK_BLOCKED, "1号罐液位低于下限，设备端联锁禁止启泵", false, 1756713600418L);
        assertEquals(FixtureLoader.loadProtocolV1("rpc_ack_rejected.json"), GSON.toJsonTree(ack));
    }

    @Test
    public void rpcAckDuplicateMatchesSharedFixture() {
        Map<String, Object> ack = RpcWirePayloads.ack(COMMAND_ID, RpcResultStatus.RECEIVED, RpcErrorCode.NONE,
                null, true, 1756713605990L);
        assertEquals(FixtureLoader.loadProtocolV1("rpc_ack_duplicate.json"), GSON.toJsonTree(ack));
    }

    // ---------------------------------------------------------------- 命令最终结果

    private static JsonObject cmdResultPayload(RpcResultStatus status, RpcErrorCode errorCode, String reason,
                                               Map<String, Object> detail, long ts) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cmdResult", RpcWirePayloads.cmdResult(COMMAND_ID, status, errorCode, reason, detail, ts));
        return GSON.toJsonTree(TelemetrySnapshotBuilder.wrapWithTimestamp(values, ts)).getAsJsonObject();
    }

    @Test
    public void cmdResultSucceededMatchesSharedFixture() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pump", 1);
        detail.put("running", true);
        JsonObject actual = cmdResultPayload(RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, null, detail,
                1756713604700L);
        assertEquals(FixtureLoader.loadProtocolV1("cmd_result_succeeded.json"), actual);
    }

    @Test
    public void cmdResultFailedMatchesSharedFixture() {
        JsonObject actual = cmdResultPayload(RpcResultStatus.FAILED, RpcErrorCode.HARDWARE_FAULT,
                "泵启动后未检测到流量，已自动停泵", null, 1756713609100L);
        assertEquals(FixtureLoader.loadProtocolV1("cmd_result_failed.json"), actual);
    }
}
