package com.sansen.yeweiyi.logic.inout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InOutSettleWindowTest {
    private val thresholds = InOutThresholds.of(100.0, 180, 100.0, 180)

    @Test
    fun bothDirections_waitForFullMinuteAfterLastChange() {
        for (sign in listOf(1, -1)) {
            val tracker = TankLevelTracker(1)
            assertNull(tracker.onReading(2000.0, 0, thresholds))
            for (second in 1..200) {
                assertNull(tracker.onReading(2000.0 + sign * second * 2, second * 1000L, thresholds))
            }
            val endHeight = 2000.0 + sign * 400
            for (second in 201..259) {
                assertNull(tracker.onReading(endHeight, second * 1000L, thresholds))
            }
            val event = tracker.onReading(endHeight, 260_000L, thresholds)
            assertNotNull(event)
            assertEquals(if (sign > 0) InOutDirection.IN else InOutDirection.OUT, event!!.direction)
            assertEquals(2000.0, event.startHeight, 0.001)
            assertEquals(endHeight, event.endHeight, 0.001)
            assertFalse(tracker.isTracking)
            assertNull(tracker.onReading(endHeight, 261_000L, thresholds))
        }
    }

    @Test
    fun thirtySecondPause_keepsOriginalStartAndRestartsStableWait() {
        val tracker = TankLevelTracker(1)
        assertNull(tracker.onReading(2000.0, 0, thresholds))
        for (second in 1..200) {
            assertNull(tracker.onReading(2000.0 + second * 2, second * 1000L, thresholds))
        }
        for (second in 201..230) {
            assertNull(tracker.onReading(2400.0, second * 1000L, thresholds))
        }
        for (second in 231..430) {
            assertNull(tracker.onReading(2400.0 + (second - 230) * 2, second * 1000L, thresholds))
        }
        for (second in 431..489) {
            assertNull(tracker.onReading(2800.0, second * 1000L, thresholds))
        }
        val event = tracker.onReading(2800.0, 490_000L, thresholds)
        assertNotNull(event)
        assertEquals(2000.0, event!!.startHeight, 0.001)
        assertEquals(2800.0, event.endHeight, 0.001)
        assertEquals(1000L, event.startTimeMillis)
    }

    @Test
    fun stableForMinute_stillRequiresBusinessTimeThreshold() {
        val tracker = TankLevelTracker(1)
        tracker.onReading(1000.0, 0, thresholds)
        tracker.onReading(1200.0, 1000L, thresholds)
        for (second in 2..180) {
            assertNull(tracker.onReading(1200.0, second * 1000L, thresholds))
        }
        assertNotNull(tracker.onReading(1200.0, 181_000L, thresholds))
    }
}
