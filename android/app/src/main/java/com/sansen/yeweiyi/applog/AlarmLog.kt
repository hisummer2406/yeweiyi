package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.bean.OilCan
import com.sansen.yeweiyi.bean.OilMessage
import com.sansen.yeweiyi.logic.applog.KeyedRateLimiter
import com.sansen.yeweiyi.logic.applog.LogModule

/**
 * 报警日志（ALARM 模块）：报警产生/恢复记 E 级，走正常的级别策略（默认就记录 E）。
 * 只记录，不改变本地报警判定、声光和云端上报。
 */
object AlarmLog {

    private const val TAG = "AlarmLog"
    private const val LIMIT_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 只给探头断线（PRO_ERR_CONN）限频：断线期间每次轮询失败都会触发一次 addAlarmState。
     * 其他类型由 AlarmDetection 的标志位控制、只在状态切换时触发，每次都要记。
     */
    private val disconnectLimiter = KeyedRateLimiter(LIMIT_INTERVAL_MS)

    /**
     * 记过「高温报警」、还没记「高温恢复」的罐。AlarmDetection 的 isTemperAlarm 表示"可否触发"而不是"正在报警"，
     * 开机和手动解警都会把它置 false，复位分支因此会在没报过警时走到——只有在这里登记过的罐才记恢复。
     */
    private val highTempActive = HashSet<Int>()

    private val PROBE_RELATED_TYPES = setOf(
        OilMessage.HEIGHT_OIL_ALARM, OilMessage.HHEIGHT_OIL_ALARM, OilMessage.LOW_OIL_ALARM,
        OilMessage.LLOW_OIL_ALARM, OilMessage.HEIGHT_WATER, OilMessage.PRO_ERR_CONN
    )

    private val typeNames = mapOf(
        OilMessage.HEIGHT_OIL_ALARM to "HEIGHT_OIL_ALARM",
        OilMessage.HHEIGHT_OIL_ALARM to "HHEIGHT_OIL_ALARM",
        OilMessage.LOW_OIL_ALARM to "LOW_OIL_ALARM",
        OilMessage.LLOW_OIL_ALARM to "LLOW_OIL_ALARM",
        OilMessage.HEIGHT_WATER to "HEIGHT_WATER",
        OilMessage.HEIGHT_TEMPER to "HEIGHT_TEMPER",
        OilMessage.LOW_TEMPER to "LOW_TEMPER",
        OilMessage.PRO_ERR_CONN to "PRO_ERR_CONN",
        OilMessage.NET_ERR_CONN to "NET_ERR_CONN",
        OilMessage.RELAY_DISCONNECT to "RELAY_DISCONNECT"
    )

    /** 所有报警产生时调用（AlarmManager.addAlarmState）。高温另由 [onHighTempRaised] 记详细内容，这里跳过。 */
    @JvmStatic
    fun onAlarmRaised(alarm: OilMessage?) = logSafely(TAG) {
        if (alarm == null || alarm.type == OilMessage.HEIGHT_TEMPER) return@logSafely
        var skipped = 0
        if (alarm.type == OilMessage.PRO_ERR_CONN) {
            skipped = disconnectLimiter.tryAcquire("pos${alarm.oilCanId}", System.currentTimeMillis())
            if (skipped == KeyedRateLimiter.DENIED) return@logSafely
        }
        val typeName = typeNames[alarm.type] ?: alarm.type.toString()
        AppLog.e(LogModule.ALARM, "报警产生", "pos", alarm.oilCanId,
            "type", typeName, "value", alarm.value, "skipped", skipped)
        // 液位类报警和探头断线也附带该罐最近报文；网络、继电器这类和探针报文无关的不附带
        if (alarm.type in PROBE_RELATED_TYPES) ProbeLog.dumpRecentFrames(alarm.oilCanId, "报警产生:$typeName")
    }

    /** AlarmDetection.tempHeightDection：只在进入高温报警时调用一次。 */
    @JvmStatic
    fun onHighTempRaised(can: OilCan?) = logSafely(TAG) {
        if (can == null) return@logSafely
        synchronized(highTempActive) { highTempActive.add(can.oilCanPos) }
        AppLog.e(LogModule.ALARM, "高温报警", "pos", can.oilCanPos, "addr", can.probeAdr,
            "temp", can.temper, "threshold", can.highTempAlarm, "corr", can.tempCorrection)
        ProbeLog.dumpRecentFrames(can.oilCanPos, "高温报警")
    }

    /** AlarmDetection 高温恢复分支调用；只有之前记过「高温报警」的罐才记。和产生成对记 E，便于看持续时长。 */
    @JvmStatic
    fun onHighTempRecovered(can: OilCan?) = logSafely(TAG) {
        if (can == null) return@logSafely
        val wasActive = synchronized(highTempActive) { highTempActive.remove(can.oilCanPos) }
        if (!wasActive) return@logSafely
        AppLog.e(LogModule.ALARM, "高温恢复", "pos", can.oilCanPos, "temp", can.temper,
            "threshold", can.highTempAlarm)
    }

    /** 超高温阈值但没持续满确认时长就回落（HighTempConfirmer.DISMISSED），没有报警。 */
    @JvmStatic
    fun onHighTempDismissed(can: OilCan?, overMs: Long) = logSafely(TAG) {
        if (can == null) return@logSafely
        AppLog.w(LogModule.ALARM, "高温未确认", "pos", can.oilCanPos, "overMs", overMs,
            "tempNow", can.temper, "threshold", can.highTempAlarm)
        ProbeLog.dumpRecentFrames(can.oilCanPos, "高温未确认")
    }

    /**
     * 主界面"解警"按钮（AlarmCoordinator.btn_jieJing）：在复位各罐标志之前调用，记下当时有哪些罐在报警。
     * 解警只消声光、不代表报警条件消失，所以单独记一条，不当作恢复。
     */
    @JvmStatic
    fun onManualSilence() = logSafely(TAG) {
        val data = MyApplication.getInstance().getmOilcanDataManager().data
        val alarming = ArrayList<String>()
        for (i in 0 until data.size()) {
            val c = data.valueAt(i) ?: continue
            if (c.alarmState != 0) alarming.add("${c.oilCanPos}(state=${c.alarmState})")
        }
        AppLog.e(LogModule.ALARM, "手动解警", "tanks", if (alarming.isEmpty()) "-" else alarming.joinToString(","))
    }
}
