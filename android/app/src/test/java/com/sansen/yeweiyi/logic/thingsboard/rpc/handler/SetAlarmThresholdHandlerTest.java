package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;
import com.sansen.yeweiyi.repository.OilTankRepository;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SetAlarmThresholdHandlerTest {

    /** 只实现测试用到的两个方法，其余按接口约定不会被调用，抛异常暴露误用。 */
    private static class FakeOilTankRepository implements OilTankRepository {
        final Map<Integer, OilCanSet> byId = new HashMap<>();
        OilCanSet lastSaved;
        boolean throwOnUpdate = false;

        @Override
        public List<OilCanSet> findAllOilCanSet() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OilCanSet findOilCanSetById(int id) {
            return byId.get(id);
        }

        @Override
        public void updateOilCanSet(OilCanSet oilCanSet) {
            if (throwOnUpdate) {
                throw new RuntimeException("模拟数据库写入失败");
            }
            lastSaved = oilCanSet;
        }

        @Override
        public List<OilCanTable> findAllOilCanTable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateOilCanTable(OilCanTable oilCanTable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exportOilCanTable(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProbeToOil> findAllProbeToOil() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Sideleakage findLastSideleakage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Sideleakage> findSideleakageBetween(int limit, int offset, boolean desc, String beginTime, String endTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OilCanMapper> findAllOilCanMapper() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OilExploration> findAllOilExploration() {
            throw new UnsupportedOperationException();
        }
    }

    private static RpcRequestEnvelope envelopeWithArgs(String argsJson) {
        String json = "{\"method\":\"setAlarmThreshold\",\"params\":{\"protocolVersion\":\"tb-v1\","
                + "\"commandId\":\"cmd-1\",\"expireAt\":9999999999999,\"args\":" + argsJson + "}}";
        return RpcRequestEnvelope.parse("req-1", json);
    }

    @Test
    public void updatesOnlyFieldsPresentInArgs() {
        FakeOilTankRepository repo = new FakeOilTankRepository();
        OilCanSet existing = new OilCanSet();
        existing.setPk_oilCanId(1);
        existing.setHeightOilAlarm(4000);
        repo.byId.put(1, existing);

        SetAlarmThresholdHandler handler = new SetAlarmThresholdHandler(repo);
        RpcResult result = handler.handle(envelopeWithArgs("{\"oilCanId\":1,\"hheightOilAlarm\":5000}"));

        assertEquals(RpcResultStatus.SUCCEEDED, result.getStatus());
        assertEquals(5000, repo.lastSaved.getHheightOilAlarm());
        assertEquals("未在 args 里出现的字段不应该被改动", 4000, repo.lastSaved.getHeightOilAlarm());
    }

    @Test
    public void rejectsWhenOilCanIdMissing() {
        SetAlarmThresholdHandler handler = new SetAlarmThresholdHandler(new FakeOilTankRepository());
        RpcResult result = handler.handle(envelopeWithArgs("{\"hheightOilAlarm\":5000}"));

        assertEquals(RpcResultStatus.REJECTED, result.getStatus());
        assertEquals(RpcErrorCode.INVALID_PARAMS, result.getErrorCode());
    }

    @Test
    public void failsWhenOilCanIdUnknown() {
        SetAlarmThresholdHandler handler = new SetAlarmThresholdHandler(new FakeOilTankRepository());
        RpcResult result = handler.handle(envelopeWithArgs("{\"oilCanId\":99,\"hheightOilAlarm\":5000}"));

        assertEquals(RpcResultStatus.FAILED, result.getStatus());
    }

    @Test
    public void rejectsWhenNoRecognizedThresholdField() {
        FakeOilTankRepository repo = new FakeOilTankRepository();
        repo.byId.put(1, new OilCanSet());

        SetAlarmThresholdHandler handler = new SetAlarmThresholdHandler(repo);
        RpcResult result = handler.handle(envelopeWithArgs("{\"oilCanId\":1,\"unknownField\":123}"));

        assertEquals(RpcResultStatus.REJECTED, result.getStatus());
        assertNull(repo.lastSaved);
    }

    @Test
    public void repositoryFailureBecomesFailedResult() {
        FakeOilTankRepository repo = new FakeOilTankRepository();
        repo.byId.put(1, new OilCanSet());
        repo.throwOnUpdate = true;

        SetAlarmThresholdHandler handler = new SetAlarmThresholdHandler(repo);
        RpcResult result = handler.handle(envelopeWithArgs("{\"oilCanId\":1,\"hheightOilAlarm\":5000}"));

        assertEquals(RpcResultStatus.FAILED, result.getStatus());
        assertEquals(RpcErrorCode.INTERNAL_ERROR, result.getErrorCode());
    }
}
