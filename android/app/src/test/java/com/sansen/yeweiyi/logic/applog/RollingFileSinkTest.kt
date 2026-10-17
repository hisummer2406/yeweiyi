package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingFileSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1760682605000L
    private var free = Long.MAX_VALUE
    private val warnings = ArrayList<String>()

    @Before
    fun setUp() {
        dir = File(tmp.root, "logs")
    }

    /** 文件 100B、PROBE 上限 300B、总上限 500B、保留 1 天、空间下限 1000B，每次都检查空间和保留期。 */
    private fun sink() = RollingFileSink(
        dir,
        LogConfig(
            fileMaxBytes = 100, totalMaxBytes = 500, retentionMs = LogConfig.DAY_MS, minFreeBytes = 1000,
            freeSpaceCheckIntervalMs = 0, maintenanceIntervalMs = 0,
            moduleMaxBytes = mapOf(LogModule.PROBE to 300L)
        ),
        LogClock { now },
        { free },
        { msg, _ -> warnings.add(msg) }
    )

    private fun line(n: Int) = "x".repeat(n - 1)

    private fun filesOf(s: RollingFileSink, prefix: String) = s.listLogFiles().filter { it.name.startsWith("$prefix-") }

    @Test
    fun modulesGoToSeparateFilesWithExpectedName() {
        val s = sink()
        assertTrue(s.write(LogModule.PROBE, "p"))
        assertTrue(s.write(LogModule.ALARM, "a"))
        s.flush(true)
        val probe = filesOf(s, "probe").single()
        assertTrue(probe.name, probe.name.matches(Regex("probe-\\d{8}-\\d{6}-01\\.log")))
        assertEquals("p\n", probe.readText())
        assertEquals(1, filesOf(s, "alarm").size)
    }

    @Test
    fun rotatesWhenFileFull() {
        val s = sink()
        s.write(LogModule.SYSTEM, line(60))
        s.write(LogModule.SYSTEM, line(60))
        s.flush(false)
        assertEquals(2, filesOf(s, "system").size)
    }

    @Test
    fun moduleCapDeletesOldestOfThatModuleOnly() {
        val s = sink()
        s.write(LogModule.ALARM, "keep")
        repeat(6) {
            now += 1000
            s.write(LogModule.PROBE, line(90))
        }
        s.flush(false)
        val probeTotal = filesOf(s, "probe").sumOf { it.length() }
        assertTrue("PROBE 总量应在上限内: $probeTotal", probeTotal <= 300)
        assertEquals(1, filesOf(s, "alarm").size)
    }

    @Test
    fun totalCapDeletesGloballyOldest() {
        val s = sink()
        val modules = listOf(LogModule.ALARM, LogModule.CLOUD, LogModule.STOCK, LogModule.SYSTEM)
        repeat(3) {
            for (m in modules) {
                now += 1000
                s.write(m, line(90))
            }
        }
        s.flush(false)
        assertTrue("总量应在上限内: ${s.totalBytes()}", s.totalBytes() <= 500)
    }

    @Test
    fun retentionDeletesOldFiles() {
        dir.mkdirs()
        val old = File(dir, "alarm-20200101-000000-01.log")
        old.writeText("old\n")
        assertTrue(old.setLastModified(now - 2 * LogConfig.DAY_MS))
        sink().write(LogModule.ALARM, "new")
        assertFalse(old.exists())
    }

    @Test
    fun appendsToLatestUnfilledFileOnFirstOpen() {
        val s1 = sink()
        s1.write(LogModule.ALARM, "a")
        s1.closeAll()
        now += 5000
        val s2 = sink()
        s2.write(LogModule.ALARM, "b")
        s2.flush(false)
        assertEquals("a\nb\n", filesOf(s2, "alarm").single().readText())
    }

    @Test
    fun pausesWhenLowOnSpaceAndResumes() {
        val s = sink()
        free = 10
        assertFalse(s.write(LogModule.ALARM, "a"))
        assertFalse(s.write(LogModule.ALARM, "b"))
        assertEquals(1, warnings.size)
        free = Long.MAX_VALUE
        assertTrue(s.write(LogModule.ALARM, "c"))
        assertEquals(2, warnings.size)
    }

    @Test
    fun unwritableDirDoesNotThrowAndWarnsOnce() {
        dir = tmp.newFile("not-a-dir")
        val s = sink()
        assertFalse(s.write(LogModule.ALARM, "a"))
        assertFalse(s.write(LogModule.ALARM, "b"))
        assertEquals(1, warnings.size)
    }

    @Test
    fun clearAllDeletesEverythingAndStartsFresh() {
        val s = sink()
        s.write(LogModule.ALARM, "a")
        s.write(LogModule.PROBE, "p")
        s.clearAll()
        assertEquals(0, s.listLogFiles().size)
        s.write(LogModule.SYSTEM, "after")
        s.flush(false)
        assertEquals(1, s.listLogFiles().size)
    }
}
