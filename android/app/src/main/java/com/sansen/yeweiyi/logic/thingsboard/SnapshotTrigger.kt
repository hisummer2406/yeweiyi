package com.sansen.yeweiyi.logic.thingsboard

import kotlin.math.abs

/**
 * 决定这一秒要不要发遥测快照：距上次满 [periodMillis] 必发；液位与上次**发出**的值差 ≥ [thresholdMm]
 * 且距上次 ≥ [minGapMillis] 也发。跟上次发出的值比，缓慢漂移累积到阈值也会发。
 * `now` 必须是单调时钟（墙钟会被校时改动）。只有发布成功才调 [onPublished]，失败时下一秒按旧基准重试。
 */
class SnapshotTrigger @JvmOverloads constructor(
    periodMillis: Long,
    private val thresholdMm: Double = DEFAULT_THRESHOLD_MM,
    private val minGapMillis: Long = DEFAULT_MIN_GAP_MILLIS,
) {
    data class TankLevel(val oilMm: Double, val waterMm: Double)

    /** 保底周期，可由共享属性 `reportIntervalSec` 运行期修改。 */
    @Volatile
    var periodMillis: Long = periodMillis
        set(value) {
            require(value > 0) { "periodMillis 必须为正数: $value" }
            field = value
        }

    private var lastPublishAt: Long? = null
    private var lastLevels: Map<Int, TankLevel> = emptyMap()

    init {
        this.periodMillis = periodMillis
    }

    @Synchronized
    fun shouldPublish(now: Long, levels: Map<Int, TankLevel>): Boolean {
        val last = lastPublishAt ?: return true
        val elapsed = now - last
        if (elapsed >= periodMillis) return true
        return elapsed >= minGapMillis && changed(levels)
    }

    @Synchronized
    fun onPublished(now: Long, levels: Map<Int, TankLevel>) {
        lastPublishAt = now
        lastLevels = HashMap(levels)
    }

    /** 不看探头连接状态：现场 errorTimes 常瞬时非 0，按连接变化触发会让部分设备几乎每 5 秒一发。 */
    private fun changed(levels: Map<Int, TankLevel>): Boolean {
        if (levels.keys != lastLevels.keys) return true
        return levels.any { (id, now) ->
            val before = lastLevels.getValue(id)
            abs(now.oilMm - before.oilMm) >= thresholdMm || abs(now.waterMm - before.waterMm) >= thresholdMm
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD_MM = 1.0
        const val DEFAULT_MIN_GAP_MILLIS = 5_000L
    }
}
