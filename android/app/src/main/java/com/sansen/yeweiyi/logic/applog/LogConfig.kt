package com.sansen.yeweiyi.logic.applog

/**
 * 日志体系的容量与节奏参数，取值依据见 openspec/changes/add-app-log-system/design.md 决策 2。
 * 生产用默认值；单测传小数值。
 */
data class LogConfig(
    val fileMaxBytes: Long = 2 * MB,
    val totalMaxBytes: Long = 200 * MB,
    val retentionMs: Long = 30 * DAY_MS,
    val minFreeBytes: Long = 200 * MB,
    val queueCapacity: Int = 2000,
    val flushIntervalMs: Long = 1000,
    val flushBatch: Int = 64,
    val repeatWindowMs: Long = 10_000,
    /** 剩余空间检查结果的缓存时长，避免每条日志都查一次文件系统。 */
    val freeSpaceCheckIntervalMs: Long = 60_000,
    /** 保留期清理的执行间隔。 */
    val maintenanceIntervalMs: Long = 60 * 60 * 1000L,
    val moduleMaxBytes: Map<LogModule, Long> = emptyMap()
) {
    fun maxBytesOf(module: LogModule): Long = moduleMaxBytes[module] ?: module.defaultMaxBytes

    companion object {
        const val MB = 1024L * 1024L
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
