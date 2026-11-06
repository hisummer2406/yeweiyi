package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.logic.applog.LogModule

/**
 * 声光日志（ALARM 模块，W 级）：记录每次开铃/关铃是哪个罐、哪类报警触发的。
 * 铃是所有罐共用的一个开关，关铃时同时记下"其他仍在报警的罐"，用来排查"罐已变红却不响铃"。只记录，不改声光行为。
 */
object SoundLightLog {

    private const val TAG = "SoundLightLog"

    /** AlarmDetection 调 openHeightAlarm/openLowOilAlarm 之前调用。[by] 如 高液位/低液位/高水位/高温。 */
    @JvmStatic
    fun onOpen(pos: Int, by: String) = logSafely(TAG) {
        AppLog.w(LogModule.ALARM, "声光开启", "pos", pos, "by", by)
    }

    /**
     * AlarmDetection 调 closeAlarm 之前调用（此时本罐的 alarmState 还没清零，所以排除本罐）。
     * closeAlarm 会无条件关掉整个铃，`stillAlarming` 不为空说明别的罐的铃也被一起关了。
     */
    @JvmStatic
    fun onClose(pos: Int, branch: String) = logSafely(TAG) {
        val data = MyApplication.getInstance().getmOilcanDataManager().data
        val others = ArrayList<String>()
        for (i in 0 until data.size()) {
            val c = data.valueAt(i) ?: continue
            if (c.oilCanPos != pos && c.alarmState != 0) others.add("${c.oilCanPos}(state=${c.alarmState})")
        }
        AppLog.w(LogModule.ALARM, "声光关闭", "pos", pos, "branch", branch,
            "stillAlarming", if (others.isEmpty()) "-" else others.joinToString(","))
    }
}
