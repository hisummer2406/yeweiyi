package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 取值范围与字段清单见 docs/远程配置下发-字段清单.md。 */
public class SetTankSettingsHandlerTest {

    private FakeOilTankRepository repo;
    private int refreshCount;
    private SetTankSettingsHandler handler;
    private OilCanSet tank1;

    @Before
    public void setUp() {
        repo = new FakeOilTankRepository();
        tank1 = new OilCanSet();
        tank1.setPk_oilCanId(1);
        tank1.setEnable(1);
        tank1.setHeight(2000);
        tank1.setLlowOilAlarm(5);
        tank1.setLowOilAlarm(100);
        tank1.setHeightOilAlarm(600);
        tank1.setHheightOilAlarm(1500);
        tank1.setDensity(0.56);
        repo.byId.put(1, tank1);
        refreshCount = 0;
        handler = new SetTankSettingsHandler(repo, () -> refreshCount++);
    }

    private RpcResult call(String argsJson) {
        String json = "{\"method\":\"config.setTankSettings\",\"params\":{\"protocolVersion\":1,"
                + "\"commandId\":\"cmd-1\",\"expiresAtMs\":9999999999999,\"args\":" + argsJson + "}}";
        return handler.handle(RpcRequestEnvelope.parse("req-1", json));
    }

    private void assertRejectedWithoutWrite(RpcResult result) {
        assertEquals(RpcResultStatus.REJECTED, result.getStatus());
        assertEquals(RpcErrorCode.INVALID_PARAMS, result.getErrorCode());
        assertNull("被拒绝时不能写库", repo.lastSaved);
        assertEquals(0, refreshCount);
    }

    @Test
    public void updatesOnlyGivenFieldsWritesSyncAndRefreshes() {
        RpcResult result = call("{\"tank\":1,\"alarmHighLevelMm\":650,\"density\":0.72,\"oilTypeIndex\":5,"
                + "\"capacityL\":30000,\"levelCorrectionMm\":-1.5}");

        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(650, repo.lastSaved.getHeightOilAlarm());
        assertEquals(1500, repo.lastSaved.getHheightOilAlarm());
        assertEquals(0.72, repo.lastSaved.getDensity(), 1e-9);
        assertEquals(5, repo.lastSaved.getOilType());
        assertEquals(30000, repo.lastSaved.getMaxVolume());
        assertEquals(-1.5, repo.lastSaved.getOilCorrection(), 1e-9);
        assertEquals(1, refreshCount);
        assertEquals(Arrays.asList("alarmHighLevelMm", "density", "oilTypeIndex", "capacityL", "levelCorrectionMm"),
                result.getData().get("updated"));
    }

    @Test
    public void zeroDisablesALevelAndIsSkippedInOrderCheck() {
        RpcResult result = call("{\"tank\":1,\"alarmLowLevelMm\":0}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(0, repo.lastSaved.getLowOilAlarm());
    }

    @Test
    public void rejectsLevelOrderViolationAfterMergingWithStoredValues() {
        // 高 700 合并库里的高高 1500 没问题；高 1600 就超过了高高
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":1600}"));
    }

    @Test
    public void rejectsWholeCommandWhenAnyFieldInvalid() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":650,\"density\":0}"));
        assertEquals("被拒时前面校验通过的字段也不能改到对象上", 600, tank1.getHeightOilAlarm());
    }

    @Test
    public void rejectsClosedFieldsEvenAlongsideValidOnes() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmLowTempC\":-5}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tankHeightMm\":3000}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":650,\"probeAddress\":2}"));
    }

    @Test
    public void unknownFieldsAreIgnoredAndReported() {
        RpcResult result = call("{\"tank\":1,\"alarmHighLevelMm\":650,\"futureField\":1}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(Arrays.asList("alarmHighLevelMm"), result.getData().get("updated"));
        assertEquals(Arrays.asList("futureField"), result.getData().get("ignored"));
        assertEquals(650, repo.lastSaved.getHeightOilAlarm());
    }

    @Test
    public void onlyUnknownFieldsIsRejectedAsNothingToChange() {
        // 拼错的字段名被忽略后没有可改的字段，整条拒绝，云端能从错误信息看到被忽略的字段
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMM\":650}"));
    }

    @Test
    public void storedOutOfOrderLevelsDoNotBlockUnrelatedChanges() {
        // 本地设置页不校验顺序，库里可能本就是 低 800 > 高 600
        tank1.setLowOilAlarm(800);
        RpcResult result = call("{\"tank\":1,\"density\":0.72}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(0.72, repo.lastSaved.getDensity(), 1e-9);
    }

    @Test
    public void storedOutOfOrderLevelsAreCheckedOnceALevelFieldIsTouched() {
        tank1.setLowOilAlarm(800);
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighHighLevelMm\":1600}"));
    }

    @Test
    public void rejectsOutOfRangeAndNonNumericValues() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighWaterMm\":2001}")); // 超过罐高
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighTempC\":101}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tempCorrectionC\":10.5}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilTypeIndex\":26}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":\"650\"}")); // 字符串
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":650.5}")); // 整数字段带小数
    }

    @Test
    public void rejectsMissingDisabledOrEmptyTank() {
        assertRejectedWithoutWrite(call("{\"alarmHighLevelMm\":650}"));
        assertRejectedWithoutWrite(call("{\"tank\":2,\"alarmHighLevelMm\":650}")); // 不存在
        tank1.setEnable(0);
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":650}"));
        tank1.setEnable(1);
        assertRejectedWithoutWrite(call("{\"tank\":1}"));
    }

    @Test
    public void writeFailureIsReportedAsFailedNotSucceeded() {
        repo.throwOnUpdate = true;
        RpcResult result = call("{\"tank\":1,\"alarmHighLevelMm\":650}");
        assertEquals(RpcResultStatus.FAILED, result.getStatus());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, result.getErrorCode());
        assertEquals(0, refreshCount);
    }

    @Test
    public void integerFieldAcceptsWholeNumberWrittenWithDecimalPoint() {
        RpcResult result = call("{\"tank\":1,\"alarmHighLevelMm\":650.0}");
        assertTrue(result.getStatus() == RpcResultStatus.SUCCEEDED);
        assertEquals(650, repo.lastSaved.getHeightOilAlarm());
    }
}
