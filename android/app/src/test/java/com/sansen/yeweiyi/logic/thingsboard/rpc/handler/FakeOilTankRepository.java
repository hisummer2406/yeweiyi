package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.repository.OilTankRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只实现 RPC handler 测试用到的方法，其余按接口约定不会被调用，抛异常暴露误用。 */
class FakeOilTankRepository implements OilTankRepository {
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
        throw new UnsupportedOperationException("handler 应该调用同步版本 updateOilCanSetSync");
    }

    @Override
    public void updateOilCanSetSync(OilCanSet oilCanSet) throws com.lidroid.xutils.exception.DbException {
        if (throwOnUpdate) {
            throw new com.lidroid.xutils.exception.DbException("模拟数据库写入失败");
        }
        lastSaved = oilCanSet;
    }

    @Override
    public void setOilCanSetChangedListener(Runnable listener) {
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
