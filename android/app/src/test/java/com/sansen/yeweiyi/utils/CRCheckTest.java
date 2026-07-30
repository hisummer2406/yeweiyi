package com.sansen.yeweiyi.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * {@link CRCheck} 的单元测试。
 *
 * CRCheck 不依赖任何 android.* 类，可直接在 JVM 上运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.utils.CRCheckTest"
 *
 * 这是"织安全网"的起点：后续重构协议解析时，靠这些断言确认算法没被改坏。
 *
 * 说明：本类中的期望值分两种，注释里已逐条标明——
 *   [标准值] 来自 CRC 算法规范的公开校验值，算法写错就会失败；
 *   [现状固化] 记录当前实现的实际行为（含已知陷阱），用于防止无意改动。
 */
public class CRCheckTest {

    /** CRC 算法规范通用校验串 "123456789"，其 CRC-16/MODBUS 标准校验值为 0x4B37。 */
    private static final byte[] CHECK_STRING = "123456789".getBytes();

    /** 最常见的 Modbus 报文：读保持寄存器。其 CRC-16/MODBUS 为 0x0A84（高字节为 0）。 */
    private static final byte[] MODBUS_READ_FRAME =
            new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x01};

    // ---------------------------------------------------------------
    // crc16Modbus：返回原始 int，不做高低字节交换
    // ---------------------------------------------------------------

    /** [标准值] CRC-16/MODBUS 对 "123456789" 的校验值恒为 0x4B37。 */
    @Test
    public void crc16Modbus_标准校验值() {
        assertEquals(0x4B37, CRCheck.crc16Modbus(CHECK_STRING));
    }

    /** [标准值] 典型 Modbus 读报文的 CRC。 */
    @Test
    public void crc16Modbus_典型Modbus报文() {
        assertEquals(0x0A84, CRCheck.crc16Modbus(MODBUS_READ_FRAME));
    }

    /** [标准值] 空数组不参与运算，应返回预置值 0xFFFF。 */
    @Test
    public void crc16Modbus_空数组返回预置值() {
        assertEquals(0xFFFF, CRCheck.crc16Modbus(new byte[0]));
    }

    // ---------------------------------------------------------------
    // crc16 与 crc162：算法相同，仅返回字节序相反
    // 命名无法体现差异，调错不会报错、只会静默算出错误校验值 —— 用测试钉死
    // ---------------------------------------------------------------

    /** [现状固化] crc16 返回 高字节在前。 */
    @Test
    public void crc16_高字节在前() {
        assertEquals("4B37", CRCheck.crc16(CHECK_STRING));
        assertEquals("0A84", CRCheck.crc16(MODBUS_READ_FRAME));
    }

    /** [现状固化] crc162 返回 低字节在前（与 crc16 相反）。 */
    @Test
    public void crc162_低字节在前() {
        assertEquals("374B", CRCheck.crc162(CHECK_STRING));
        assertEquals("840A", CRCheck.crc162(MODBUS_READ_FRAME));
    }

    /**
     * [现状固化] 二者互为字节序反转，且绝不能混用。
     * 这条断言的作用：如果有人"顺手统一"了两个方法的返回顺序，这里会立刻失败。
     */
    @Test
    public void crc16与crc162互为字节序反转() {
        String a = CRCheck.crc16(CHECK_STRING);      // "4B37"
        String b = CRCheck.crc162(CHECK_STRING);     // "374B"
        assertNotEquals("两者必须保持不同的字节序", a, b);
        assertEquals("crc162 应为 crc16 的字节序反转",
                a.substring(2) + a.substring(0, 2), b);
    }

    // ---------------------------------------------------------------
    // getModBusCRC：已知陷阱 —— 小写 + 不补零
    // ---------------------------------------------------------------

    /** [现状固化] 返回小写十六进制（另两个方法返回大写）。 */
    @Test
    public void getModBusCRC_返回小写十六进制() {
        assertEquals("4b37", CRCheck.getModBusCRC(CHECK_STRING));
    }

    /**
     * [现状固化] 陷阱：内部用 Integer.toHexString，CRC 高位为 0 时不补零。
     *
     * 最常见的 Modbus 读报文 01 03 00 00 00 01 的 CRC 是 0x0A84，
     * 这里只返回 3 个字符 "a84"。
     *
     * 当前唯一调用点 LDCompany.java:72 用自带的 getString() 补零兜住了，
     * 因此不是线上故障；但正确性依赖每个调用者都记得补零。
     * 若将来给 getModBusCRC 内部加上补零，本测试会失败 —— 那是预期的，
     * 届时同步确认 LDCompany 的 getString() 是否还需要保留。
     */
    @Test
    public void getModBusCRC_高位为0时不补零_已知陷阱() {
        String crc = CRCheck.getModBusCRC(MODBUS_READ_FRAME);
        assertEquals("a84", crc);
        assertEquals("长度不足 4，调用方必须自行补零", 3, crc.length());
    }

    // ---------------------------------------------------------------
    // hexToBytes
    // ---------------------------------------------------------------

    @Test
    public void hexToBytes_基本转换() {
        assertArrayEquals(new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x01},
                CRCheck.hexToBytes("010300000001"));
    }

    /** [现状固化] 内部先 toUpperCase，故大小写均可。 */
    @Test
    public void hexToBytes_大小写不敏感() {
        assertArrayEquals(CRCheck.hexToBytes("ABCDEF"), CRCheck.hexToBytes("abcdef"));
    }

    @Test
    public void hexToBytes_处理高位字节() {
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0x80}, CRCheck.hexToBytes("FF80"));
    }

    /** hexToBytes 与 CRC 计算串起来：从十六进制字符串直接校验。 */
    @Test
    public void hexToBytes与crc16串联() {
        assertEquals(0x0A84, CRCheck.crc16Modbus(CRCheck.hexToBytes("010300000001")));
    }
}
