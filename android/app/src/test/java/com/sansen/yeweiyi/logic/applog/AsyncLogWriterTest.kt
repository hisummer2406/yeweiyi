package com.sansen.yeweiyi.logic.applog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class AsyncLogWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sink: RollingFileSink
    private lateinit var writer: AsyncLogWriter

    private fun newWriter(queueCapacity: Int): AsyncLogWriter {
        val cfg = LogConfig(minFreeBytes = 0, queueCapacity = queueCapacity, flushIntervalMs = 50)
        sink = RollingFileSink(File(tmp.root, "logs"), cfg, LogClock.SYSTEM, { Long.MAX_VALUE }, { _, _ -> })
        writer = AsyncLogWriter(sink, LogFormatter(), LogClock.SYSTEM, cfg, { _, _ -> })
        return writer
    }

    @After
    fun tearDown() {
        if (::writer.isInitialized) writer.stop(2000)
    }

    private fun readAll() = sink.listLogFiles().joinToString("") { it.readText() }

    private fun rec(body: String, critical: Boolean = false, level: LogLevel = LogLevel.INFO) =
        LogRecord(System.currentTimeMillis(), LogModule.ALARM, level, body, critical)

    @Test
    fun flushBarrierWritesEverythingQueuedBefore() {
        newWriter(100).start()
        for (i in 0 until 20) assertTrue(writer.enqueue(rec("n$i")))
        assertTrue(writer.flushBarrier(2000))
        val all = readAll()
        assertTrue(all.contains("[ALARM] n0\n"))
        assertTrue(all.contains("[ALARM] n19\n"))
    }

    @Test
    fun fullQueueDropsWithoutBlockingAndReportsCount() {
        newWriter(3)
        // 写线程还没启动：队列只能放 3 条，其余立即丢弃
        val start = System.currentTimeMillis()
        val accepted = (0 until 10).count { writer.enqueue(rec("m$it")) }
        assertTrue(System.currentTimeMillis() - start < 500)
        assertEquals(3, accepted)
        writer.start()
        assertTrue(writer.flushBarrier(2000))
        val all = readAll()
        assertTrue(all, all.contains("[ALARM][W] 日志丢弃 count=7"))
        assertTrue(all.contains("m0"))
        assertFalse(all.contains("m5"))
    }

    @Test
    fun criticalRecordReachesDiskWithoutBarrier() {
        newWriter(100).start()
        writer.enqueue(rec("高温报警 pos=1", critical = true))
        val deadline = System.currentTimeMillis() + 2000
        while (!readAll().contains("高温报警 pos=1") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(readAll().contains("高温报警 pos=1"))
    }

    @Test
    fun clearDeletesFilesAndLaterLogsStartFresh() {
        newWriter(100).start()
        writer.enqueue(rec("before"))
        assertTrue(writer.clear(2000))
        assertEquals(0, sink.listLogFiles().size)
        writer.enqueue(rec("after"))
        assertTrue(writer.flushBarrier(2000))
        val all = readAll()
        assertFalse(all.contains("before"))
        assertTrue(all.contains("after"))
    }

    @Test
    fun writeSyncGoesStraightToDisk() {
        newWriter(100)
        writer.writeSync(LogRecord(System.currentTimeMillis(), LogModule.SYSTEM, LogLevel.ERROR, "崩溃", true))
        assertTrue(readAll().contains("[SYSTEM][E] 崩溃"))
    }

    @Test
    fun concurrentProducersAllLand() {
        newWriter(10_000).start()
        val go = CountDownLatch(1)
        val threads = (0 until 4).map { id ->
            Thread {
                go.await()
                for (i in 0 until 200) writer.enqueue(rec("t$id-$i"))
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        assertTrue(writer.flushBarrier(5000))
        assertEquals(800, readAll().trim().split("\n").size)
    }

    @Test
    fun drainSyncWritesQueuedRecordsBeforeCrashRecord() {
        newWriter(100)
        // 写线程未启动，模拟"队列里还有没写的日志时崩溃"
        writer.enqueue(rec("崩溃前1"))
        writer.enqueue(rec("崩溃前2"))
        writer.drainSync()
        writer.writeSync(LogRecord(System.currentTimeMillis(), LogModule.ALARM, LogLevel.ERROR, "崩溃", true))
        val all = readAll()
        assertTrue(all, all.indexOf("崩溃前1") in 0 until all.indexOf("崩溃前2"))
        assertTrue(all.indexOf("崩溃前2") < all.indexOf("[ALARM][E] 崩溃"))
    }
}
