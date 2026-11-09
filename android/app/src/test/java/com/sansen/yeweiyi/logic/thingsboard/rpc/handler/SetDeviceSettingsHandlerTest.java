package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SetDeviceSettingsHandlerTest {

    private Map<String, Integer> saved;

    private RpcResult call(SetDeviceSettingsHandler handler, String argsJson) {
        String json = "{\"method\":\"config.setDeviceSettings\",\"params\":{\"protocolVersion\":1,"
                + "\"commandId\":\"cmd-1\",\"expiresAtMs\":9999999999999,\"args\":" + argsJson + "}}";
        return handler.handle(RpcRequestEnvelope.parse("req-1", json));
    }

    private SetDeviceSettingsHandler recordingHandler() {
        saved = null;
        return new SetDeviceSettingsHandler(values -> saved = values);
    }

    @Test
    public void savesGivenThresholds() {
        RpcResult result = call(recordingHandler(), "{\"outStockHeightThresholdMm\":20,\"outStockTimeThresholdSec\":60}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(Integer.valueOf(20), saved.get("outStockHeightThresholdMm"));
        assertEquals(Integer.valueOf(60), saved.get("outStockTimeThresholdSec"));
        assertEquals(2, saved.size());
    }

    @Test
    public void rejectsZeroOutOfRangeUnknownAndEmptyWithoutSaving() {
        String[] bad = {
                "{\"inStockHeightThresholdMm\":0}",       // 本地填 0 不保存，远程保持一致
                "{\"inStockTimeThresholdSec\":86401}",
                "{\"inStockTimeThresholdSec\":1.5}",
                "{\"inStockHeightThreshold\":10}",        // 拼错且没有其它字段：忽略后无可改字段
                "{}"
        };
        for (String args : bad) {
            RpcResult result = call(recordingHandler(), args);
            assertEquals(args, RpcResultStatus.REJECTED, result.getStatus());
            assertEquals(args, RpcErrorCode.INVALID_PARAMS, result.getErrorCode());
            assertNull(args, saved);
        }
    }

    @Test
    public void unknownFieldsAreIgnoredAndReported() {
        RpcResult result = call(recordingHandler(), "{\"inStockTimeThresholdSec\":30,\"futureField\":1}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, saved.size());
        assertEquals(java.util.Collections.singletonList("futureField"), result.getData().get("ignored"));
    }

    @Test
    public void storeFailureIsReportedAsFailed() {
        SetDeviceSettingsHandler handler = new SetDeviceSettingsHandler(values -> {
            throw new Exception("写入失败");
        });
        RpcResult result = call(handler, "{\"inStockTimeThresholdSec\":30}");
        assertEquals(RpcResultStatus.FAILED, result.getStatus());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, result.getErrorCode());
    }

    /** #9：整机多字段一次交给存储（真机实现一次 commit，全有或全无）；存储失败时回执 FAILED 且说明未修改任何字段。 */
    @Test
    public void allFieldsGoToTheStoreInOneCallAndAFailureChangesNothing() {
        final int[] calls = {0};
        SetDeviceSettingsHandler handler = new SetDeviceSettingsHandler(values -> {
            calls[0]++;
            assertEquals(4, values.size());
            throw new IllegalStateException("写入配置失败，未修改任何字段");
        });
        RpcResult result = call(handler, "{\"inStockHeightThresholdMm\":20,\"inStockTimeThresholdSec\":60,"
                + "\"outStockHeightThresholdMm\":30,\"outStockTimeThresholdSec\":90}");
        assertEquals(1, calls[0]);
        assertEquals(RpcResultStatus.FAILED, result.getStatus());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, result.getErrorCode());
        org.junit.Assert.assertTrue(result.getMessage().contains("未修改任何字段"));
    }
}
