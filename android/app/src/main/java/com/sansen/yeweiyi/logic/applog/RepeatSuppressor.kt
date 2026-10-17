package com.sansen.yeweiyi.logic.applog

import java.util.EnumMap

/**
 * 同一模块、同一内容在窗口内连续出现时只写第一条，窗口结束或内容变化时补一条 `重复 count=N`。
 * 关键事件不参与合并。非线程安全，只在写线程上使用。
 */
class RepeatSuppressor(private val windowMs: Long) {

    private class State(var first: LogRecord, var lastTimeMs: Long, var repeats: Int = 0)

    private val states = EnumMap<LogModule, State>(LogModule::class.java)

    /** 返回此刻应写出的记录：可能为空，也可能是"汇总 + 本条"两条。 */
    fun offer(r: LogRecord): List<LogRecord> {
        val s = states[r.module]
        if (r.critical) {
            val out = ArrayList<LogRecord>(2)
            if (s != null) {
                summaryOf(s)?.let { out.add(it) }
                states.remove(r.module)
            }
            out.add(r)
            return out
        }
        if (s != null && s.first.level == r.level && s.first.body == r.body &&
            r.timeMs >= s.first.timeMs && r.timeMs - s.first.timeMs < windowMs
        ) {
            s.repeats++
            s.lastTimeMs = r.timeMs
            return emptyList()
        }
        val out = ArrayList<LogRecord>(2)
        s?.let { summaryOf(it)?.let { sum -> out.add(sum) } }
        states[r.module] = State(r, r.timeMs)
        out.add(r)
        return out
    }

    /** 窗口已过的模块补写汇总；写线程空闲时定期调用，导出/清空前强制调用。 */
    fun drainExpired(nowMs: Long, force: Boolean): List<LogRecord> {
        val out = ArrayList<LogRecord>()
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (force || nowMs - s.first.timeMs >= windowMs || nowMs < s.first.timeMs) {
                summaryOf(s)?.let { out.add(it) }
                it.remove()
            }
        }
        return out
    }

    private fun summaryOf(s: State): LogRecord? =
        if (s.repeats == 0) null
        else LogRecord(s.lastTimeMs, s.first.module, s.first.level, LogFormatter.body(REPEAT_EVENT, "count", s.repeats))

    companion object {
        const val REPEAT_EVENT = "重复"
    }
}
