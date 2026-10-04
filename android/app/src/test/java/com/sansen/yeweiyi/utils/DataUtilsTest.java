package com.sansen.yeweiyi.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * {@link DataUtils} 中字节/十六进制转换部分的单元测试。
 *
 * 运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.utils.DataUtilsTest"
 *
 * 关于可测性：DataUtils 整体 import 了 R / MyApplication / ATGJNI，但这些都在方法内部使用，
 * 类里没有静态初始化块，所以类加载不会触发 System.loadLibrary，纯 JVM 下可安全测试。
 * 本类只覆盖不碰 Android 的方法 —— 刻意避开 getLitre()(走 ATGJNI) 和 getOilType()(走 MyApplication)。
 *
 * 期望值来源同 CRCheckTest：逐行复刻实现的位运算推导得出，并已交叉验算。
 * 注释标记含义：
 *   [标准值]   算法/规范决定的值，实现写错就会失败
 *   [现状固化] 记录当前实现的实际行为（含已知陷阱），防止无意改动
 */
public class DataUtilsTest {

    /** 典型 Modbus 读保持寄存器报文。 */
    private static final byte[] FRAME = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x01};

    /** 含字母十六进制位的字节，用于暴露各方法的大小写差异。 */
    private static final byte[] LETTER_BYTES = new byte[]{0x0A, (byte) 0xBC};

    // ---------------------------------------------------------------
    // 十六进制字符串 -> 字节
    // ---------------------------------------------------------------

    @Test
    public void HexString2Bytes_基本转换() {
        assertArrayEquals(FRAME, DataUtils.HexString2Bytes("010300000001"));
    }

    @Test
    public void hexStringToByteArray_会先去掉空格() {
        assertArrayEquals(FRAME, DataUtils.hexStringToByteArray("01 03 00 00 00 01"));
        assertArrayEquals(FRAME, DataUtils.hexStringToByteArray("010300000001"));
    }

    /**
     * [现状固化] HexString2Bytes 与 hexString2Bytes 是完全重复的两个方法，
     * 实现逐字节相同，只有首字母大小写不同。
     *
     * 保留这条断言是为了让"其中一个被改了、另一个没改"的情况立刻暴露。
     * 真要收敛的话应删掉一个并统一调用点，而不是让两份实现各自漂移。
     */
    @Test
    public void HexString2Bytes与hexString2Bytes是重复实现() {
        String src = "010300000001";
        assertArrayEquals(DataUtils.HexString2Bytes(src), DataUtils.hexString2Bytes(src));
    }

    // ---------------------------------------------------------------
    // 字节 -> 十六进制字符串：三个同名/近名方法，格式互不相同
    // 这是最容易在协议拼包时踩错的地方，逐个钉死
    // ---------------------------------------------------------------

    /**
     * [现状固化] 带 size 的重载：**大写** + 每字节后加空格（含结尾空格）。
     * 用含字母的字节(0x0A/0xBC)才能真正体现大小写差异，纯数字字节是看不出来的。
     */
    @Test
    public void bytesToHexString_带size版本_大写且空格分隔() {
        assertEquals("0A BC ", DataUtils.bytesToHexString(LETTER_BYTES, LETTER_BYTES.length));
        assertEquals("01 03 00 00 00 01 ", DataUtils.bytesToHexString(FRAME, FRAME.length));
    }

    /**
     * [现状固化] 不带 size 的重载：**小写** + 无分隔符。
     * 与上面同名，格式却完全不同 —— 拼协议帧时选错重载不会报错，只会静默拼出错误报文。
     */
    @Test
    public void bytesToHexString_不带size版本_小写且无分隔() {
        assertEquals("0abc", DataUtils.bytesToHexString(LETTER_BYTES));
        assertEquals("010300000001", DataUtils.bytesToHexString(FRAME));
    }

    /** [现状固化] 单字节重载：小写且**不补零**，0x0A 只返回 "a" 而不是 "0a"。 */
    @Test
    public void bytesToHexString_单字节版本_不补零_已知陷阱() {
        assertEquals("a", DataUtils.bytesToHexString((byte) 0x0A));
        assertEquals("ff", DataUtils.bytesToHexString((byte) 0xFF));
    }

    /** [现状固化] byteToHexString 才是补零的那个（与上面单字节版本行为相反）。 */
    @Test
    public void byteToHexString_补零到两位() {
        assertEquals("0a", DataUtils.byteToHexString((byte) 0x0A));
        assertEquals("ff", DataUtils.byteToHexString((byte) 0xFF));
    }

    @Test
    public void bytesToHexString_size非法时返回null() {
        assertNull(DataUtils.bytesToHexString(FRAME, 0));
        assertNull(DataUtils.bytesToHexString(null, 2));
    }

    // ---------------------------------------------------------------
    // 数值转换
    // ---------------------------------------------------------------

    @Test
    public void byte2Short_大端解析() {
        assertEquals(2692, DataUtils.byte2Short(new byte[]{0x0A, (byte) 0x84}));
        assertEquals(-1, DataUtils.byte2Short(new byte[]{(byte) 0xFF, (byte) 0xFF}));
        assertEquals(0, DataUtils.byte2Short(new byte[]{0x00, 0x00}));
    }

    /** [现状固化] byteToShort 与 byte2Short 是重复实现，行为必须一致。 */
    @Test
    public void byteToShort与byte2Short是重复实现() {
        byte[] v = new byte[]{0x0A, (byte) 0x84};
        assertEquals(DataUtils.byte2Short(v), DataUtils.byteToShort(v));
    }

    @Test
    public void byte2Int_大端解析() {
        assertEquals(2692, DataUtils.byte2Int(new byte[]{0x00, 0x00, 0x0A, (byte) 0x84}));
        assertEquals(-1, DataUtils.byte2Int(
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    public void shortToByte_与byte2Short互逆() {
        assertArrayEquals(new byte[]{0x0A, (byte) 0x84}, DataUtils.shortToByte((short) 2692));
        assertEquals(2692, DataUtils.byte2Short(DataUtils.shortToByte((short) 2692)));
    }

    @Test
    public void int2Byte_与byte2Int互逆() {
        assertArrayEquals(new byte[]{0x00, 0x00, 0x0A, (byte) 0x84}, DataUtils.int2Byte(2692));
        assertEquals(2692, DataUtils.byte2Int(DataUtils.int2Byte(2692)));
    }

    @Test
    public void toShortArray与toByteArray互逆() {
        short[] shorts = new short[]{2692, -1, 0};
        assertArrayEquals(shorts, DataUtils.toShortArray(DataUtils.toByteArray(shorts)));
    }

    // ---------------------------------------------------------------
    // 十进制 -> 十六进制字符串
    // ---------------------------------------------------------------

    @Test
    public void Dec2Hex_基本转换() {
        assertEquals("A84", DataUtils.Dec2Hex(2692));
        assertEquals("0", DataUtils.Dec2Hex(0));
        assertEquals("FF", DataUtils.Dec2Hex(255));
    }

    /** [现状固化] 负数返回带负号的十六进制（不是补码表示）。 */
    @Test
    public void Dec2Hex_负数返回负号加绝对值() {
        assertEquals("-A", DataUtils.Dec2Hex(-10));
    }

    /** [现状固化] 只补到 2 位；超过 2 位的原样返回，不会补到 4 位。 */
    @Test
    public void Dec2HexAddZero_只补到两位() {
        assertEquals("05", DataUtils.Dec2HexAddZero(5));
        assertEquals("FF", DataUtils.Dec2HexAddZero(255));
        assertEquals("A84", DataUtils.Dec2HexAddZero(2692));
    }

    // ---------------------------------------------------------------
    // 校验和
    // ---------------------------------------------------------------

    /**
     * [现状固化] 名字叫 SumCheck(校验和)，实际算的是"全部字节异或后再减 0x80"。
     * 名实不符，改动前先确认下位机协议到底要哪种。
     */
    @Test
    public void SumCheck_实为异或后减0x80() {
        // 0x01^0x03^0x00^0x00^0x00^0x01 = 0x03; (byte)(0x03 - 0x80) = -125
        assertEquals((byte) -125, DataUtils.SumCheck(FRAME));
    }

    @Test
    public void SumCheck_空数组() {
        // XOR 初值 0 -> (byte)(0 - 0x80) = -128
        assertEquals((byte) -128, DataUtils.SumCheck(new byte[0]));
    }

    // ---------------------------------------------------------------
    // 浮点解析 —— 存在会抛异常的边界
    // ---------------------------------------------------------------

    @Test
    public void hexToFloat_正数正常() {
        assertEquals(25.5f, DataUtils.hexToFloat("41CC0000"), 0.0001f);
        assertEquals(25.5f, DataUtils.hexToFloat("41 CC 00 00"), 0.0001f);
        assertEquals(0.0f, DataUtils.hexToFloat("00000000"), 0.0001f);
    }

    /**
     * [现状固化] 陷阱：hexToFloat 内部用 Integer.parseInt(hex, 16)，上限 0x7FFFFFFF。
     *
     * 任何**负数**浮点的 IEEE754 表示都会把符号位置 1（如 -5.0f = 0xC0A00000 = 3231711232），
     * 超出 int 范围，直接抛 NumberFormatException 而不是返回负数。
     *
     * 目前 hexToFloat / byteToFloat 在项目里没有任何调用点，所以这不是线上故障；
     * 但液位仪要读负温度，一旦有人拿它解析温度就会炸。
     * 修法：改用 Integer.parseUnsignedInt(hex, 16) 或 (int) Long.parseLong(hex, 16)。
     * 修好之后本测试会失败 —— 那时把它改成断言 -5.0f 即可。
     */
    @Test
    public void hexToFloat_负数会抛异常_已知缺陷() {
        try {
            DataUtils.hexToFloat("C0A00000");   // -5.0f
            fail("预期抛 NumberFormatException；若已修复，请把本测试改为断言 -5.0f");
        } catch (NumberFormatException expected) {
            // 当前实现的既有行为
        }
    }

    // ---------------------------------------------------------------
    // 数值格式化
    // ---------------------------------------------------------------

    /**
     * 注意：doubleFoment 用 DecimalFormat，依赖默认 Locale。
     * 在小数点为逗号的 Locale 下 Double.parseDouble 会抛异常 —— 这也是实现的一个脆弱点。
     * 下面刻意避开 x.x5 这类受 HALF_EVEN 舍入影响的边界值。
     */
    @Test
    public void doubleFoment_保留一位小数() {
        assertEquals(3.1, DataUtils.doubleFoment(3.14159), 1e-9);
        assertEquals(2.0, DataUtils.doubleFoment(2.0), 1e-9);
    }

    /**
     * 当"0.0"格式化后长度超过 7，改用"0"重新格式化，只显示整数——符合代码注释的原意。
     */
    @Test
    public void doubleFoment_超过7位时只显示整数() {
        // "123456.8" 长度为 8 > 7，于是改用 0 格式（只显示整数）-> 123457
        assertEquals(123457.0, DataUtils.doubleFoment(123456.78), 1e-9);
    }

    @Test
    public void doubleFomentStr_返回字符串形式() {
        assertEquals("3.1", DataUtils.doubleFomentStr(3.14159));
    }

    @Test
    public void doubleFomentStr2_固定四位小数() {
        assertEquals("3.1416", DataUtils.doubleFomentStr2(3.14159));
        assertEquals("2.0000", DataUtils.doubleFomentStr2(2.0));
    }
}
