package com.sansen.yeweiyi.applog

import android.os.SystemClock
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.DataUtils

/** 代理失败按原因限频，逐帧内容仅在 DEBUG 输出。 */
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

    /** 先判断 DEBUG 再格式化原始帧，正常轮询无需生成十六进制字符串。 */
    @JvmStatic fun frame(direction: String, bytes: ByteArray) = logSafely("ProbeProxyLog") {
        if (AppLog.isLoggable(LogModule.PROBE, LogLevel.DEBUG)) {
            AppLog.d(LogModule.PROBE, "探针代理报文", "direction", direction,
                "frame", DataUtils.bytesToHexString(bytes, bytes.size))
        }
    }
}
