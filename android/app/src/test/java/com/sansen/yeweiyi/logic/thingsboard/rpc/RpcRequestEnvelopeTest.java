package com.sansen.yeweiyi.logic.thingsboard.rpc;

import com.sansen.yeweiyi.logic.thingsboard.FixtureLoader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RpcRequestEnvelopeTest {

    private static String fixture() {
        return FixtureLoader.loadText("rpc_request_set_alarm_threshold.json");
    }

    @Test
    public void parsesAllFieldsFromFixture() {
        RpcRequestEnvelope req = RpcRequestEnvelope.parse("mqtt-req-1", fixture());

        assertEquals("mqtt-req-1", req.getMqttRequestId());
        assertEquals("config.setAlarmThresholds", req.getMethod());
        assertEquals(1, req.getProtocolVersion());
        assertEquals("cmd-0001", req.getCommandId());
        assertEquals(1735689600000L, req.getIssuedAt());
        assertEquals(1735689630000L, req.getExpireAt());
        assertEquals("ops-user-1", req.getOperator());
        assertEquals("现场巡检后调整高高液位阈值", req.getReason());
        assertEquals(1, req.getArgs().get("oilCanId").getAsInt());
        assertEquals(5000, req.getArgs().get("hheightOilAlarm").getAsInt());
    }

    @Test
    public void isExpiredComparesAgainstExpireAt() {
        RpcRequestEnvelope req = RpcRequestEnvelope.parse("mqtt-req-1", fixture());
        assertFalse(req.isExpired(1735689630000L)); // 正好等于 expireAt，还没过期
        assertTrue(req.isExpired(1735689630001L));
    }

    @Test
    public void protocolVersionMismatchIsDetected() {
        String json = fixture().replace("\"protocolVersion\": 1", "\"protocolVersion\": 2");
        RpcRequestEnvelope req = RpcRequestEnvelope.parse("mqtt-req-1", json);
        assertFalse(req.isProtocolVersionSupported());
    }

    // ---- PR #38 Codex 第六轮审核 P2：缺失/非法 protocolVersion 不能默认放行 ----

    @Test
    public void missingProtocolVersionIsTreatedAsUnsupportedNotDefaultedToCurrent() {
        String json = fixture().replace("\"protocolVersion\": 1,\n    ", "");
        RpcRequestEnvelope req = RpcRequestEnvelope.parse("mqtt-req-1", json);

        assertFalse("缺少 protocolVersion 的请求语义不能假定跟当前协议版本一致，"
                + "必须落进版本不支持的拒绝路径，而不是被当成当前版本直接放行",
                req.isProtocolVersionSupported());
        // commandId 仍然要能正常解析出来——这条走的是"版本不支持"拒绝路径（按 commandId
        // 去重记录），不是解析阶段就失败的 MalformedRpcRequestException。
        assertEquals("cmd-0001", req.getCommandId());
    }

    @Test
    public void nonNumericProtocolVersionIsTreatedAsUnsupportedNotDefaultedToCurrent() {
        String json = fixture().replace("\"protocolVersion\": 1", "\"protocolVersion\": \"not-a-number\"");
        RpcRequestEnvelope req = RpcRequestEnvelope.parse("mqtt-req-1", json);

        assertFalse(req.isProtocolVersionSupported());
        assertEquals("cmd-0001", req.getCommandId());
    }

    @Test(expected = RpcRequestEnvelope.MalformedRpcRequestException.class)
    public void rejectsNonJsonPayload() {
        RpcRequestEnvelope.parse("mqtt-req-1", "not json");
    }

    @Test(expected = RpcRequestEnvelope.MalformedRpcRequestException.class)
    public void rejectsPayloadMissingMethod() {
        RpcRequestEnvelope.parse("mqtt-req-1", "{\"params\":{\"commandId\":\"c1\",\"expiresAtMs\":1}}");
    }

    @Test(expected = RpcRequestEnvelope.MalformedRpcRequestException.class)
    public void rejectsPayloadMissingCommandId() {
        RpcRequestEnvelope.parse("mqtt-req-1", "{\"method\":\"ping\",\"params\":{\"expiresAtMs\":1}}");
    }

    @Test(expected = RpcRequestEnvelope.MalformedRpcRequestException.class)
    public void rejectsPayloadMissingExpireAt() {
        RpcRequestEnvelope.parse("mqtt-req-1", "{\"method\":\"ping\",\"params\":{\"commandId\":\"c1\"}}");
    }
}
