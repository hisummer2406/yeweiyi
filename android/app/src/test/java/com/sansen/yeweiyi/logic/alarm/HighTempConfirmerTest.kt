package com.sansen.yeweiyi.logic.alarm

import com.sansen.yeweiyi.logic.alarm.HighTempConfirmer.State
import org.junit.Assert.assertEquals
import org.junit.Test

class HighTempConfirmerTest {

    private val c = HighTempConfirmer()

    @Test
    fun 持续满5秒才确认() {
        assertEquals(State.PENDING, c.update(1, true, 1_000).state)
        assertEquals(State.PENDING, c.update(1, true, 3_500).state)
        assertEquals(State.PENDING, c.update(1, true, 5_999).state)
        val r = c.update(1, true, 6_000)
        assertEquals(State.CONFIRMED, r.state)
        assertEquals(5_000L, r.overMs)
        assertEquals(State.CONFIRMED, c.update(1, true, 7_000).state)
    }

    @Test
    fun 不满5秒回落为未确认() {
        c.update(1, true, 0)
        c.update(1, true, 4_900)
        val r = c.update(1, false, 6_200)
        assertEquals(State.DISMISSED, r.state)
        assertEquals(6_200L, r.overMs)
        assertEquals(State.NORMAL, c.update(1, false, 7_000).state)
    }

    @Test
    fun 中途一次不超就重新计时() {
        c.update(1, true, 0)
        c.update(1, true, 4_000)
        c.update(1, false, 4_500)
        assertEquals(State.PENDING, c.update(1, true, 5_000).state)
        assertEquals(State.PENDING, c.update(1, true, 9_999).state)
        assertEquals(State.CONFIRMED, c.update(1, true, 10_000).state)
    }

    @Test
    fun 确认后回落不算未确认() {
        c.update(1, true, 0)
        c.update(1, true, 5_000)
        assertEquals(State.NORMAL, c.update(1, false, 8_000).state)
    }

    @Test
    fun 同一读数反复检测不推进计时() {
        // 报警检测随液位帧反复运行，温度没有新读数时 readAt 不变
        c.update(1, true, 1_000)
        repeat(10) { assertEquals(State.PENDING, c.update(1, true, 1_000).state) }
        assertEquals(State.CONFIRMED, c.update(1, true, 6_000).state)
    }

    @Test
    fun 两次真实读数间隔超过5秒时第二次即确认() {
        assertEquals(State.PENDING, c.update(1, true, 0).state)
        assertEquals(State.CONFIRMED, c.update(1, true, 8_000).state)
    }

    @Test
    fun 各罐独立计时() {
        c.update(1, true, 0)
        c.update(2, true, 3_000)
        assertEquals(State.CONFIRMED, c.update(1, true, 5_000).state)
        assertEquals(State.PENDING, c.update(2, true, 5_000).state)
        assertEquals(State.CONFIRMED, c.update(2, true, 8_000).state)
    }
}
