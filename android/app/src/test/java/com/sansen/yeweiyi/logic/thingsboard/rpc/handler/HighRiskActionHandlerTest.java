package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HighRiskActionHandlerTest {

    private static RpcRequestEnvelope envelope(String method) {
        String json = "{\"method\":\"" + method + "\",\"params\":{\"protocolVersion\":1,"
                + "\"commandId\":\"cmd-1\",\"expiresAtMs\":9999999999999,\"args\":{}}}";
        return RpcRequestEnvelope.parse("req-1", json);
    }

    /**
     * 固定这七个方法名跟 yeweiyi-cloud {@code internal/protocol/rpc.go} 的 {@code Method*} 常量
     * 逐字符相等——2026-09-19 复核发现泵/阀四个此前一直是改造前的旧写法（{@code startPump} 等），
     * 因为恒 {@code FEATURE_DISABLED} 从不被云端真正调用，名字错了也测不出来，直到 #34 联锁补齐
     * 真正打开这些方法那天才会暴露成云端发 {@code pump.start}、设备答 {@code UNKNOWN_METHOD}。
     */
    @Test
    public void methodNamesMatchCloudProtocol() {
        assertEquals("pump.start", HighRiskActionHandler.METHOD_START_PUMP);
        assertEquals("pump.stop", HighRiskActionHandler.METHOD_STOP_PUMP);
        assertEquals("valve.open", HighRiskActionHandler.METHOD_OPEN_VALVE);
        assertEquals("valve.close", HighRiskActionHandler.METHOD_CLOSE_VALVE);
        assertEquals("alarm.mute", HighRiskActionHandler.METHOD_ALARM_MUTE);
        assertEquals("alarm.clear", HighRiskActionHandler.METHOD_ALARM_CLEAR);
        assertEquals("device.reboot", HighRiskActionHandler.METHOD_DEVICE_REBOOT);
    }

    @Test
    public void alwaysRejectsRegardlessOfMethod() {
        HighRiskActionHandler handler = new HighRiskActionHandler();

        for (String method : new String[]{
                HighRiskActionHandler.METHOD_START_PUMP,
                HighRiskActionHandler.METHOD_STOP_PUMP,
                HighRiskActionHandler.METHOD_OPEN_VALVE,
                HighRiskActionHandler.METHOD_CLOSE_VALVE,
                HighRiskActionHandler.METHOD_ALARM_MUTE,
                HighRiskActionHandler.METHOD_ALARM_CLEAR,
                HighRiskActionHandler.METHOD_DEVICE_REBOOT}) {
            RpcResult result = handler.handle(envelope(method));
            assertEquals(method, RpcResultStatus.REJECTED, result.getStatus());
            assertEquals(method, RpcErrorCode.FEATURE_DISABLED, result.getErrorCode());
        }
    }
}
