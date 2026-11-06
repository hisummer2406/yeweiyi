package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelPolicyTest {

    private val now = 1_000_000L

    @Test
    fun defaultIsEnabledAtWarn() {
        val s = LevelSettings()
        assertTrue(s.enabled)
        assertEquals(LogLevel.WARN, LevelPolicy.effectiveLevel(s, LogModule.PROBE))
        assertFalse(LevelPolicy.isLoggable(s, LogModule.PROBE, LogLevel.INFO))
    }

    @Test
    fun disabledMeansErrorOnly() {
        val s = LevelSettings(enabled = false)
        assertEquals(LogLevel.ERROR, LevelPolicy.effectiveLevel(s, LogModule.PROBE))
        assertFalse(LevelPolicy.isLoggable(s, LogModule.PROBE, LogLevel.WARN))
        assertTrue(LevelPolicy.isLoggable(s, LogModule.PROBE, LogLevel.ERROR))
    }

    @Test
    fun enabledDefaultsToWarn() {
        val s = LevelSettings(enabled = true)
        assertEquals(LevelPolicy.DEFAULT_ENABLED_LEVEL, s.level)
        assertTrue(LevelPolicy.isLoggable(s, LogModule.ALARM, LogLevel.WARN))
        assertFalse(LevelPolicy.isLoggable(s, LogModule.ALARM, LogLevel.INFO))
    }

    @Test
    fun moduleOverrideWins() {
        val s = LevelPolicy.apply(true, LogLevel.WARN, mapOf(LogModule.PROBE to LogLevel.DEBUG), 0, LevelSource.CLOUD, now)
        assertTrue(LevelPolicy.isLoggable(s, LogModule.PROBE, LogLevel.DEBUG))
        assertFalse(LevelPolicy.isLoggable(s, LogModule.ALARM, LogLevel.INFO))
        assertEquals(LevelSource.CLOUD, s.source)
    }

    @Test
    fun overrideIgnoredWhenDisabled() {
        val s = LevelPolicy.apply(false, LogLevel.WARN, mapOf(LogModule.PROBE to LogLevel.DEBUG), 0, LevelSource.LOCAL, now)
        assertEquals(LogLevel.ERROR, LevelPolicy.effectiveLevel(s, LogModule.PROBE))
        assertFalse(LevelPolicy.usesDebug(s))
    }

    @Test
    fun debugGetsDefault24hAndExpiresBackToWarn() {
        val s = LevelPolicy.apply(true, LogLevel.DEBUG, emptyMap(), 0, LevelSource.LOCAL, now)
        assertEquals(now + LogConfig.DAY_MS, s.debugUntilMs)
        assertFalse(LevelPolicy.isDebugExpired(s, now + LogConfig.DAY_MS - 1))
        assertTrue(LevelPolicy.isDebugExpired(s, now + LogConfig.DAY_MS))
        val expired = LevelPolicy.expireDebug(s)
        assertEquals(LogLevel.WARN, expired.level)
        assertTrue(expired.enabled)
        assertFalse(LevelPolicy.usesDebug(expired))
    }

    @Test
    fun customDebugDurationAndModuleExpiry() {
        val s = LevelPolicy.apply(true, LogLevel.INFO, mapOf(LogModule.PROBE to LogLevel.DEBUG), 60_000, LevelSource.CLOUD, now)
        assertEquals(now + 60_000, s.debugUntilMs)
        val expired = LevelPolicy.expireDebug(s)
        assertEquals(LogLevel.INFO, expired.level)
        assertEquals(LogLevel.WARN, expired.modules[LogModule.PROBE])
    }

    @Test
    fun clockMovedBackCountsAsExpired() {
        val s = LevelPolicy.apply(true, LogLevel.DEBUG, emptyMap(), 0, LevelSource.LOCAL, now)
        assertTrue(LevelPolicy.isDebugExpired(s, now - 1))
    }

    @Test
    fun nonDebugNeverExpires() {
        val s = LevelPolicy.apply(true, LogLevel.INFO, emptyMap(), 0, LevelSource.LOCAL, now)
        assertEquals(0, s.debugUntilMs)
        assertFalse(LevelPolicy.isDebugExpired(s, now + 365 * LogConfig.DAY_MS))
    }
}
