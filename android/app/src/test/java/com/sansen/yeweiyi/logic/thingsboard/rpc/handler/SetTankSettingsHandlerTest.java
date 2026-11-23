package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
        names = new FakeNames();
        handler = new SetTankSettingsHandler(repo, () -> refreshCount++, names, hex -> "not in palette: " + hex);
    }

    private static final class FakeNames implements CustomOilNameStore {
        final Map<Integer, String[]> saved = new HashMap<>();
        int writes;

        @Override
        public String[] save(int index, String zh, String en) {
            writes++;
            String[] old = saved.getOrDefault(index, new String[]{"", ""});
            saved.put(index, new String[]{zh, en});
            return old;
        }
    }

    private FakeNames names;

    @Test
    public void writesAPaletteColorAsArgb() {
        RpcResult result = call("{\"tank\":1,\"oilColor\":16711680}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(0xFFFF0000, repo.lastSaved.getOilColor());
    }

    @Test
    public void refusesAColorOutsideThePalette() {
        RpcResult result = call("{\"tank\":1,\"oilColor\":1193046}");
        assertRejectedWithoutWrite(result);
        assertEquals("界面语言的提示文字（strings.xml），不是写死的中文", "not in palette: #123456", result.getMessage());
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilColor\":\"#FF0000\"}"));
    }

    @Test
    public void colorAndAnInvalidFieldAreRefusedTogether() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilColor\":16711680,\"density\":3}"));
        assertEquals(0, tank1.getOilColor());
    }

    @Test
    public void writesTheNameAndTheIndexTogether() {
        RpcResult result = call("{\"tank\":1,\"oilTypeIndex\":32,\"customOilName\":\"二厂盐酸\",\"customOilNameEn\":\"HCl\"}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(32, repo.lastSaved.getOilType());
        assertEquals("二厂盐酸", names.saved.get(32)[0]);
        assertEquals("HCl", names.saved.get(32)[1]);
    }

    @Test
    public void refusesANameWithoutACustomIndex() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilTypeIndex\":5,\"customOilName\":\"盐酸\"}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"density\":0.8,\"customOilName\":\"盐酸\"}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilTypeIndex\":32,\"customOilName\":\" \"}"));
        assertEquals(0, names.writes);
    }

    @Test
    public void restoresTheOldNameWhenTheTankWriteFails() {
        names.saved.put(33, new String[]{"旧", "Old"});
        repo.failNextSave = true;
        RpcResult result = call("{\"tank\":1,\"oilTypeIndex\":33,\"customOilName\":\"新\"}");
        assertEquals(RpcResultStatus.FAILED, result.getStatus());
        assertEquals("旧", names.saved.get(33)[0]);
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
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighLevelMm\":650,\"probeAddress\":2}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tankShape\":1}"));
    }

    @Test
    public void changesTankHeightAlone() {
        RpcResult result = call("{\"tank\":1,\"tankHeightMm\":2600}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(2600, repo.lastSaved.getHeight());
        assertEquals(1, repo.lastSaved.getEnable());
    }

    @Test
    public void lowerTankHeightIsRejectedWhenAStoredThresholdWouldExceedIt() {
        // #65 验收：高高 1500 时把罐高降到 1400 被拒，原值保留，错误里列出冲突阈值
        RpcResult result = call("{\"tank\":1,\"tankHeightMm\":1400}");
        assertRejectedWithoutWrite(result);
        assertTrue(result.getMessage(), result.getMessage().contains("高高液位(1500)"));
        assertEquals(2000, tank1.getHeight());
        tank1.setHeightWaterAlarm(1450);
        assertTrue(call("{\"tank\":1,\"tankHeightMm\":1400}").getMessage().contains("高水位(1450)"));
    }

    @Test
    public void lowerTankHeightPassesWhenTheConflictingThresholdIsFixedInTheSameCommand() {
        RpcResult result = call("{\"tank\":1,\"tankHeightMm\":1400,\"alarmHighHighLevelMm\":1300}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(1400, repo.lastSaved.getHeight());
        assertEquals(1300, repo.lastSaved.getHheightOilAlarm());
    }

    @Test
    public void thresholdIsCheckedAgainstTheNewTankHeight() {
        assertEquals(RpcResultStatus.SUCCEEDED,
                call("{\"tank\":1,\"tankHeightMm\":3000,\"alarmHighHighLevelMm\":2800}").getStatus());
    }

    @Test
    public void rejectsZeroNegativeAndFractionalTankHeight() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tankHeightMm\":0}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tankHeightMm\":-1}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tankHeightMm\":2600.5}"));
    }

    @Test
    public void changesLowTempIncludingNegativeAndZero() {
        assertEquals(RpcResultStatus.SUCCEEDED, call("{\"tank\":1,\"alarmLowTempC\":-40}").getStatus());
        assertEquals(-40, repo.lastSaved.getLowTempAlarm());
        assertEquals(RpcResultStatus.SUCCEEDED, call("{\"tank\":1,\"alarmLowTempC\":0}").getStatus());
        assertEquals(0, repo.lastSaved.getLowTempAlarm());
    }

    @Test
    public void lowTempMustBeBelowHighTempWhenBothEnabled() {
        tank1.setHighTempAlarm(40);
        RpcResult result = call("{\"tank\":1,\"alarmLowTempC\":40}");
        assertRejectedWithoutWrite(result);
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmLowTempC\":-51}"));
        assertEquals(RpcResultStatus.SUCCEEDED,
                call("{\"tank\":1,\"alarmLowTempC\":50,\"alarmHighTempC\":0}").getStatus());
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
    public void correctionsHaveNoRangeLimit() {
        RpcResult result = call("{\"tank\":1,\"levelCorrectionMm\":1000,\"waterCorrectionMm\":-1000.5,\"tempCorrectionC\":-100.5}");
        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(1000, repo.lastSaved.getOilCorrection(), 1e-9);
        assertEquals(-1000.5, repo.lastSaved.getWaterCorrection(), 1e-9);
        assertEquals(-100.5, repo.lastSaved.getTempCorrection(), 1e-9);
    }

    @Test
    public void rejectsOutOfRangeAndNonNumericValues() {
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighWaterMm\":2001}")); // 超过罐高
        assertRejectedWithoutWrite(call("{\"tank\":1,\"alarmHighTempC\":101}"));
        assertRejectedWithoutWrite(call("{\"tank\":1,\"tempCorrectionC\":\"1\"}")); // 字符串
        assertRejectedWithoutWrite(call("{\"tank\":1,\"levelCorrectionMm\":1e999}")); // 超出 double
        assertRejectedWithoutWrite(call("{\"tank\":1,\"oilTypeIndex\":40}"));
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
