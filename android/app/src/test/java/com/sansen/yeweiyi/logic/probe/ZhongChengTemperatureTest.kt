package com.sansen.yeweiyi.logic.probe

import com.sansen.yeweiyi.factory.ZhongChengCompany
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 正负温度按 docs/协议/新版中成探针协议.doc 及 DS18B20 同格式数据手册的取值；
 * 无效帧取自现场日志 yeweiyi-log-unknown-20261018-145137（2 号探针，实际约 23℃）。
 */
class ZhongChengTemperatureTest {

    private fun b(v: Int) = v.toByte()

    private fun decode(hi: Int, lo: Int) = ZhongChengTemperature.decode(b(hi), b(lo))

    private fun reason(hi: Int, lo: Int) = ZhongChengTemperature.invalidReason(b(hi), b(lo))

    @Test
    fun 正温度() {
        assertEquals(24.6875, decode(0x01, 0x8B), 0.0) // 协议文档举例
        assertEquals(25.0, decode(0x01, 0x90), 0.0)
        assertEquals(23.75, decode(0x01, 0x7C), 0.0)
        assertEquals(0.0, decode(0x00, 0x00), 0.0)
        assertEquals(85.0, decode(0x05, 0x50), 0.0)
    }

    @Test
    fun 负温度按补码() {
        assertEquals(-0.5, decode(0xFF, 0xF8), 0.0)
        assertEquals(-5.0, decode(0xFF, 0xB0), 0.0)
        assertEquals(-10.125, decode(0xFF, 0x5E), 0.0)
        assertEquals(-25.0625, decode(0xFE, 0x6F), 0.0)
        assertEquals(-55.0, decode(0xFC, 0x90), 0.0)
    }

    @Test
    fun 解析入口走有符号换算() {
        assertEquals(-5.0, ZhongChengCompany.getInstance().calcTemperature(b(0xFF), b(0xB0)), 0.0)
    }

    @Test
    fun 正常范围有效() {
        assertNull(reason(0x01, 0x7C))
        assertNull(reason(0x03, 0x7D)) // 55.8℃：格式合法且低于 85℃，过滤不掉，靠报警持续确认
        assertNull(reason(0x05, 0x4F)) // 84.9375℃
        assertNull(reason(0xFF, 0xB0))
        assertNull(reason(0xFC, 0x90))
    }

    @Test
    fun 现场符号位不一致的帧无效() {
        for (hi in intArrayOf(0x81, 0x41, 0x21, 0x11, 0x09)) {
            assertEquals("hi=%02X".format(hi), ZhongChengTemperature.REASON_SIGN_BITS, reason(hi, 0x7C))
        }
        // 负温度符号位被翻掉一位同样无效
        assertEquals(ZhongChengTemperature.REASON_SIGN_BITS, reason(0x7F, 0xB0))
    }

    @Test
    fun 大于等于85度无效() {
        assertEquals(ZhongChengTemperature.REASON_TOO_HIGH, reason(0x05, 0x50)) // 85℃，芯片上电复位值
        assertEquals(ZhongChengTemperature.REASON_TOO_HIGH, reason(0x05, 0x7B)) // 现场 87.6875℃
        assertEquals(ZhongChengTemperature.REASON_TOO_HIGH, reason(0x07, 0xD0)) // 125℃
    }
}
