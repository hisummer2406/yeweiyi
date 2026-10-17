package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatSuppressorTest {

    private fun rec(t: Long, body: String, module: LogModule = LogModule.PROBE, critical: Boolean = false) =
        LogRecord(t, module, LogLevel.INFO, body, critical)

    @Test
    fun repeatsInsideWindowAreMergedAndSummarizedOnChange() {
        val s = RepeatSuppressor(10_000)
        assertEquals(1, s.offer(rec(0, "a")).size)
        for (i in 1..99) assertTrue(s.offer(rec(i * 10L, "a")).isEmpty())
        val out = s.offer(rec(2000, "b"))
        assertEquals(listOf("重复 count=99", "b"), out.map { it.body })
    }

    @Test
    fun sameBodyAfterWindowIsWrittenAgain() {
        val s = RepeatSuppressor(10_000)
        s.offer(rec(0, "a"))
        s.offer(rec(5_000, "a"))
        assertEquals(listOf("重复 count=1", "a"), s.offer(rec(10_000, "a")).map { it.body })
    }

    @Test
    fun noSummaryWhenNothingRepeated() {
        val s = RepeatSuppressor(10_000)
        s.offer(rec(0, "a"))
        assertEquals(1, s.offer(rec(1, "b")).size)
    }

    @Test
    fun criticalIsNeverMergedAndFlushesPendingSummary() {
        val s = RepeatSuppressor(10_000)
        s.offer(rec(0, "a"))
        s.offer(rec(1, "a"))
        val out = s.offer(rec(2, "a", critical = true))
        assertEquals(listOf("重复 count=1", "a"), out.map { it.body })
        assertTrue(out[1].critical)
        assertEquals(1, s.offer(rec(3, "a", critical = true)).size)
    }

    @Test
    fun modulesAreIndependent() {
        val s = RepeatSuppressor(10_000)
        s.offer(rec(0, "a"))
        assertEquals(1, s.offer(rec(1, "a", LogModule.ALARM)).size)
    }

    @Test
    fun drainExpiredEmitsSummaryOnlyAfterWindowOrWhenForced() {
        val s = RepeatSuppressor(10_000)
        s.offer(rec(0, "a"))
        s.offer(rec(1, "a"))
        assertTrue(s.drainExpired(5_000, false).isEmpty())
        assertEquals("重复 count=1", s.drainExpired(10_000, false).single().body)

        s.offer(rec(20_000, "c"))
        s.offer(rec(20_001, "c"))
        assertEquals(1, s.drainExpired(20_002, true).size)
    }
}
