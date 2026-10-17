package com.sansen.yeweiyi.applog

/**
 * 领域日志（ProbeLog/AlarmLog 等）的统一保护：写日志出任何异常都吞掉，不能影响调用方的业务流程。
 */
internal inline fun logSafely(owner: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        android.util.Log.w(owner, "日志记录异常，已忽略", e)
    }
}
