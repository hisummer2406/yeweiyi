package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.logic.applog.LogModule

/**
 * 单罐开关量输出（IO2 联动）日志（ALARM 模块）：闭合/断开及原因、设置变更、IO 写入结果。
 * 闭合/断开会驱动现场设备，按重要事件记 E。只记录，不改控制行为。
 */
object PumpLog {

    private const val TAG = "PumpLog"

    @JvmStatic
    fun onSwitch(pos: Int, on: Boolean, reason: String, level: Double?) = logSafely(TAG) {
        AppLog.e(LogModule.ALARM, if (on) "IO2闭合" else "IO2断开", "pos", pos, "reason", reason, "level", level)
    }

    /** 绑定罐的低低/高高未设置或倒挂，只在有效↔无效切换时记一次。 */
    @JvmStatic
    fun onThresholdInvalid(pos: Int, llow: Int, hhigh: Int) = logSafely(TAG) {
        AppLog.e(LogModule.ALARM, "IO2联动阈值无效", "pos", pos, "llow", llow, "hhigh", hhigh)
    }

    @JvmStatic
    fun onSettings(enabled: Boolean, tank: Int) = logSafely(TAG) {
        AppLog.e(LogModule.ALARM, "IO2联动设置", "enabled", enabled, "tank", tank)
    }

    @JvmStatic
    fun onWriteFailed(on: Boolean, error: Throwable?) = logSafely(TAG) {
        if (error != null) {
            AppLog.e(LogModule.ALARM, "IO2写入失败", error, "value", if (on) 1 else 0)
        } else {
            AppLog.e(LogModule.ALARM, "IO2写入失败", "value", if (on) 1 else 0, "cause", "xzjh_server不可用")
        }
    }

    @JvmStatic
    fun onWrite(on: Boolean, ret: Int) = logSafely(TAG) {
        AppLog.i(LogModule.ALARM, "IO2写入", "value", if (on) 1 else 0, "ret", ret)
    }
}
