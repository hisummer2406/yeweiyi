package com.sansen.yeweiyi.logic.applog

import java.util.Locale

/** 日志模块：每个模块单独成文件、单独封顶。新功能要记日志时在这里加一个值。 */
enum class LogModule(val defaultMaxBytes: Long) {
    PROBE(80 * LogConfig.MB),
    ALARM(30 * LogConfig.MB),
    CLOUD(30 * LogConfig.MB),
    STOCK(30 * LogConfig.MB),
    SYSTEM(30 * LogConfig.MB);

    /** 文件名前缀，如 `probe`。 */
    val fileTag: String get() = name.lowercase(Locale.US)
}

/** 级别从低到高；只记录不低于生效级别的日志（见 [LevelPolicy]），各级别记什么见 docs/日志体系.md。 */
enum class LogLevel(val mark: String) {
    DEBUG(""),
    INFO(""),
    WARN("[W]"),
    ERROR("[E]")
}

/** 时钟注入点，单测里换成可控时钟。 */
fun interface LogClock {
    fun nowMs(): Long

    companion object {
        @JvmField
        val SYSTEM = LogClock { System.currentTimeMillis() }
    }
}

/**
 * 一条待写入的日志。[body] 是 `[模块]` 之后的全部内容，时间和模块名由 [LogFormatter] 补。
 * [critical] 为关键事件：写入后立即 fsync，且不参与重复合并。
 */
data class LogRecord(
    val timeMs: Long,
    val module: LogModule,
    val level: LogLevel,
    val body: String,
    val critical: Boolean = false
) {
    /** ERROR 与关键事件都要求立即落盘。 */
    val needsSync: Boolean get() = critical || level == LogLevel.ERROR
}
