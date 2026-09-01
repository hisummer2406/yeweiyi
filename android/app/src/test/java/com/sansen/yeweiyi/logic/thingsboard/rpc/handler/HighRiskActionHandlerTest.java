package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HighRiskActionHandlerTest {

    private static RpcRequestEnvelope envelope(String method) {
        String json = "{\"method\":\"" + method + "\",\"params\":{\"protocolVersion\":\"tb-v1\","
                + "\"commandId\":\"cmd-1\",\"expireAt\":9999999999999,\"args\":{}}}";
        return RpcRequestEnvelope.parse("req-1", json);
    }

    @Test
    public void alwaysRejectsRegardlessOfMethod() {
        HighRiskActionHandler handler = new HighRiskActionHandler();

        for (String method : new String[]{
                HighRiskActionHandler.METHOD_START_PUMP,
                HighRiskActionHandler.METHOD_STOP_PUMP,
                HighRiskActionHandler.METHOD_OPEN_VALVE,
                HighRiskActionHandler.METHOD_CLOSE_VALVE}) {
            RpcResult result = handler.handle(envelope(method));
            assertEquals(method, RpcResultStatus.REJECTED, result.getStatus());
            assertEquals(method, RpcErrorCode.FEATURE_DISABLED, result.getErrorCode());
        }
    }
}
