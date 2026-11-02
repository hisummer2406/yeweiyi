package com.sansen.yeweiyi.logic.thingsboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议 §5.1 客户端属性（设备身份/配置，连接时和配置变更时上报到 {@code v1/devices/me/attributes}）。
 * 字段形状对照共享 fixture {@code client_attributes.json}，由 {@code ProtocolV1ContractTest} 固定。
 * <p>
 * 只发有真实数据来源的 key：{@code protocolVersion}、{@code tankCount}、每罐 {@code probeAddress}/
 * {@code probeType}/{@code capacityL}。{@code deviceModel}/{@code hardwareVersion} 没人定义过取值，
 * {@code localControlMode} 设备上没有这个状态——都不发，不编造值（执行方案 V1-T3）。
 * <p>
 * 纯 Java，调用方（{@code ThingsBoardManager}）从 {@code OilcanDataManager} 取每罐配置组装 {@link Tank}。
 */
public final class ClientAttributesBuilder {

    /** 一个启用罐的配置。 */
    public static final class Tank {
        final int tank;
        final String probeAddress;
        final int probeTypeCode;
        final double capacityL;

        /**
         * @param probeTypeCode {@code OilCan.getProbeType()}，实际返回串口设置页"串口 2"的选择：
         *                      0=中盛探针协议、1=标准 Modbus 协议、2=4-20mA（整机一个设置）
         */
        public Tank(int tank, String probeAddress, int probeTypeCode, double capacityL) {
            this.tank = tank;
            this.probeAddress = probeAddress;
            this.probeTypeCode = probeTypeCode;
            this.capacityL = capacityL;
        }
    }

    private ClientAttributesBuilder() {
    }

    public static Map<String, Object> build(List<Tank> tanks) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("protocolVersion", ProtocolVersion.CURRENT);
        // 跟遥测快照的 tankCount 同一语义：最大罐号，不是罐数（罐号可能不连续，见 TelemetrySnapshotBuilder）
        int maxTank = 0;
        for (Tank tank : tanks) {
            maxTank = Math.max(maxTank, tank.tank);
        }
        attributes.put("tankCount", maxTank);
        for (Tank tank : tanks) {
            attributes.put(tankKey(tank.tank, "probeAddress"), tank.probeAddress);
            String probeType = probeTypeName(tank.probeTypeCode);
            if (probeType != null) {
                attributes.put(tankKey(tank.tank, "probeType"), probeType);
            }
            attributes.put(tankKey(tank.tank, "capacityL"), tank.capacityL);
        }
        return attributes;
    }

    /**
     * 上报的是探针通讯协议，名称跟"高级设置 → 串口设置 → 串口 2"的选项一致（{@code ChuanKouFragment}）：
     * 中盛探针协议 / 标准 Modbus 协议 / 4-20mA。不是测量原理——云端 fixture 示例写的是 "磁致伸缩"，
     * 取值约定待跟云端确认（执行方案 §5）。不认识的编号返回 null，不发这个 key。
     */
    static String probeTypeName(int probeTypeCode) {
        switch (probeTypeCode) {
            case 0:
                return "ZHONGCHENG";
            case 1:
                return "MODBUS";
            case 2:
                return "4-20MA";
            default:
                return null;
        }
    }

    private static String tankKey(int index, String field) {
        return "tank" + index + "." + field;
    }
}
