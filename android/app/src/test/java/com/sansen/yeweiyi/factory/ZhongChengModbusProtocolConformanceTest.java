package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 对照《中成液位modbus协议.docx》核对协议相关静态工具方法在真实文档举例报文上的行为。
 * 从站组帧逻辑本身见 {@link ZhongChengModbusSlaveTest}。
 *
 * 文档要点（设备作为 Modbus 从站，被外部主机查询）：
 *   - 从站地址固定 0x08，功能码 03（读）
 *   - 寄存器地址=文档写的编号-1（如"1000"对应线上 0x03E7），即 1 号罐油高
 *   - CRC16/MODBUS（多项式 0xA001），线上低字节在前
 *   - 每罐 6 个字段（油高/水高/温度/油水体积/油体积/水体积），每个字段固定
 *     4 字节 IEEE754 float、大端序（高字节在前），共 24 字节/罐
 *
 * 运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.factory.ZhongChengModbusProtocolConformanceTest"
 */
public class ZhongChengModbusProtocolConformanceTest {

    // ------------------------------------------------------------------
    // 文档 2 号罐"全部信息"举例（发送 08 03 03 f3 00 0c b5 21 / 返回见下）
    // ------------------------------------------------------------------

    /** 返回帧里 6 个字段各自的原始 4 字节浮点数据（已去掉地址/功能码/字节数/CRC）。 */
    private static final String[] TANK2_FIELD_HEX = {
            "3FBABB71", // 油高 ≈1.4588m
            "3DEB08B4", // 水高 ≈0.1148m
            "41DC8000", // 温度 ≈27.5625℃
            "462C98F0", // 油水体积 ≈11046.23L
            "462744F4", // 油体积   ≈10705.24L
            "43AA7F71", // 水体积   ≈340.996L  （油体积+水体积 == 油水体积，文档内部自洽）
    };

    private static final String TANK2_FULL_QUERY = "0803" + "03F3" + "000C"; // 地址08 命令03 寄存器03F3 数据长度12(寄存器数)
    private static final String TANK2_FULL_RESPONSE_BODY =
            "0803" + "18" + String.join("", TANK2_FIELD_HEX); // 地址08 命令03 字节数0x18(24) + 24字节数据
    private static final String TANK2_FULL_QUERY_CRC = "B521";     // 文档：b5 21（线上顺序，低字节在前）
    private static final String TANK2_FULL_RESPONSE_CRC = "64B2";  // 文档：64 b2

    // ------------------------------------------------------------------
    // 文档 2 号罐"仅油高"举例
    // ------------------------------------------------------------------
    private static final String TANK2_HEIGHT_QUERY = "0803" + "03F3" + "0002";
    private static final String TANK2_HEIGHT_QUERY_CRC = "34E5";
    private static final String TANK2_HEIGHT_RESPONSE_BODY = "0803" + "04" + "3FBABD2C";
    private static final String TANK2_HEIGHT_RESPONSE_CRC = "3E4F";

    // ------------------------------------------------------------------
    // 1. 寄存器地址换算：文档编号 - 1 = 线上地址
    // ------------------------------------------------------------------

    /**
     * [标准值] 文档"寄存器地址1000，1号罐油高"——Modbus 从站寄存器编号习惯从0开始，
     * 文档给的是"第几号"（1-based），线上实际地址 = 文档编号 - 1 = 999 = 0x03E7。
     * 从 2 号罐举例（文档地址1012 对应线上 03F3=1011=1012-1）可以反推验证这个换算关系成立。
     */
    @Test
    public void 文档寄存器编号减一等于线上地址_由2号罐举例反推验证() {
        int tank2DocumentedRegister = 1012; // 文档："1012 2罐油高"
        int tank2WireRegister = Integer.parseInt("03F3", 16);
        assertEquals(tank2DocumentedRegister - 1, tank2WireRegister);
    }

    // ------------------------------------------------------------------
    // 2. CRC：CRCheck.crc16() 换位后即线上顺序，跑文档里的真实例子
    // ------------------------------------------------------------------

    /** CRCheck.crc16() 返回高字节在前，换位后即线上顺序（低字节在前）。 */
    private static String crcAsOnWire(String hexBody) {
        String crc = CRCheck.crc16(DataUtils.HexString2Bytes(hexBody));
        return crc.substring(2) + crc.substring(0, 2);
    }

    @Test
    public void 查询帧CRC与文档一致_仅油高举例() {
        assertEquals(TANK2_HEIGHT_QUERY_CRC, crcAsOnWire(TANK2_HEIGHT_QUERY));
    }

    @Test
    public void 应答帧CRC与文档一致_仅油高举例() {
        assertEquals(TANK2_HEIGHT_RESPONSE_CRC, crcAsOnWire(TANK2_HEIGHT_RESPONSE_BODY));
    }

    @Test
    public void 查询帧CRC与文档一致_全部信息举例() {
        assertEquals(TANK2_FULL_QUERY_CRC, crcAsOnWire(TANK2_FULL_QUERY));
    }

    @Test
    public void 应答帧CRC与文档一致_全部信息举例() {
        assertEquals(TANK2_FULL_RESPONSE_CRC, crcAsOnWire(TANK2_FULL_RESPONSE_BODY));
    }

    // ------------------------------------------------------------------
    // 3. 字段编码：float 按 IEEE754 大端即文档线上字节
    // ------------------------------------------------------------------

    @Test
    public void floatToHex对油高字段编码与文档线上字节一致() {
        float height = 1.4588452577590942f; // 文档 3f ba bb 71 解出来的真实值
        assertEquals(TANK2_FIELD_HEX[0], DataUtils.floatToHex(height));
    }
}
