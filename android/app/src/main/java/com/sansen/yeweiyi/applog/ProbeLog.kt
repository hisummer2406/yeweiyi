package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.bean.OilCan
import com.sansen.yeweiyi.logic.applog.FrameRing
import com.sansen.yeweiyi.logic.applog.KeyedRateLimiter
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.logic.applog.ProbeCommStats
import com.sansen.yeweiyi.logic.applog.TempAnomalyDetector
import com.sansen.yeweiyi.logic.applog.WindowRateLimiter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 探头通讯日志（PROBE 模块）：收发、温度异常、首读、通讯摘要，各条记哪一级见 docs/日志体系.md §6。
 * 调用方在串口轮询线程上，这里只做级别判断、内存操作和入队；只记录，不改变解析结果。
 */
object ProbeLog {

    private const val RING_CAPACITY = 30
    private const val LIMIT_INTERVAL_MS = 10 * 60 * 1000L
    private const val SUMMARY_INTERVAL_MIN = 5L
    private const val MAX_DUMPS_PER_WINDOW = 10

    private const val RESULT_OK = "ok"
    private const val RESULT_BAD = "bad"
    private const val RESULT_NO_RESP = "noResp"
    /** 42（长度）应答解析成功也返回 false，判断不了好坏。 */
    private const val RESULT_UNCHECKED = "unchecked"
    private const val KIND_LENGTH = "42"

    private val ring = FrameRing(RING_CAPACITY)
    private val stats = ProbeCommStats()
    private val detector = TempAnomalyDetector()
    /** 每罐每 10 分钟最多写出 10 次报文：液位在阈值附近来回跳时每轮都会报警，不限的话量接近 D 级。 */
    private val dumpLimiter = WindowRateLimiter(LIMIT_INTERVAL_MS, MAX_DUMPS_PER_WINDOW)
    private val noMatchLimiter = KeyedRateLimiter(LIMIT_INTERVAL_MS)

    @Volatile
    private var started = false

    /** 启动 5 分钟通讯摘要定时器，在 MyApplication 里调用一次。 */
    @JvmStatic
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "probe-log-summary").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ logSafely(TAG) { writeSummary() } },
                SUMMARY_INTERVAL_MIN, SUMMARY_INTERVAL_MIN, TimeUnit.MINUTES)
    }

    /**
     * 每次收发后调用（[frame] 为 null 表示没收到应答）。
     * @param kind 中成协议的请求类型 44/45/42，其他探头类型传协议名
     */
    @JvmStatic
    fun onResponse(pos: Int, kind: String, frame: String?, parsedOk: Boolean) = logSafely(TAG) {
        if (!AppLog.isLoggable(LogModule.PROBE, LogLevel.WARN)) return@logSafely
        val result = when {
            frame == null -> RESULT_NO_RESP
            kind == KIND_LENGTH -> RESULT_UNCHECKED
            parsedOk -> RESULT_OK
            else -> RESULT_BAD
        }
        ring.add(pos, System.currentTimeMillis(), kind, frame, result)
        if (AppLog.isLoggable(LogModule.PROBE, LogLevel.INFO)) {
            when (result) {
                RESULT_OK -> stats.recordOk(pos)
                RESULT_BAD -> stats.recordBad(pos)
                RESULT_NO_RESP -> stats.recordNoResponse(pos)
            }
        }
        AppLog.d(LogModule.PROBE, "收发", "pos", pos, "kind", kind, "result", result, "frame", frame ?: "-")
    }

    /**
     * 中成 45 帧通过 CRC 后调用，[can] 已经写入了新温度；匹配不到罐时 [can] 为 null。
     * @param decoded 补偿前的解码温度
     */
    @JvmStatic
    fun onTemperatureFrame(address: Int, can: OilCan?, hi: Byte, lo: Byte, decoded: Double, frame: ByteArray) =
        logSafely(TAG) {
            if (!AppLog.isLoggable(LogModule.PROBE, LogLevel.WARN)) return@logSafely
            val raw = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
            val hex = frame.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
            if (can == null) {
                val skipped = noMatchLimiter.tryAcquire("addr$address", System.currentTimeMillis())
                if (skipped != KeyedRateLimiter.DENIED) {
                    AppLog.w(LogModule.PROBE, "温度异常", "reason", "noMatch", "addr", address, "raw", raw,
                        "decoded", decoded, "skipped", skipped, "frame", hex)
                }
                return@logSafely
            }
            val pos = can.oilCanPos
            val final = can.temper
            val decision = detector.evaluate(pos, final, can.highTempAlarm.toDouble())
            if (decision.first) {
                AppLog.i(LogModule.PROBE, "温度首读", "pos", pos, "addr", address, "raw", raw, "decoded", decoded,
                    "final", final)
            }
            if (!decision.isAnomaly) return@logSafely
            AppLog.w(LogModule.PROBE, "温度异常", "pos", pos, "addr", address,
                "hi", String.format("%02X", hi.toInt() and 0xFF), "lo", String.format("%02X", lo.toInt() and 0xFF),
                "raw", raw, "decoded", decoded, "corr", can.tempCorrection, "final", final, "prev", decision.previous,
                "limit", can.highTempAlarm, "reason", decision.reason, "overFrames", decision.overLimitFrames,
                "frame", hex)
            dumpRecentFrames(pos, "温度异常")
        }

    /**
     * 中成 45 帧温度被判无效、已丢弃时调用（[can] 的温度没变）。[reason] 见 ZhongChengTemperature；
     * 匹配不到罐时 [can] 为 null，不写报文。
     */
    @JvmStatic
    fun onTemperatureRejected(address: Int, can: OilCan?, hi: Byte, lo: Byte, decoded: Double, reason: String,
                              frame: ByteArray) = logSafely(TAG) {
        if (!AppLog.isLoggable(LogModule.PROBE, LogLevel.WARN)) return@logSafely
        val hex = frame.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        AppLog.w(LogModule.PROBE, "温度无效", "pos", can?.oilCanPos ?: "-", "addr", address,
            "hi", String.format("%02X", hi.toInt() and 0xFF), "lo", String.format("%02X", lo.toInt() and 0xFF),
            "decoded", decoded, "reason", reason, "kept", can?.temper ?: "-", "frame", hex)
        if (can != null) dumpRecentFrames(can.oilCanPos, "温度无效")
    }

    /**
     * 把该罐上次写出之后的新应答写一条 W 日志（最多 30 帧），温度异常和各类报警都会调用。
     * `since` 是上次写到的帧时间，和上一份接着读就是完整时间线；`count=0` 表示和上一份相同；
     * `gap=true` 表示两份之间有帧已被挤出缓冲、没能写出。
     */
    internal fun dumpRecentFrames(pos: Int, cause: String) {
        if (!AppLog.isLoggable(LogModule.PROBE, LogLevel.WARN)) return
        val skipped = dumpLimiter.tryAcquire("pos$pos", System.currentTimeMillis())
        if (skipped == KeyedRateLimiter.DENIED) return
        val batch = ring.takeNew(pos)
        AppLog.w(LogModule.PROBE, "最近报文", "pos", pos, "cause", cause, "count", batch.lines.size,
            "since", batch.sinceMs?.let { timeOf(it) } ?: "-", "gap", batch.gap, "skipped", skipped,
            "frames", batch.lines.joinToString("") { "\n\t" + it })
    }

    private fun timeOf(ms: Long) = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    private fun writeSummary() {
        val counts = stats.drain()
        if (!AppLog.isLoggable(LogModule.PROBE, LogLevel.INFO)) return
        val data = MyApplication.getInstance().getmOilcanDataManager().data
        for (i in 0 until data.size()) {
            val c = data.valueAt(i) ?: continue
            val n = counts[c.oilCanPos]
            AppLog.i(LogModule.PROBE, "通讯摘要", "pos", c.oilCanPos, "temp", c.temper, "oilLevel", c.oilLevel,
                "errorTimes", c.errorTimes, "ok", n?.ok ?: 0, "bad", n?.bad ?: 0, "noResp", n?.noResp ?: 0)
        }
    }

    private const val TAG = "ProbeLog"
}
