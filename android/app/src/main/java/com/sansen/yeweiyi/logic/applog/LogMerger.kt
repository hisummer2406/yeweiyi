package com.sansen.yeweiyi.logic.applog

import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.util.PriorityQueue

/**
 * 把各模块的日志按时间合并成一份（导出时的 all.log）。每个模块内部本来就按时间有序，这里做 k 路归并，
 * 边读边写，不整体读进内存。以 tab 开头的续行（堆栈、最近报文）跟着主行走，不会被拆开。
 * 设备时钟被往回调过时，模块内部本身就不单调，合并结果只保证"各模块内顺序不变"。
 */
object LogMerger {

    /** 行首时间 `yyyy-MM-dd HH:mm:ss.SSS` 的长度，字符串比较即时间比较。 */
    private const val TIME_PREFIX_LEN = 23

    /** 一路输入：同一模块的文件按时间顺序依次读。 */
    private class Source(files: List<File>, val order: Int) {
        private val pending = ArrayDeque(files)
        private var reader: BufferedReader? = null
        private var lookahead: String? = null
        var record: String? = null
            private set
        var key: String = ""
            private set

        /** 读出下一条记录（主行 + 后面的续行），没有了返回 false。 */
        fun advance(): Boolean {
            // 文件开头若是孤立的续行（文件切分恰好切在续行前），当作一条独立记录
            val head = lookahead ?: nextLine() ?: run { record = null; return false }
            lookahead = null
            val sb = StringBuilder(head)
            while (true) {
                val line = nextLine() ?: break
                if (line.startsWith("\t")) {
                    sb.append('\n').append(line)
                } else {
                    lookahead = line
                    break
                }
            }
            record = sb.toString()
            key = if (head.length >= TIME_PREFIX_LEN) head.substring(0, TIME_PREFIX_LEN) else head
            return true
        }

        private fun nextLine(): String? {
            while (true) {
                val r = reader ?: openNext() ?: return null
                val line = r.readLine()
                if (line != null) return line
                r.close()
                reader = null
            }
        }

        private fun openNext(): BufferedReader? {
            val f = pending.removeFirstOrNull() ?: return null
            return f.bufferedReader(Charsets.UTF_8).also { reader = it }
        }

        fun close() {
            reader?.close()
            reader = null
        }
    }

    /**
     * @param groups 每组是同一模块的文件，组内已按时间从旧到新排好
     */
    @JvmStatic
    fun merge(groups: List<List<File>>, out: Writer) {
        val sources = groups.filter { it.isNotEmpty() }.mapIndexed { i, files -> Source(files, i) }
        // 时间相同时按模块顺序，保证结果稳定
        val queue = PriorityQueue<Source>(maxOf(1, sources.size),
            compareBy<Source>({ it.key }, { it.order }))
        try {
            sources.forEach { if (it.advance()) queue.add(it) }
            while (queue.isNotEmpty()) {
                val s = queue.poll()!!
                out.write(s.record!!)
                out.write("\n")
                if (s.advance()) queue.add(s)
            }
            out.flush()
        } finally {
            sources.forEach { it.close() }
        }
    }
}
