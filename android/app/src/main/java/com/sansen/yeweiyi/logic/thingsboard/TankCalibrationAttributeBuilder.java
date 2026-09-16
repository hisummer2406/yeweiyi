package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 罐体标定表（{@link OilCanTable#getHeight()}/{@link OilCanTable#getVolum()}，
 * {@code CreateOilTableFragment} 生成、写入 {@code oiltablearray} 那份高度→体积离散点）
 * 作为 ThingsBoard client attribute 上报——zhongchengyeweiyi#42 待办1 的落地。
 * <p>
 * <b>范围刻意收窄</b>：只报安卓端已经真实持久化的数据（标定表本身）。罐型分类
 * （椭圆/圆柱/长方体）和原始尺寸参数（长度/半径/封头高度等）在安卓端从未真正落库——
 * {@code CreateOilTableFragment} 只把这些当算标定表用的临时局部变量，算完即丢，
 * {@code OilCanSet.gunType} 声明了但从未被真正 set 过——所以这里不报，不是遗漏。
 * <p>
 * 新增字段，云端目前没有对应的 attribute 消费逻辑，需要跨仓库确认后云端才能落地存储/展示
 * （见 CLAUDE.md「协议还未冻结」的约定），不是本次改动单方面冻结的协议。
 * <p>
 * 纯 Java，无 Android 依赖，可在 JVM 单测里直接构造 {@link OilCanSet}/{@link OilCanTable} 校验。
 */
public final class TankCalibrationAttributeBuilder {

    private TankCalibrationAttributeBuilder() {
    }

    /**
     * @param oilCanSets 每个罐位的启用状态与罐号来源（{@code OilTankRepository#findAllOilCanSet()}）——
     *                   跟 {@code OilcanDataManager.setProbeData} 一样，只发 {@code enable==1} 的罐，
     *                   保持跟 {@code tank{N}.*} 遥测键同一套"哪些罐是活的"口径。
     * @param tables     标定表列表（{@code OilTankRepository#findAllOilCanTable()}），列表下标
     *                   0-based，跟 {@code OilCanSet.pk_oilCanId}（1-based 罐位号）相差 1——
     *                   跟 {@code OilcanDataManager.setOilcanTable} 里 {@code tables.get(pos - 1)}
     *                   同一个对应关系，不是本类新发明的。
     * @return {@code tank{N}.calibrationHeightMm}/{@code tank{N}.calibrationVolumeL} 形式的扁平键，
     * 值是等长的高度（mm）/体积（L）数组，按高度升序——直接对应标定表的离散点对。
     */
    public static Map<String, Object> build(List<OilCanSet> oilCanSets, List<OilCanTable> tables) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (oilCanSets == null || tables == null) {
            return attributes;
        }
        for (OilCanSet set : oilCanSets) {
            if (set.getEnable() != 1) {
                continue;
            }
            int tankIndex = set.getPk_oilCanId();
            int tableIndex = tankIndex - 1;
            if (tableIndex < 0 || tableIndex >= tables.size()) {
                continue;
            }
            OilCanTable table = tables.get(tableIndex);
            table.StringToArray();
            attributes.put(tankKey(tankIndex, "calibrationHeightMm"), table.getHeight());
            attributes.put(tankKey(tankIndex, "calibrationVolumeL"), table.getVolum());
        }
        return attributes;
    }

    private static String tankKey(int index, String field) {
        return "tank" + index + "." + field;
    }
}
