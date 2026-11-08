package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.DataUtils

/**
 * 串口 4 联动日志（ALARM 模块）：高高/低低液位时给防溢柜或 Modbus 继电器下发了什么帧、有没有发出去、有没有应答。
 * 原先这条链路只有 logcat，现场日志看不出卡在哪一步。只记录，不改下发行为。
 */
object RelayLog {

    private const val TAG = "RelayLog"

    @Volatile
    private var portClosedLogged = false

    /** AsyManager.openOne 组好帧、放进发送队列时调用。[protocol] 防溢柜/Modbus。 */
    @JvmStatic
    fun onCommand(pos: Int, protocol: String, code: Int, frame: ByteArray) = logSafely(TAG) {
        AppLog.w(LogModule.ALARM, "串口4下发", "pos", pos, "protocol", protocol, "code", code, "frame", hex(frame))
    }

    /** 判出了需要联动的报警，但按当前串口 4 协议不下发。 */
    @JvmStatic
    fun onSkipped(pos: Int, by: String, reason: String) = logSafely(TAG) {
        AppLog.w(LogModule.ALARM, "串口4未下发", "pos", pos, "by", by, "reason", reason)
    }

    /** 发出后 3 秒内是否收到应答。防溢柜协议本身没有应答帧，[replyExpected]=false 时无应答属正常。 */
    @JvmStatic
    fun onResult(pos: Int, replied: Boolean, replyExpected: Boolean) = logSafely(TAG) {
        if (replied) {
            AppLog.i(LogModule.ALARM, "串口4应答", "pos", pos)
        } else {
            AppLog.w(LogModule.ALARM, "串口4无应答", "pos", pos, "replyExpected", replyExpected)
        }
    }

    /** ObservableManager 真正往 ttyS4 写之前调用。串口没打开时只在状态切换时记一次 E，避免每秒刷屏。 */
    @JvmStatic
    fun onWrite(frame: ByteArray, portOpen: Boolean) = logSafely(TAG) {
        if (!portOpen) {
            if (!portClosedLogged) {
                portClosedLogged = true
                AppLog.e(LogModule.ALARM, "串口4未打开，帧未发出", "frame", hex(frame))
            }
            return@logSafely
        }
        if (portClosedLogged) {
            portClosedLogged = false
            AppLog.w(LogModule.ALARM, "串口4已打开")
        }
        if (AppLog.isLoggable(LogModule.ALARM, LogLevel.DEBUG)) {
            AppLog.d(LogModule.ALARM, "串口4发送", "frame", hex(frame))
        }
    }

    /** ttyS4 收到的原始字节。 */
    @JvmStatic
    fun onReceive(data: ByteArray) = logSafely(TAG) {
        if (AppLog.isLoggable(LogModule.ALARM, LogLevel.DEBUG)) {
            AppLog.d(LogModule.ALARM, "串口4接收", "data", hex(data))
        }
    }

    private fun hex(b: ByteArray) = DataUtils.bytesToHexString(b, b.size)?.trim() ?: "-"
}
