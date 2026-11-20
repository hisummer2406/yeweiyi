package com.sansen.yeweiyi.logic.thingsboard

import com.sansen.yeweiyi.logic.thingsboard.SnapshotTrigger.TankLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 遥测快照触发规则：30 秒保底 + 液位变化 ≥1 mm 且距上次 ≥5 秒立即发（report-snapshot-on-level-change）。 */
class SnapshotTriggerTest {

    private val period = 30_000L

    private fun levels(vararg tanks: Pair<Int, TankLevel>) = mapOf(*tanks)
    private fun lv(oil: Double, water: Double = 0.0) = TankLevel(oil, water)

    /** 在 t=0 发出过一条快照，液位为 [base]。 */
    private fun publishedAtZero(base: Map<Int, TankLevel>): SnapshotTrigger {
        val trigger = SnapshotTrigger(period)
        assertTrue("启动后第一次检查必发", trigger.shouldPublish(0, base))
        trigger.onPublished(0, base)
        return trigger
    }

    @Test
    fun firstCheckAlwaysPublishes() {
        assertTrue(SnapshotTrigger(period).shouldPublish(123, levels(1 to lv(1000.0))))
    }

    @Test
    fun unchangedLevels_publishOnlyWhenPeriodElapses() {
        val base = levels(1 to lv(1000.0))
        val trigger = publishedAtZero(base)
        assertFalse(trigger.shouldPublish(29_999, base))
        assertTrue(trigger.shouldPublish(30_000, base))
    }

    @Test
    fun oilChangeOfOneMm_publishesAfterMinGap() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        val moved = levels(1 to lv(1001.0))
        assertFalse("不到 5 秒限速", trigger.shouldPublish(4_999, moved))
        assertTrue(trigger.shouldPublish(5_000, moved))
    }

    @Test
    fun waterChangeOfOneMm_alsoTriggers() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0, water = 10.0)))
        assertTrue(trigger.shouldPublish(5_000, levels(1 to lv(1000.0, water = 9.0))))
    }

    @Test
    fun jitterBelowThreshold_doesNotTrigger() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        assertFalse(trigger.shouldPublish(10_000, levels(1 to lv(1000.9))))
        assertFalse(trigger.shouldPublish(11_000, levels(1 to lv(999.1))))
    }

    @Test
    fun slowDriftAccumulatesAgainstLastPublishedValue() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        // 每秒 0.1 mm：跟上一秒比永远不到 1 mm，跟上次发出的值比第 10 秒累计到 1 mm
        assertFalse(trigger.shouldPublish(9_000, levels(1 to lv(1000.9))))
        assertTrue(trigger.shouldPublish(10_000, levels(1 to lv(1001.0))))
    }

    @Test
    fun continuousChange_isRateLimitedToMinGap() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        assertTrue(trigger.shouldPublish(5_000, levels(1 to lv(1005.0))))
        trigger.onPublished(5_000, levels(1 to lv(1005.0)))
        assertFalse(trigger.shouldPublish(6_000, levels(1 to lv(1006.0))))
        assertFalse(trigger.shouldPublish(9_999, levels(1 to lv(1009.0))))
        assertTrue(trigger.shouldPublish(10_000, levels(1 to lv(1010.0))))
    }

    @Test
    fun changeInAnyTankTriggers() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0), 2 to lv(500.0)))
        assertTrue(trigger.shouldPublish(5_000, levels(1 to lv(1000.0), 2 to lv(498.0))))
    }

    @Test
    fun tankSetChange_triggers() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        assertTrue("新增罐", trigger.shouldPublish(5_000, levels(1 to lv(1000.0), 2 to lv(0.0))))
        assertTrue("删除罐", trigger.shouldPublish(5_000, levels()))
    }

    @Test
    fun publishingResetsPeriod() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        val moved = levels(1 to lv(1002.0))
        trigger.onPublished(10_000, moved)
        assertFalse("变化触发后保底周期顺延，不在第 30 秒发", trigger.shouldPublish(30_000, moved))
        assertTrue(trigger.shouldPublish(40_000, moved))
    }

    @Test
    fun failedPublish_keepsBaselineSoNextCheckRetries() {
        val trigger = publishedAtZero(levels(1 to lv(1000.0)))
        val moved = levels(1 to lv(1020.0))
        assertTrue(trigger.shouldPublish(5_000, moved))
        // 发布失败：不调 onPublished
        assertTrue("下一秒仍以旧基准判定，继续尝试", trigger.shouldPublish(6_000, moved))
    }

    @Test
    fun periodCanBeChangedAtRuntime() {
        val base = levels(1 to lv(1000.0))
        val trigger = publishedAtZero(base)
        trigger.periodMillis = 60_000
        assertFalse(trigger.shouldPublish(30_000, base))
        assertTrue(trigger.shouldPublish(60_000, base))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositivePeriodIsRejected() {
        SnapshotTrigger(period).periodMillis = 0
    }

}
