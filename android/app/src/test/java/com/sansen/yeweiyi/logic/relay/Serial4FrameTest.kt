package com.sansen.yeweiyi.logic.relay

import com.sansen.yeweiyi.utils.DataUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class Serial4FrameTest {

    private fun hex(b: ByteArray) = DataUtils.bytesToHexString(b, b.size).trim()

    /** 《防溢阀自用串口报警协议》里 1~12 号罐"油位高高"的示例帧。 */
    @Test
    fun overflowValve_highHigh_matchesProtocolDocExamples() {
        val expected = listOf(
            "FE FE 01 01 03 04 0D F6", "FE FE 01 01 04 04 08 17", "FE FE 01 01 05 04 09 EC",
            "FE FE 01 01 06 04 0B E1", "FE FE 01 01 07 04 0A 1A", "FE FE 01 01 08 04 00 23",
            "FE FE 01 01 09 04 01 D8", "FE FE 01 01 0A 04 03 D5", "FE FE 01 01 0B 04 02 2E",
            "FE FE 01 01 0C 04 07 CF", "FE FE 01 01 0D 04 06 34", "FE FE 01 01 0E 04 04 39",
        )
        expected.forEachIndexed { i, frame ->
            assertEquals("tank ${i + 1}", frame, hex(Serial4Frame.overflowValve(i + 1, 4)))
        }
    }

    @Test
    fun overflowValve_otherCode_matchesProtocolDocExample() {
        // 文档示例：FE FE 01 01 05 02 09 D8（3 号罐油低）
        assertEquals("FE FE 01 01 05 02 09 D8", hex(Serial4Frame.overflowValve(3, 2)))
    }

    /** 中盛 IO 手册示例：通道 1 开 01 06 00 00 00 01 48 0A，关 01 06 00 00 00 00 89 CA。 */
    @Test
    fun modbusWrite_channel1_matchesManualExamples() {
        assertEquals("01 06 00 00 00 01 48 0A", hex(Serial4Frame.modbusWrite(1, 1)))
        assertEquals("01 06 00 00 00 00 89 CA", hex(Serial4Frame.modbusWrite(1, 0)))
    }

    @Test
    fun modbusWrite_tank11_usesHexRegisterAddress() {
        // 11 号罐寄存器 0x000A；旧代码 %02d 会拼成 0x0010
        assertEquals("01 06 00 0A 00 01", hex(Serial4Frame.modbusWrite(11, 1)).substring(0, 17))
    }
}
