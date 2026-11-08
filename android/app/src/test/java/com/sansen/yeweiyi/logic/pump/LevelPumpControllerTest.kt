package com.sansen.yeweiyi.logic.pump

import com.sansen.yeweiyi.logic.pump.LevelPumpController.Change
import com.sansen.yeweiyi.logic.pump.LevelPumpController.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelPumpControllerTest {

    private val c = LevelPumpController(staleTimeoutMs = 60_000)
    private val llow = 500
    private val hhigh = 2000

    @Test
    fun 低于低低闭合_回差内保持_高于高高断开() {
        assertEquals(Change(true, Reason.LOW_LOW), c.onReading(499.0, llow, hhigh, 0))
        assertNull(c.onReading(1000.0, llow, hhigh, 1_000))
        assertNull(c.onReading(2000.0, llow, hhigh, 2_000)) // 等于高高不断开，与报警的 > 一致
        assertTrue(c.isOn)
        assertEquals(Change(false, Reason.HIGH_HIGH), c.onReading(2000.1, llow, hhigh, 3_000))
        assertFalse(c.isOn)
    }

    @Test
    fun 开机时液位在两者之间不闭合() {
        assertNull(c.onReading(1000.0, llow, hhigh, 0))
        assertNull(c.onReading(500.0, llow, hhigh, 1_000)) // 等于低低不闭合
        assertFalse(c.isOn)
    }

    @Test
    fun 断开后回升途中不再闭合_直到再次低于低低() {
        c.onReading(100.0, llow, hhigh, 0)
        c.onReading(2100.0, llow, hhigh, 1_000)
        assertNull(c.onReading(1000.0, llow, hhigh, 2_000))
        assertNull(c.onReading(600.0, llow, hhigh, 3_000))
        assertEquals(Change(true, Reason.LOW_LOW), c.onReading(400.0, llow, hhigh, 4_000))
    }

    @Test
    fun 阈值附近抖动不反复切换() {
        c.onReading(499.0, llow, hhigh, 0)
        for (i in 1..10) {
            assertNull(c.onReading(if (i % 2 == 0) 499.0 else 501.0, llow, hhigh, i * 1_000L))
        }
        assertTrue(c.isOn)
    }

    @Test
    fun 阈值无效时强制断开且不闭合() {
        c.onReading(100.0, llow, hhigh, 0)
        assertEquals(Change(false, Reason.INVALID_THRESHOLD), c.onReading(100.0, 0, hhigh, 1_000))
        assertNull(c.onReading(100.0, llow, 0, 2_000))
        assertNull(c.onReading(100.0, 2000, 500, 3_000)) // 倒挂
        assertNull(c.onReading(100.0, 1000, 1000, 4_000))
        assertFalse(c.isOn)
    }

    @Test
    fun 探针断线断开() {
        assertNull(c.onProbeLost())
        c.onReading(100.0, llow, hhigh, 0)
        assertEquals(Change(false, Reason.PROBE_LOST), c.onProbeLost())
        assertFalse(c.isOn)
    }

    @Test
    fun 闭合时超时无读数断开() {
        assertNull(c.checkStale(1_000_000)) // 断开状态不处理
        c.onReading(100.0, llow, hhigh, 0)
        assertNull(c.checkStale(60_000))
        c.onReading(600.0, llow, hhigh, 50_000) // 新读数刷新计时
        assertNull(c.checkStale(110_000))
        assertEquals(Change(false, Reason.STALE), c.checkStale(110_001))
    }

    @Test
    fun 重置断开并回到初始状态() {
        assertNull(c.reset())
        c.onReading(100.0, llow, hhigh, 0)
        assertEquals(Change(false, Reason.RESET), c.reset())
        assertNull(c.onReading(1000.0, llow, hhigh, 1_000))
        assertFalse(c.isOn)
    }

    @Test
    fun 阈值校验() {
        assertTrue(LevelPumpController.thresholdsValid(1, 2))
        assertFalse(LevelPumpController.thresholdsValid(0, 2))
        assertFalse(LevelPumpController.thresholdsValid(2, 0))
        assertFalse(LevelPumpController.thresholdsValid(2, 2))
        assertFalse(LevelPumpController.thresholdsValid(3, 2))
    }
}
