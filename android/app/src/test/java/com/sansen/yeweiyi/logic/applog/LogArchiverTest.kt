package com.sansen.yeweiyi.logic.applog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class LogArchiverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zipsDirsAndTextsAndSkipsMissingDir() {
        val logs = tmp.newFolder("logs")
        File(logs, "probe-a.log").writeText("p1\n")
        File(logs, "alarm-a.log").writeText("a1\n")
        val target = File(tmp.root, "out.zip")

        LogArchiver.zip(target,
            mapOf("logs" to logs, "crashLog" to File(tmp.root, "missing")),
            mapOf("device-info.txt" to "版本=1"))

        assertFalse(File(target.path + ".tmp").exists())
        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("device-info.txt", "logs/alarm-a.log", "logs/probe-a.log"), names)
            val info = zip.getInputStream(zip.getEntry("device-info.txt")).readBytes().toString(Charsets.UTF_8)
            assertEquals("版本=1", info)
            val probe = zip.getInputStream(zip.getEntry("logs/probe-a.log")).readBytes().toString(Charsets.UTF_8)
            assertEquals("p1\n", probe)
        }
    }

    @Test
    fun overwritesExistingTarget() {
        val target = File(tmp.root, "out.zip")
        target.writeText("old")
        LogArchiver.zip(target, emptyMap(), mapOf("a.txt" to "x"))
        ZipFile(target).use { assertTrue(it.getEntry("a.txt") != null) }
    }

    @Test
    fun generatedEntryIsStreamedIntoZip() {
        val target = File(tmp.root, "gen.zip")
        LogArchiver.zip(target, emptyMap(), mapOf("device-info.txt" to "i"),
            mapOf("all.log" to { w: java.io.Writer -> w.write("合并1\n合并2\n") }))
        ZipFile(target).use { zip ->
            val all = zip.getInputStream(zip.getEntry("all.log")).readBytes().toString(Charsets.UTF_8)
            assertEquals("合并1\n合并2\n", all)
            assertTrue(zip.getEntry("device-info.txt") != null)
        }
    }
}
