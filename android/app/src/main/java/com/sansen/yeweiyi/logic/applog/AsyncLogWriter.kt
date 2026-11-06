package com.sansen.yeweiyi.logic.applog

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台写线程：调用方只入有界队列，满了丢弃并计数，不等待——串口轮询线程不能被文件 IO 拖住。
 * 普通日志按批量/间隔刷盘，[LogRecord.needsSync] 的立即 fsync。
 */
class AsyncLogWriter(
    private val sink: RollingFileSink,
    private val formatter: LogFormatter,
    private val clock: LogClock,
    private val config: LogConfig,
    private val onWarning: (String, Throwable?) -> Unit,
    /** 写线程每轮循环都会调用一次（约每 [LogConfig.flushIntervalMs]），供上层做定时检查。 */
    private val onTick: () -> Unit = {}
) {

    private enum class CommandType { FLUSH, CLEAR, STOP }

    private class Command(val type: CommandType) {
        val done = CountDownLatch(1)
    }

    private val suppressor = RepeatSuppressor(config.repeatWindowMs)
    private val queue = ArrayBlockingQueue<Any>(config.queueCapacity)
    private val dropped = AtomicInteger()
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "app-log-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 不阻塞。返回 false 表示队列已满、本条被丢弃（计入下一次写入时的丢弃记录）。 */
    fun enqueue(record: LogRecord): Boolean {
        if (queue.offer(record)) return true
        dropped.incrementAndGet()
        return false
    }

    /** 等写线程把此前入队的日志全部写完并 fsync。导出前调用。 */
    fun flushBarrier(timeoutMs: Long) = runCommand(CommandType.FLUSH, timeoutMs)

    /** 在写线程上关闭并删除全部日志文件。 */
    fun clear(timeoutMs: Long) = runCommand(CommandType.CLEAR, timeoutMs)

    fun stop(timeoutMs: Long) = runCommand(CommandType.STOP, timeoutMs)

    /**
     * 崩溃时在调用线程上把队列里剩下的日志直接写盘（不经重复合并），崩溃前最后约 1 秒的上下文才不会丢。
     * 写线程可能同时在写，sink 和 formatter 都已加锁；排在队列里的命令直接放行。
     */
    fun drainSync() {
        while (true) {
            when (val item = queue.poll() ?: break) {
                is LogRecord -> writeLine(item)
                is Command -> item.done.countDown()
            }
        }
    }

    /** 绕过队列在调用线程上直接写并 fsync，只给崩溃用：进程马上退出，等不到写线程。 */
    fun writeSync(record: LogRecord) {
        sink.write(record.module, formatter.formatLine(record))
        sink.flush(true)
    }

    private fun runCommand(type: CommandType, timeoutMs: Long): Boolean {
        val cmd = Command(type)
        return try {
            queue.offer(cmd, timeoutMs, TimeUnit.MILLISECONDS) && cmd.done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun loop() {
        var lastFlushMs = clock.nowMs()
        var pending = 0
        var running = true
        while (running) {
            try {
                val item = queue.poll(config.flushIntervalMs, TimeUnit.MILLISECONDS)
                val now = clock.nowMs()
                when (item) {
                    is LogRecord -> {
                        pending += handle(item)
                        if (item.needsSync) {
                            sink.flush(true)
                            pending = 0
                            lastFlushMs = now
                        }
                    }
                    is Command -> {
                        suppressor.drainExpired(now, true).forEach { writeLine(it) }
                        if (item.type == CommandType.CLEAR) sink.clearAll() else sink.flush(true)
                        if (item.type == CommandType.STOP) {
                            sink.closeAll()
                            running = false
                        }
                        pending = 0
                        lastFlushMs = now
                        item.done.countDown()
                        continue
                    }
                }
                suppressor.drainExpired(now, false).forEach {
                    writeLine(it)
                    pending++
                }
                if (pending > 0 && (pending >= config.flushBatch || now - lastFlushMs >= config.flushIntervalMs ||
                        now < lastFlushMs)
                ) {
                    sink.flush(false)
                    pending = 0
                    lastFlushMs = now
                }
                onTick()
            } catch (e: InterruptedException) {
                running = false
            } catch (e: RuntimeException) {
                onWarning("日志写线程异常，已跳过本条", e)
            }
        }
        synchronized(this) { thread = null }
    }

    private fun handle(r: LogRecord): Int {
        var written = 0
        val lost = dropped.getAndSet(0)
        if (lost > 0) {
            writeLine(LogRecord(r.timeMs, r.module, LogLevel.WARN, LogFormatter.body(DROPPED_EVENT, "count", lost)))
            written++
        }
        for (out in suppressor.offer(r)) {
            writeLine(out)
            written++
        }
        return written
    }

    private fun writeLine(r: LogRecord) {
        sink.write(r.module, formatter.formatLine(r))
    }

    companion object {
        const val DROPPED_EVENT = "日志丢弃"
    }
}
