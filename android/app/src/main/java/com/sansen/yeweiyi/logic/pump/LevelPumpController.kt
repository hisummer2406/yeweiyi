package com.sansen.yeweiyi.logic.pump

/**
 * 单罐开关量输出（IO2）的回差判断：液位 < 低低时闭合，闭合后一直保持到液位 > 高高才断开。
 * 比较方式与 AlarmDetection 的低低/高高报警一致。初始为断开，开机时液位在两者之间不闭合。
 * 纯逻辑，不碰硬件；外层只在 [Change] 非空时写 IO。
 */
class LevelPumpController(private val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS) {

    enum class Reason { LOW_LOW, HIGH_HIGH, INVALID_THRESHOLD, PROBE_LOST, STALE, RESET }

    /** 一次状态切换：[on] 为切换后的状态。 */
    data class Change(val on: Boolean, val reason: Reason)

    var isOn = false
        private set

    private var lastValidAtMs = 0L

    /** 一条有效读数（通讯正常的帧）。阈值无效时强制断开。 */
    fun onReading(level: Double, llow: Int, hhigh: Int, nowMs: Long): Change? {
        lastValidAtMs = nowMs
        if (!thresholdsValid(llow, hhigh)) return turnOff(Reason.INVALID_THRESHOLD)
        return when {
            !isOn && level < llow -> turnOn()
            isOn && level > hhigh -> turnOff(Reason.HIGH_HIGH)
            else -> null
        }
    }

    fun onProbeLost(): Change? = turnOff(Reason.PROBE_LOST)

    /** 闭合状态下超过 [staleTimeoutMs] 没有有效读数则断开（采集停了，不能盲目一直闭合）。 */
    fun checkStale(nowMs: Long): Change? =
        if (isOn && nowMs - lastValidAtMs > staleTimeoutMs) turnOff(Reason.STALE) else null

    /** 开关关闭 / 换绑定罐：断开并回到初始状态。 */
    fun reset(): Change? {
        lastValidAtMs = 0L
        return turnOff(Reason.RESET)
    }

    private fun turnOn(): Change {
        isOn = true
        return Change(true, Reason.LOW_LOW)
    }

    private fun turnOff(reason: Reason): Change? {
        if (!isOn) return null
        isOn = false
        return Change(false, reason)
    }

    companion object {
        const val DEFAULT_STALE_TIMEOUT_MS = 60_000L

        /** 低低、高高都要设置（非 0），且低低 < 高高，否则回差无意义。 */
        @JvmStatic
        fun thresholdsValid(llow: Int, hhigh: Int): Boolean = llow > 0 && hhigh > 0 && llow < hhigh
    }
}
