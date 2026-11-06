package com.sansen.yeweiyi.logic.applog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs

/**
 * 按 key 限频：同一 key 在间隔内只放行一次，被拦下的次数在下一次放行时返回。线程安全。
 */
class KeyedRateLimiter(private val intervalMs: Long) {

    private class Slot(var lastPassMs: Long, var skipped: Int = 0)

    private val slots = HashMap<String, Slot>()

    /** 放行时返回此前被拦下的次数（≥0）并清零；不放行返回 [DENIED]。时钟往回调视为可以放行。 */
    @Synchronized
    fun tryAcquire(key: String, nowMs: Long): Int {
        val s = slots[key]
        if (s == null) {
            slots[key] = Slot(nowMs)
            return 0
        }
        if (nowMs - s.lastPassMs >= intervalMs || nowMs < s.lastPassMs) {
            val skipped = s.skipped
            s.skipped = 0
            s.lastPassMs = nowMs
            return skipped
        }
        s.skipped++
        return DENIED
    }

    @Synchronized
    fun clear() = slots.clear()

    companion object {
        const val DENIED = -1
    }
}

/**
 * 判断一帧温度是否值得记 W：跳变 ≥ 阈值、进入/离开超阈值状态。每罐首帧只作基准（记 I）。
 * 持续超阈值期间只在进入和离开时各记一次，避免罐温真高时逐帧刷日志。线程安全。
 */
class TempAnomalyDetector(private val jumpThreshold: Double = DEFAULT_JUMP_THRESHOLD) {

    /** 一帧的判定结果。[reason] 为 null 表示不是异常。 */
    class Decision(
        val first: Boolean,
        val reason: String?,
        /** 上一次的温度；首帧为 NaN。 */
        val previous: Double,
        /** 离开超阈值状态时，期间持续超阈值的帧数；其他情况为 0。 */
        val overLimitFrames: Int
    ) {
        val isAnomaly: Boolean get() = reason != null
    }

    private class TankState(var last: Double, var overLimit: Boolean, var overLimitFrames: Int = 0)

    private val tanks = HashMap<Int, TankState>()

    /**
     * @param temp      补偿后的温度（即写入 OilCan 的值）
     * @param highLimit 该罐高温阈值，0 表示未设置，与 AlarmDetection 的判定口径一致
     */
    @Synchronized
    fun evaluate(pos: Int, temp: Double, highLimit: Double): Decision {
        val over = highLimit != 0.0 && temp > highLimit
        val s = tanks[pos]
        if (s == null) {
            tanks[pos] = TankState(temp, over, if (over) 1 else 0)
            return Decision(true, if (over) REASON_OVER_LIMIT else null, Double.NaN, 0)
        }
        val previous = s.last
        s.last = temp
        val reasons = ArrayList<String>(2)
        if (abs(temp - previous) >= jumpThreshold) reasons.add(REASON_JUMP)
        var frames = 0
        when {
            over && !s.overLimit -> {
                reasons.add(REASON_OVER_LIMIT)
                s.overLimitFrames = 1
            }
            over -> s.overLimitFrames++
            s.overLimit -> {
                reasons.add(REASON_BACK_UNDER_LIMIT)
                frames = s.overLimitFrames
                s.overLimitFrames = 0
            }
        }
        s.overLimit = over
        return Decision(false, if (reasons.isEmpty()) null else reasons.joinToString(","), previous, frames)
    }

    /** 罐配置重载后清掉基准，下一帧重新作为首帧。 */
    @Synchronized
    fun reset() = tanks.clear()

    companion object {
        const val DEFAULT_JUMP_THRESHOLD = 5.0
        const val REASON_JUMP = "jump"
        const val REASON_OVER_LIMIT = "overLimit"
        const val REASON_BACK_UNDER_LIMIT = "backUnderLimit"
    }
}

/**
 * 每罐最近 N 帧应答，只在内存里；报警/异常时写出。每帧带递增序号，[takeNew] 只取上次写出之后的新帧，
 * 几个报警挨着发生时不会把同一批帧写两遍。线程安全。
 */
class FrameRing(private val capacity: Int) {

    private class Entry(val seq: Long, val timeMs: Long, val kind: String, val frame: String?, val result: String)

    /** 一次写出的内容。[sinceMs] 是上次写出的最后一帧的时间（从没写过为 null）；[gap] 表示两次之间有帧已被挤出缓冲。 */
    class Batch(val lines: List<String>, val sinceMs: Long?, val gap: Boolean)

    private val rings = HashMap<Int, ArrayDeque<Entry>>()
    private val takenSeq = HashMap<Int, Long>()
    private val takenTimeMs = HashMap<Int, Long>()
    private var nextSeq = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * @param kind   请求类型，如 44/45/42
     * @param frame  应答原始 hex，没收到应答时为 null
     * @param result ok / bad / noResp
     */
    @Synchronized
    fun add(pos: Int, timeMs: Long, kind: String, frame: String?, result: String) {
        val ring = rings.getOrPut(pos) { ArrayDeque(capacity) }
        if (ring.size == capacity) ring.removeFirst()
        ring.addLast(Entry(nextSeq++, timeMs, kind, frame, result))
    }

    /** 从旧到新，每帧一行：`HH:mm:ss.SSS kind=45 result=ok frame=...`。不影响 [takeNew] 的进度。 */
    @Synchronized
    fun snapshot(pos: Int): List<String> = rings[pos]?.map { format(it) } ?: emptyList()

    /** 取出该罐上次 [takeNew] 之后的新帧（最多 capacity 帧），并记下这次取到哪里。 */
    @Synchronized
    fun takeNew(pos: Int): Batch {
        val mark = takenSeq[pos]
        val fresh = rings[pos]?.filter { mark == null || it.seq > mark } ?: emptyList()
        val gap = mark != null && fresh.isNotEmpty() && fresh.first().seq > mark + 1
        val since = takenTimeMs[pos]
        if (fresh.isNotEmpty()) {
            takenSeq[pos] = fresh.last().seq
            takenTimeMs[pos] = fresh.last().timeMs
        }
        return Batch(fresh.map { format(it) }, since, gap)
    }

    @Synchronized
    fun clear() {
        rings.clear()
        takenSeq.clear()
        takenTimeMs.clear()
    }

    private fun format(e: Entry) =
        "${timeFormat.format(Date(e.timeMs))} kind=${e.kind} result=${e.result} frame=${e.frame ?: "-"}"
}

/**
 * 按 key 的滑动窗口限频：窗口内最多放行 [maxEvents] 次，被拦下的次数在下一次放行时返回。线程安全。
 */
class WindowRateLimiter(private val windowMs: Long, private val maxEvents: Int) {

    private class Slot {
        val passes = ArrayDeque<Long>()
        var skipped = 0
    }

    private val slots = HashMap<String, Slot>()

    /** 放行时返回此前被拦下的次数（≥0）并清零；不放行返回 [KeyedRateLimiter.DENIED]。时钟往回调时重新计数。 */
    @Synchronized
    fun tryAcquire(key: String, nowMs: Long): Int {
        val s = slots.getOrPut(key) { Slot() }
        if (s.passes.isNotEmpty() && nowMs < s.passes.last()) s.passes.clear()
        while (s.passes.isNotEmpty() && nowMs - s.passes.first() >= windowMs) s.passes.removeFirst()
        if (s.passes.size >= maxEvents) {
            s.skipped++
            return KeyedRateLimiter.DENIED
        }
        s.passes.addLast(nowMs)
        val skipped = s.skipped
        s.skipped = 0
        return skipped
    }
}

/** 每罐通讯计数（成功 / 解析失败 / 无应答），供定时摘要使用，取一次清零一次。线程安全。 */
class ProbeCommStats {

    class Counts {
        var ok = 0
        var bad = 0
        var noResp = 0
    }

    private var counts = TreeMap<Int, Counts>()

    @Synchronized
    fun recordOk(pos: Int) {
        counts.getOrPut(pos) { Counts() }.ok++
    }

    @Synchronized
    fun recordBad(pos: Int) {
        counts.getOrPut(pos) { Counts() }.bad++
    }

    @Synchronized
    fun recordNoResponse(pos: Int) {
        counts.getOrPut(pos) { Counts() }.noResp++
    }

    /** 返回按罐位排序的本周期计数，并开始新周期。 */
    @Synchronized
    fun drain(): Map<Int, Counts> {
        val out = counts
        counts = TreeMap()
        return out
    }
}
