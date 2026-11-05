package com.sansen.yeweiyi.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link UnitPref.Volume} 的单元测试——重点覆盖 2026-09-27 新增的 GALLON（美制加仑）选项。
 *
 * 运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.utils.UnitPrefTest"
 *
 * 关于可测性：UnitPref.java 零 import，不碰任何 Android API，纯 JVM 下可安全测试。
 */
public class UnitPrefTest {

    private static final double DELTA = 1e-9;

    // ---------------------------------------------------------------
    // parse()
    // ---------------------------------------------------------------

    @Test
    public void parse_识别加仑的规范值和历史文案变体() {
        assertEquals(UnitPref.Volume.GALLON, UnitPref.Volume.parse("GALLON"));
        assertEquals(UnitPref.Volume.GALLON, UnitPref.Volume.parse("gallon"));
        assertEquals(UnitPref.Volume.GALLON, UnitPref.Volume.parse("Gal"));
        assertEquals(UnitPref.Volume.GALLON, UnitPref.Volume.parse("GAL"));
        assertEquals(UnitPref.Volume.GALLON, UnitPref.Volume.parse("加仑"));
    }

    @Test
    public void parse_裸的G不识别为加仑_回落到L() {
        // 回归哨兵：parse() 认历史脏值是为了兼容曾经真的存进过 SharedPreferences 的展示文案，
        // 加仑是全新选项、不存在历史脏值，裸 "G" 不该被 parse() 接受（见 UnitPref.java 里的注释）。
        assertEquals(UnitPref.Volume.L, UnitPref.Volume.parse("G"));
        assertEquals(UnitPref.Volume.L, UnitPref.Volume.parse("g"));
    }

    @Test
    public void parse_已有的L和CUBIC不受影响() {
        assertEquals(UnitPref.Volume.L, UnitPref.Volume.parse("L"));
        assertEquals(UnitPref.Volume.L, UnitPref.Volume.parse("升"));
        assertEquals(UnitPref.Volume.CUBIC, UnitPref.Volume.parse("CUBIC"));
        assertEquals(UnitPref.Volume.CUBIC, UnitPref.Volume.parse("立方"));
        assertEquals(UnitPref.Volume.CUBIC, UnitPref.Volume.parse("m³"));
    }

    // ---------------------------------------------------------------
    // convert() —— raw 恒为升数
    // ---------------------------------------------------------------

    @Test
    public void convert_加仑换算系数() {
        assertEquals(2.6417, UnitPref.Volume.GALLON.convert(10), DELTA);
        assertEquals(0.26417, UnitPref.Volume.GALLON.convert(1), DELTA);
    }

    @Test
    public void convert_L和CUBIC不受影响() {
        assertEquals(10.0, UnitPref.Volume.L.convert(10), DELTA);
        assertEquals(0.01, UnitPref.Volume.CUBIC.convert(10), DELTA);
    }

    // ---------------------------------------------------------------
    // format() —— 换算 + 格式化后的展示字符串
    // ---------------------------------------------------------------

    @Test
    public void format_加仑保留1位小数跟L一致_不是CUBIC的4位小数() {
        assertEquals("2.6", UnitPref.Volume.GALLON.format(10)); // 10*0.26417=2.6417 -> 1位小数
        assertEquals("264.2", UnitPref.Volume.GALLON.format(1000)); // 1000*0.26417=264.17 -> 264.2
    }

    @Test
    public void format_L和CUBIC的小数位格式不受影响() {
        assertEquals("10.0", UnitPref.Volume.L.format(10));
        assertEquals("10.0000", UnitPref.Volume.CUBIC.format(10000));
    }
}
