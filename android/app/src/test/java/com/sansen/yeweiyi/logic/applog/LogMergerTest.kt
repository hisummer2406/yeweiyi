package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter

class LogMergerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, vararg lines: String): File =
        File(tmp.root, name).apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    private fun merge(vararg groups: List<File>): List<String> {
        val out = StringWriter()
        LogMerger.merge(groups.toList(), out)
        return out.toString().trimEnd('\n').split("\n")
    }

    @Test
    fun interleavesModulesByTime() {
        val probe = file("probe-1.log",
            "2026-10-17 14:30:05.123 [PROBE][W] 温度异常 pos=3",
            "2026-10-17 14:30:07.000 [PROBE] 通讯摘要 pos=3")
        val alarm = file("alarm-1.log",
            "2026-10-17 14:30:05.124 [ALARM][E] 高温报警 pos=3")
        assertEquals(listOf(
            "2026-10-17 14:30:05.123 [PROBE][W] 温度异常 pos=3",
            "2026-10-17 14:30:05.124 [ALARM][E] 高温报警 pos=3",
            "2026-10-17 14:30:07.000 [PROBE] 通讯摘要 pos=3"
        ), merge(listOf(probe), listOf(alarm)))
    }

    @Test
    fun continuationLinesStayWithTheirRecord() {
        val probe = file("probe-1.log",
            "2026-10-17 14:30:05.000 [PROBE][W] 最近报文 pos=3 frames=",
            "\t14:30:01.000 kind=45 result=ok frame=AA",
            "\t14:30:02.000 kind=44 result=ok frame=BB",
            "2026-10-17 14:30:09.000 [PROBE] 通讯摘要 pos=3")
        val system = file("system-1.log",
            "2026-10-17 14:30:06.000 [SYSTEM][E] 崩溃 err=x",
            "\tat a.b(C.kt:1)")
        assertEquals(listOf(
            "2026-10-17 14:30:05.000 [PROBE][W] 最近报文 pos=3 frames=",
            "\t14:30:01.000 kind=45 result=ok frame=AA",
            "\t14:30:02.000 kind=44 result=ok frame=BB",
            "2026-10-17 14:30:06.000 [SYSTEM][E] 崩溃 err=x",
            "\tat a.b(C.kt:1)",
            "2026-10-17 14:30:09.000 [PROBE] 通讯摘要 pos=3"
        ), merge(listOf(probe), listOf(system)))
    }

    @Test
    fun readsFilesOfOneModuleInOrderAndKeepsTiesStable() {
        val p1 = file("probe-1.log", "2026-10-17 10:00:00.000 [PROBE] a")
        val p2 = file("probe-2.log", "2026-10-17 12:00:00.000 [PROBE] c")
        val a1 = file("alarm-1.log", "2026-10-17 11:00:00.000 [ALARM][E] b", "2026-10-17 12:00:00.000 [ALARM][E] d")
        assertEquals(listOf(
            "2026-10-17 10:00:00.000 [PROBE] a",
            "2026-10-17 11:00:00.000 [ALARM][E] b",
            "2026-10-17 12:00:00.000 [PROBE] c",
            "2026-10-17 12:00:00.000 [ALARM][E] d"
        ), merge(listOf(p1, p2), listOf(a1)))
    }

    @Test
    fun emptyInputsProduceNothing() {
        val out = StringWriter()
        LogMerger.merge(listOf(emptyList(), listOf(file("x.log"))), out)
        assertEquals("", out.toString().trim())
    }
}
