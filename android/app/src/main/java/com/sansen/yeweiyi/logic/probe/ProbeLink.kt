package com.sansen.yeweiyi.logic.probe

/**
 * 探针断线口径（issue #75）：按罐连续失败次数 OilCan.errorTimes 判断，成功一次即清零恢复。
 * 断线报警、代理缓存失效和遥测 probeConnected 共用此口径；遥测原用 errorTimes == 0，
 * 单次失败就把仍有效的读数报成断线。
 */
object ProbeLink {
    /** 常用探针（中成、LD 等）连续失败 5 次算断线。 */
    const val LOST_AFTER_FAILURES = 5

    /** 探针类型 20 轮询更密，连续失败 20 次才算断线。 */
    const val LOST_AFTER_FAILURES_TYPE_20 = 20

    /** 有断线报警的探针类型；其他类型没有解析器，不发断线报警。 */
    @JvmStatic
    fun raisesLostAlarm(probeType: Int): Boolean = probeType in 0..6 || probeType == 20

    @JvmStatic
    fun lostThreshold(probeType: Int): Int =
        if (probeType == 20) LOST_AFTER_FAILURES_TYPE_20 else LOST_AFTER_FAILURES

    /** 连续失败达到阈值即断线；成功一次（errorTimes 清零）即恢复。 */
    @JvmStatic
    fun isLost(probeType: Int, errorTimes: Int): Boolean = errorTimes >= lostThreshold(probeType)

    @JvmStatic
    fun isConnected(probeType: Int, errorTimes: Int): Boolean = !isLost(probeType, errorTimes)
}
