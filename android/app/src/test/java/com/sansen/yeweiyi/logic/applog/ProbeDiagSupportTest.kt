package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDiagSupportTest {

    @Test
    fun firstFrameIsBaselineOnly() {
        val d = TempAnomalyDetector().evaluate(1, 20.0, 60.0)
        assertTrue(d.first)
        assertFalse(d.isAnomaly)
        assertTrue(d.previous.isNaN())
    }

    @Test
    fun firstFrameAlreadyOverLimitIsAnomaly() {
        val d = TempAnomalyDetector().evaluate(1, 70.0, 60.0)
        assertTrue(d.first)
        assertEquals(TempAnomalyDetector.REASON_OVER_LIMIT, d.reason)
    }

    @Test
    fun jumpAndRecoveryAreBothReported() {
        val det = TempAnomalyDetector()
        det.evaluate(1, 18.5, 0.0)
        assertNull(det.evaluate(1, 19.0, 0.0).reason)
        val spike = det.evaluate(1, 4095.0, 0.0)
        assertEquals("jump", spike.reason)
        assertEquals(19.0, spike.previous, 0.0)
        assertEquals("jump", det.evaluate(1, 19.2, 0.0).reason)
    }

    @Test
    fun overLimitOnlyOnEntryThenBackUnderWithFrameCount() {
        val det = TempAnomalyDetector()
        det.evaluate(2, 58.0, 60.0)
        assertEquals("overLimit", det.evaluate(2, 61.0, 60.0).reason)
        assertNull(det.evaluate(2, 61.5, 60.0).reason)
        assertNull(det.evaluate(2, 62.0, 60.0).reason)
        val back = det.evaluate(2, 59.0, 60.0)
        assertEquals("backUnderLimit", back.reason)
        assertEquals(3, back.overLimitFrames)
    }

    @Test
    fun jumpIntoOverLimitReportsBoth() {
        val det = TempAnomalyDetector()
        det.evaluate(3, 20.0, 60.0)
        assertEquals("jump,overLimit", det.evaluate(3, 85.0, 60.0).reason)
    }

    @Test
    fun zeroThresholdNeverOverLimitAndTanksAreIndependent() {
        val det = TempAnomalyDetector()
        det.evaluate(1, 100.0, 0.0)
        det.evaluate(2, 20.0, 0.0)
        assertNull(det.evaluate(1, 101.0, 0.0).reason)
        assertNull(det.evaluate(2, 21.0, 0.0).reason)
    }

    @Test
    fun resetMakesNextFrameFirstAgain() {
        val det = TempAnomalyDetector()
        det.evaluate(1, 20.0, 0.0)
        det.reset()
        assertTrue(det.evaluate(1, 90.0, 0.0).first)
    }

    @Test
    fun rateLimiterCountsSkipped() {
        val rl = KeyedRateLimiter(10_000)
        assertEquals(0, rl.tryAcquire("a", 0))
        assertEquals(KeyedRateLimiter.DENIED, rl.tryAcquire("a", 1000))
        assertEquals(KeyedRateLimiter.DENIED, rl.tryAcquire("a", 2000))
        assertEquals(0, rl.tryAcquire("b", 2000))
        assertEquals(2, rl.tryAcquire("a", 10_000))
        assertEquals(KeyedRateLimiter.DENIED, rl.tryAcquire("a", 15_000))
        assertEquals(1, rl.tryAcquire("a", 14_000 - 20_000))
    }

    @Test
    fun frameRingKeepsNewestInOrderPerTank() {
        val ring = FrameRing(3)
        for (i in 1..5) ring.add(1, i * 1000L, "44", "F$i", "ok")
        ring.add(2, 0, "45", null, "noResp")
        val lines = ring.snapshot(1)
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("kind=44 result=ok frame=F3"))
        assertTrue(lines[2].endsWith("frame=F5"))
        assertTrue(ring.snapshot(2).single().endsWith("kind=45 result=noResp frame=-"))
        assertTrue(ring.snapshot(9).isEmpty())
    }

    @Test
    fun commStatsDrainResets() {
        val st = ProbeCommStats()
        st.recordOk(2)
        st.recordOk(2)
        st.recordBad(2)
        st.recordNoResponse(1)
        val first = st.drain()
        assertEquals(listOf(1, 2), first.keys.toList())
        assertEquals(2, first.getValue(2).ok)
        assertEquals(1, first.getValue(2).bad)
        assertEquals(1, first.getValue(1).noResp)
        assertTrue(st.drain().isEmpty())
    }

    @Test
    fun takeNewNeverRepeatsFramesAcrossBackToBackAlarms() {
        val ring = FrameRing(30)
        for (i in 1..5) ring.add(1, i * 1000L, "45", "F$i", "ok")
        val first = ring.takeNew(1)
        assertEquals(5, first.lines.size)
        assertNull(first.sinceMs)
        assertFalse(first.gap)

        // 温度报警后紧接着液位报警，中间没有新帧：不重复写
        val same = ring.takeNew(1)
        assertTrue(same.lines.isEmpty())
        assertEquals(5000L, same.sinceMs)

        ring.add(1, 6000, "44", "F6", "ok")
        val next = ring.takeNew(1)
        assertEquals(1, next.lines.size)
        assertTrue(next.lines[0].endsWith("frame=F6"))
        assertEquals(5000L, next.sinceMs)
        assertFalse(next.gap)
    }

    @Test
    fun takeNewMarksGapWhenFramesWereEvicted() {
        val ring = FrameRing(3)
        ring.add(1, 1000, "45", "A", "ok")
        ring.takeNew(1)
        for (i in 2..6) ring.add(1, i * 1000L, "45", "F$i", "ok")
        val batch = ring.takeNew(1)
        assertEquals(3, batch.lines.size)
        assertTrue(batch.gap)
        assertTrue(batch.lines[0].endsWith("frame=F4"))
    }

    @Test
    fun takeNewIsPerTankAndSnapshotDoesNotAdvance() {
        val ring = FrameRing(30)
        ring.add(1, 1000, "45", "A", "ok")
        ring.add(2, 1000, "45", "B", "ok")
        assertEquals(1, ring.snapshot(1).size)
        assertEquals(1, ring.takeNew(1).lines.size)
        assertEquals(1, ring.takeNew(2).lines.size)
    }

    @Test
    fun windowLimiterAllowsMaxPerWindowAndCountsSkipped() {
        val rl = WindowRateLimiter(10_000, 3)
        assertEquals(0, rl.tryAcquire("p1", 0))
        assertEquals(0, rl.tryAcquire("p1", 1000))
        assertEquals(0, rl.tryAcquire("p1", 2000))
        assertEquals(KeyedRateLimiter.DENIED, rl.tryAcquire("p1", 3000))
        assertEquals(KeyedRateLimiter.DENIED, rl.tryAcquire("p1", 4000))
        assertEquals(0, rl.tryAcquire("p2", 4000))
        // 第一次放行已滑出窗口
        assertEquals(2, rl.tryAcquire("p1", 10_000))
        // 时钟往回调：重新计数
        assertEquals(0, rl.tryAcquire("p1", 500))
    }
}
