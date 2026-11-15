package com.sansen.yeweiyi.applog

import android.os.SystemClock
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.DataUtils

/** 异常记 W、启动模式记 I；逐帧内容仅在 PROBE 日志级别为 D 时输出，与构建类型无关。 */
object ProbeProxyLog {
    private val last = HashMap<String, Long>()

    /** 同一原因每分钟最多一条；reason 使用固定类别，避免噪声报文撑大限频表。 */
    @JvmStatic @Synchronized fun notice(reason: String) = logSafely("ProbeProxyLog") {
        val now = SystemClock.elapsedRealtime()
        val before = last[reason]
        if (before != null && now - before < 60_000) return@logSafely
        last[reason] = now
        AppLog.w(LogModule.PROBE, "探针代理", "reason", reason)
    }

    /** 记录实际启动模式，帮助区分已保存配置和尚未重启生效的配置。 */
    @JvmStatic fun startup(mode: Int, protocol: Int) = logSafely("ProbeProxyLog") {
        AppLog.i(LogModule.PROBE, "探针代理模式", "serial4", mode, "serial2", protocol)
    }

    /** 先判断是否开启 D 级详细日志，再格式化原始帧，避免默认 W 级轮询产生额外开销。 */
    @JvmStatic fun frame(direction: String, bytes: ByteArray) = logSafely("ProbeProxyLog") {
        if (AppLog.isLoggable(LogModule.PROBE, LogLevel.DEBUG)) {
            AppLog.d(LogModule.PROBE, "探针代理报文", "direction", direction,
                "frame", DataUtils.bytesToHexString(bytes, bytes.size))
        }
    }
}
