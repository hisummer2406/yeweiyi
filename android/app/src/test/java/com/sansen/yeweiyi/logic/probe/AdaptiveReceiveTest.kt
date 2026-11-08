package com.sansen.yeweiyi.logic.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用虚拟时钟模拟串口：script 按"第几次读"给出字节，sleep 只推进时钟。 */
class AdaptiveReceiveTest {

    private class FakePort(private val script: Map<Int, ByteArray>) {
        var now = 0L
        var reads = 0
        fun receiver() = AdaptiveReceive(
            read = { script[++reads] },
            sleep = { now += it },
            nowMs = { now },
        )
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun 应答晚于旧固定等待也能收到() {
        // 第 15 次读 = 120ms，超过原来的 100ms
        val port = FakePort(mapOf(15 to bytes(1, 2, 3)))
        assertArrayEquals(bytes(1, 2, 3), port.receiver().receive())
        assertEquals(18, port.reads) // 收到后再空读 3 次即结束
    }

    @Test
    fun 分段到达的一帧拼成一块() {
        val port = FakePort(mapOf(2 to bytes(1, 2), 3 to bytes(3), 5 to bytes(4)))
        assertArrayEquals(bytes(1, 2, 3, 4), port.receiver().receive())
        assertEquals(8, port.reads)
    }

    @Test
    fun 无应答等到上限返回null() {
        val port = FakePort(emptyMap())
        assertNull(port.receiver().receive())
        assertEquals(AdaptiveReceive.MAX_WAIT_MS, port.now)
    }

    @Test
    fun 持续有字节也在上限停止() {
        val script = (1..100).associateWith { bytes(it) }
        val port = FakePort(script)
        val got = port.receiver().receive()!!
        assertEquals(AdaptiveReceive.MAX_WAIT_MS, port.now)
        assertEquals(25, got.size)
    }
}
