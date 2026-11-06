package com.sansen.yeweiyi.logic.applog

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale

/**
 * 按模块落盘：文件名 `<模块>-yyyyMMdd-HHmmss-NN.log`，按大小切分，按模块/总量封顶，按保留期清理，
 * 剩余空间不足时暂停写入。IO 失败只回调告警、不抛给调用方。所有公开方法加锁。
 */
class RollingFileSink(
    val dir: File,
    private val config: LogConfig,
    private val clock: LogClock,
    private val freeSpace: (File) -> Long,
    private val onWarning: (String, Throwable?) -> Unit
) {

    private class OpenFile(val file: File, val fos: FileOutputStream, var size: Long) {
        val out = BufferedOutputStream(fos, 8 * 1024)
    }

    private val open = EnumMap<LogModule, OpenFile>(LogModule::class.java)
    /** 本进程内已为该模块打开过文件：首次打开时续写最新的未满文件，之后切分一律新建。 */
    private val openedOnce = HashSet<LogModule>()
    private val nameTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private var lastFreeCheckMs: Long? = null
    private var lowSpace = false
    private var lastMaintenanceMs: Long? = null
    private var writeFailing = false

    /** 写一行（自动补换行）。返回 false 表示这一行没写进去（空间不足或 IO 失败）。 */
    @Synchronized
    fun write(module: LogModule, line: String): Boolean {
        val now = clock.nowMs()
        maybeMaintain(now)
        if (isLowSpace(now)) return false
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var f: OpenFile? = null
        return try {
            f = ensureOpen(module, bytes.size, now)
            f.out.write(bytes)
            f.size += bytes.size
            writeFailing = false
            true
        } catch (e: IOException) {
            // 只在第一次失败时告警，持续失败（如目录不可写）不刷屏
            if (!writeFailing) onWarning("写日志失败 module=$module file=${f?.file?.name ?: "?"}", e)
            writeFailing = true
            closeQuietly(module)
            false
        }
    }

    /** [sync] 为 true 时还会 fsync，确保断电后数据还在。 */
    @Synchronized
    fun flush(sync: Boolean) {
        for (f in open.values) {
            try {
                f.out.flush()
                if (sync) f.fos.fd.sync()
            } catch (e: IOException) {
                onWarning("刷新日志失败 file=${f.file.name}", e)
            }
        }
    }

    @Synchronized
    fun closeAll() {
        LogModule.values().forEach { closeQuietly(it) }
    }

    /** 关闭并删除全部日志文件；之后的写入从新文件开始。 */
    @Synchronized
    fun clearAll() {
        closeAll()
        listLogFiles().forEach { deleteQuietly(it) }
        openedOnce.addAll(LogModule.values())
    }

    @Synchronized
    fun totalBytes(): Long = listLogFiles().sumOf { sizeOf(it) }

    /** 按模块分组的日志文件，组内按时间从旧到新，供导出合并 all.log 用。 */
    @Synchronized
    fun filesByModule(): List<List<File>> = LogModule.values().map { filesOf(it) }

    /** 目录下所有符合命名规则的日志文件。 */
    @Synchronized
    fun listLogFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile && NAME.matches(it.name) } ?: emptyList()

    private fun ensureOpen(module: LogModule, incoming: Int, now: Long): OpenFile {
        open[module]?.let { f ->
            if (f.size == 0L || f.size + incoming <= config.fileMaxBytes) return f
            closeQuietly(module)
        }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("日志目录创建失败: ${dir.absolutePath}")
        var target: File? = null
        if (openedOnce.add(module)) {
            val latest = filesOf(module).lastOrNull()
            if (latest != null && latest.length() + incoming <= config.fileMaxBytes) target = latest
        }
        val fresh = target == null
        val file = target ?: newFile(module, now)
        val f = OpenFile(file, FileOutputStream(file, true), file.length())
        open[module] = f
        if (fresh) enforceCaps(module)
        return f
    }

    private fun newFile(module: LogModule, now: Long): File {
        val stamp = nameTime.format(Date(now))
        var i = 1
        while (true) {
            val f = File(dir, "${module.fileTag}-$stamp-${i.toString().padStart(2, '0')}.log")
            if (!f.exists()) return f
            i++
        }
    }

    /** 该模块的文件，按文件名里的时间从旧到新排。 */
    private fun filesOf(module: LogModule): List<File> =
        listLogFiles().filter { NAME.matchEntire(it.name)?.groupValues?.get(1) == module.fileTag }
            .sortedBy { sortKey(it) }

    private fun isOpenFile(f: File) = open.values.any { it.file == f }

    /** 打开中的文件有一部分还在缓冲区里，磁盘长度偏小，用记账的大小。 */
    private fun sizeOf(f: File): Long = open.values.firstOrNull { it.file == f }?.size ?: f.length()

    /**
     * 删最旧的文件直到满足上限。[fresh] 是刚新建文件的模块：给新文件预留一个文件的上限，
     * 否则它写满之后总量会超出上限。
     */
    private fun enforceCaps(fresh: LogModule?) {
        for (m in LogModule.values()) {
            val files = filesOf(m)
            var total = files.sumOf { sizeOf(it) } + if (m == fresh) config.fileMaxBytes else 0L
            for (f in files) {
                if (total <= config.maxBytesOf(m)) break
                if (!isOpenFile(f)) {
                    total -= sizeOf(f)
                    deleteQuietly(f)
                }
            }
        }
        val all = listLogFiles().sortedBy { sortKey(it) }
        var total = all.sumOf { sizeOf(it) } + if (fresh != null) config.fileMaxBytes else 0L
        for (f in all) {
            if (total <= config.totalMaxBytes) break
            if (!isOpenFile(f)) {
                total -= sizeOf(f)
                deleteQuietly(f)
            }
        }
    }

    private fun maybeMaintain(now: Long) {
        val last = lastMaintenanceMs
        if (last != null && now >= last && now - last < config.maintenanceIntervalMs) return
        lastMaintenanceMs = now
        for (f in listLogFiles()) {
            val modified = f.lastModified()
            if (modified in 1..now && now - modified > config.retentionMs && !isOpenFile(f)) deleteQuietly(f)
        }
        enforceCaps(null)
    }

    private fun isLowSpace(now: Long): Boolean {
        val last = lastFreeCheckMs
        if (last != null && now >= last && now - last < config.freeSpaceCheckIntervalMs) return lowSpace
        lastFreeCheckMs = now
        val free = freeSpace(if (dir.exists()) dir else dir.parentFile ?: dir)
        val low = free >= 0 && free < config.minFreeBytes
        if (low != lowSpace) {
            onWarning(if (low) "剩余空间不足，暂停写日志 free=$free" else "剩余空间恢复，继续写日志 free=$free", null)
        }
        lowSpace = low
        return low
    }

    private fun deleteQuietly(f: File) {
        if (!f.delete() && f.exists()) onWarning("删除日志文件失败 file=${f.name}", null)
    }

    private fun closeQuietly(module: LogModule) {
        val f = open.remove(module) ?: return
        try {
            f.out.close()
        } catch (e: IOException) {
            onWarning("关闭日志文件失败 file=${f.file.name}", e)
        }
    }

    private companion object {
        val NAME = Regex("^([a-z]+)-(\\d{8}-\\d{6})-(\\d{2,})\\.log$")

        /** 时间戳 + 序号（补零到 6 位），跨模块比较新旧用。 */
        fun sortKey(f: File): String {
            val m = NAME.matchEntire(f.name) ?: return f.name
            return m.groupValues[2] + "-" + m.groupValues[3].padStart(6, '0')
        }
    }
}
