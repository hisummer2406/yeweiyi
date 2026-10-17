package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFormatterTest {

    private val t = 1760682605123L

    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun line(module: LogModule, level: LogLevel, body: String) =
        LogFormatter().formatLine(LogRecord(t, module, level, body))

    @Test
    fun infoLineIsTimeThenModuleThenBody() {
        val body = LogFormatter.body("温度异常", "pos", 3, "temp", 85.0)
        assertEquals("${time(t)} [PROBE] 温度异常 pos=3 temp=85.0", line(LogModule.PROBE, LogLevel.INFO, body))
    }

    @Test
    fun debugHasNoLevelMark() {
        assertEquals("${time(t)} [PROBE] x", line(LogModule.PROBE, LogLevel.DEBUG, "x"))
    }

    @Test
    fun warnAndErrorCarryMarkRightAfterModule() {
        assertEquals("${time(t)} [SYSTEM][W] a", line(LogModule.SYSTEM, LogLevel.WARN, "a"))
        assertEquals("${time(t)} [SYSTEM][E] b", line(LogModule.SYSTEM, LogLevel.ERROR, "b"))
    }

    @Test
    fun oddKeyValueCountDoesNotThrow() {
        assertEquals("ev a=1 b=?", LogFormatter.body("ev", "a", 1, "b"))
        assertEquals("ev", LogFormatter.body("ev"))
        assertEquals("ev k=null", LogFormatter.body("ev", "k", null))
    }

    @Test
    fun throwableAppendsErrAndIndentedStack() {
        val body = LogFormatter.bodyWithError("导出失败", IllegalStateException("boom"), "path", "/x")
        assertTrue(body, body.startsWith("导出失败 path=/x err=java.lang.IllegalStateException: boom\n\t"))
        assertTrue(body.contains("\n\tat "))
    }

    @Test
    fun credentialsAreMaskedCaseInsensitive() {
        val l = line(LogModule.CLOUD, LogLevel.INFO,
            LogFormatter.body("连接", "accessToken", "abc123", "PASSWORD", "p@ss", "host", "1.2.3.4"))
        assertTrue(l, l.endsWith("连接 accessToken=*** PASSWORD=*** host=1.2.3.4"))
        assertFalse(l.contains("abc123"))
        assertFalse(l.contains("p@ss"))
    }

    @Test
    fun redactorCoversSecretAndPwdAndLeavesOthers() {
        assertEquals("provisionDeviceSecret=*** pwd=*** a=1", LogRedactor.redact("provisionDeviceSecret=xyz pwd=1 a=1"))
        assertEquals("nothing here", LogRedactor.redact("nothing here"))
    }
}
