package com.sansen.yeweiyi.logic.alarm

/**
 * 温度要连续越过阈值 [confirmMs] 才确认（高温、低温各用一个实例），期间任何一次不越过就重新计时。按读数时刻计时：
 * 报警检测随液位帧反复运行，同一个温度值被检测多次不能让计时前进。线程安全。
 */
class HighTempConfirmer(private val confirmMs: Long = CONFIRM_MS) {

    enum class State {
        /** 未超阈值，且之前也没有在计时 */
        NORMAL,
        /** 超阈值，还没满 [confirmMs] */
        PENDING,
        /** 超阈值已满 [confirmMs] */
        CONFIRMED,
        /** 回落到阈值以下，这一段超阈值没满 [confirmMs]，没报警 */
        DISMISSED
    }

    /** [overMs]：PENDING/CONFIRMED 为已持续时长，DISMISSED 为这段超阈值的时长，NORMAL 为 0。 */
    class Result(val state: State, val overMs: Long)

    private val overSince = HashMap<Int, Long>()
    private val confirmed = HashSet<Int>()

    /**
     * @param readAtMs 当前温度值读到的时刻（OilCan.temperReadAt，单调时钟）；两次真实读数间隔超过
     *   [confirmMs] 时，第二次仍超阈值就直接确认（已与用户确认可接受）
     */
    @Synchronized
    fun update(pos: Int, over: Boolean, readAtMs: Long): Result {
        if (over) {
            val elapsed = readAtMs - overSince.getOrPut(pos) { readAtMs }
            if (elapsed >= confirmMs) {
                confirmed.add(pos)
                return Result(State.CONFIRMED, elapsed)
            }
            return Result(State.PENDING, elapsed)
        }
        val since = overSince.remove(pos) ?: return Result(State.NORMAL, 0)
        if (confirmed.remove(pos)) return Result(State.NORMAL, 0)
        return Result(State.DISMISSED, readAtMs - since)
    }

    companion object {
        /** 2026-10-20 与领导确认取 5 秒 */
        const val CONFIRM_MS = 5_000L
    }
}
