package com.sansen.yeweiyi.logic.applog

/** 级别是谁设置的：本地设置页，或云端下发（预留）。 */
enum class LevelSource { LOCAL, CLOUD }

/**
 * 持久化的级别设置。默认就开启、记 WARN（量很小，现场第一次出问题就有记录）；[enabled] 为 false 时只记 ERROR；
 * [modules] 按模块覆盖 [level]；任一处为 DEBUG 时，[debugUntilMs] 到期后退回 WARN。
 */
data class LevelSettings(
    val enabled: Boolean = true,
    val level: LogLevel = LogLevel.WARN,
    val modules: Map<LogModule, LogLevel> = emptyMap(),
    val debugUntilMs: Long = 0,
    val source: LevelSource = LevelSource.LOCAL,
    val setAtMs: Long = 0
)

/** 生效级别的计算规则，纯函数，便于单测。 */
object LevelPolicy {

    const val DEFAULT_DEBUG_DURATION_MS = LogConfig.DAY_MS

    /** 默认级别，也是重新打开开关后的级别。 */
    @JvmField
    val DEFAULT_ENABLED_LEVEL = LogLevel.WARN

    @JvmStatic
    fun effectiveLevel(s: LevelSettings, module: LogModule): LogLevel =
        if (!s.enabled) LogLevel.ERROR else s.modules[module] ?: s.level

    @JvmStatic
    fun isLoggable(s: LevelSettings, module: LogModule, level: LogLevel): Boolean =
        level.ordinal >= effectiveLevel(s, module).ordinal

    @JvmStatic
    fun usesDebug(s: LevelSettings): Boolean =
        s.enabled && (s.level == LogLevel.DEBUG || s.modules.values.contains(LogLevel.DEBUG))

    /** DEBUG 已到期；设备时钟被调回到设置时间之前也算到期。 */
    @JvmStatic
    fun isDebugExpired(s: LevelSettings, nowMs: Long): Boolean =
        usesDebug(s) && (nowMs >= s.debugUntilMs || nowMs < s.setAtMs)

    /** 把所有 DEBUG 退回 WARN，其他保持不变。 */
    @JvmStatic
    fun expireDebug(s: LevelSettings): LevelSettings = s.copy(
        level = downgrade(s.level),
        modules = s.modules.mapValues { downgrade(it.value) },
        debugUntilMs = 0
    )

    /**
     * 统一设置入口：后设置的生效。用到 DEBUG 时按 [debugDurationMs]（≤0 用默认 24 小时）计算到期时间。
     */
    @JvmStatic
    fun apply(
        enabled: Boolean,
        level: LogLevel,
        modules: Map<LogModule, LogLevel>,
        debugDurationMs: Long,
        source: LevelSource,
        nowMs: Long
    ): LevelSettings {
        val draft = LevelSettings(enabled, level, modules, 0, source, nowMs)
        if (!usesDebug(draft)) return draft
        val duration = if (debugDurationMs > 0) debugDurationMs else DEFAULT_DEBUG_DURATION_MS
        return draft.copy(debugUntilMs = nowMs + duration)
    }

    private fun downgrade(level: LogLevel) = if (level == LogLevel.DEBUG) LogLevel.WARN else level
}
