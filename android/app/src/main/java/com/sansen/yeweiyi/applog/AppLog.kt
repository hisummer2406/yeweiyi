package com.sansen.yeweiyi.applog

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sansen.yeweiyi.BuildConfig
import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.logic.applog.AsyncLogWriter
import com.sansen.yeweiyi.logic.applog.LevelPolicy
import com.sansen.yeweiyi.logic.applog.LevelSettings
import com.sansen.yeweiyi.logic.applog.LevelSource
import com.sansen.yeweiyi.logic.applog.LogArchiver
import com.sansen.yeweiyi.logic.applog.LogClock
import com.sansen.yeweiyi.logic.applog.LogConfig
import com.sansen.yeweiyi.logic.applog.LogFormatter
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogMerger
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.logic.applog.LogRecord
import com.sansen.yeweiyi.logic.applog.RollingFileSink
import com.sansen.yeweiyi.utils.SharePrefUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 应用日志门面：`AppLog.w(LogModule.PROBE, "温度异常", "pos", 3, ...)`。级别约定、新增日志的做法见 docs/日志体系.md。
 * 低于生效级别的调用在调用方线程上直接返回；落盘在后台写线程上做。初始化之前的调用只进 logcat。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val PREFS = "app_log_settings"
    private const val BARRIER_TIMEOUT_MS = 5_000L
    private const val EXPORT_PREFIX = "yeweiyi-log-"

    /** 最近一次导出删掉的旧 zip 个数，只在 app-log-io 线程上读写。 */
    private var removedOld = 0

    private val config = LogConfig()
    private val formatter = LogFormatter()
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log-io").apply { isDaemon = true } }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var settings = LevelSettings()
    @Volatile
    private var writer: AsyncLogWriter? = null
    private var sink: RollingFileSink? = null
    private var appContext: Context? = null

    /** 导出/清空结果，在主线程回调。导出成功时 [file] 是 zip 路径，失败时 [error] 非空。 */
    fun interface ResultCallback {
        fun onResult(file: File?, error: Throwable?)
    }

    /** 在 MyApplication.onCreate() 最前面调用，重复调用无效果。 */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val ctx = context.applicationContext
        appContext = ctx
        settings = loadSettings(ctx)
        val warn: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) }
        val s = RollingFileSink(File(ctx.filesDir, "logs"), config, LogClock.SYSTEM, { it.usableSpace }, warn)
        sink = s
        writer = AsyncLogWriter(s, formatter, LogClock.SYSTEM, config, warn) { checkDebugExpiry() }.also { it.start() }
        checkDebugExpiry()
        val cur = settings
        critical("会话开始",
            "version", BuildConfig.VERSION_NAME, "versionCode", BuildConfig.VERSION_CODE,
            "deviceId", deviceId(), "wallClock", formatTime(System.currentTimeMillis()),
            "uptimeMs", SystemClock.elapsedRealtime(), "level", describeLevel(cur), "source", cur.source)
    }

    @JvmStatic
    fun isLoggable(module: LogModule, level: LogLevel): Boolean = LevelPolicy.isLoggable(settings, module, level)

    @JvmStatic
    fun d(module: LogModule, event: String, vararg keyValues: Any?) = log(module, LogLevel.DEBUG, event, null, false, keyValues)

    @JvmStatic
    fun i(module: LogModule, event: String, vararg keyValues: Any?) = log(module, LogLevel.INFO, event, null, false, keyValues)

    @JvmStatic
    fun w(module: LogModule, event: String, vararg keyValues: Any?) = log(module, LogLevel.WARN, event, null, false, keyValues)

    @JvmStatic
    fun e(module: LogModule, event: String, vararg keyValues: Any?) = log(module, LogLevel.ERROR, event, null, false, keyValues)

    @JvmStatic
    fun e(module: LogModule, event: String, error: Throwable, vararg keyValues: Any?) =
        log(module, LogLevel.ERROR, event, error, false, keyValues)

    /**
     * 日志系统自身的关键事件（会话头、级别变更、到期、导出、清空）：不受级别限制、立即落盘，固定写 SYSTEM。
     * 只给日志体系内部用；业务事件（包括报警）一律走级别，重要的用 [e]。崩溃见 [crashSync]。
     */
    internal fun critical(event: String, vararg keyValues: Any?) =
        log(LogModule.SYSTEM, LogLevel.INFO, event, null, true, keyValues)

    /** 崩溃时在当前线程同步写入并 fsync，进程随后就会退出。 */
    @JvmStatic
    fun crashSync(thread: Thread?, error: Throwable) {
        val body = LogFormatter.bodyWithError("崩溃", error, "thread", thread?.name)
        Log.e(TAG, body)
        try {
            writer?.drainSync()
            writer?.writeSync(LogRecord(System.currentTimeMillis(), LogModule.SYSTEM, LogLevel.ERROR, body, true))
        } catch (t: Throwable) {
            Log.e(TAG, "崩溃日志写入失败", t)
        }
    }

    @JvmStatic
    fun getSettings(): LevelSettings = settings

    /**
     * 统一的级别设置入口（本地设置页；以后云端下发也走这里）。后设置的生效。
     * [debugDurationMs] ≤ 0 时 DEBUG 默认 24 小时后退回 WARN。
     */
    @JvmStatic
    @Synchronized
    fun applyLevel(
        enabled: Boolean,
        level: LogLevel,
        modules: Map<LogModule, LogLevel>,
        debugDurationMs: Long,
        source: LevelSource
    ) {
        val next = LevelPolicy.apply(enabled, level, modules, debugDurationMs, source, System.currentTimeMillis())
        settings = next
        appContext?.let { saveSettings(it, next) }
        critical("日志级别变更", "level", describeLevel(next), "source", source,
            "debugUntil", if (next.debugUntilMs > 0) formatTime(next.debugUntilMs) else "-")
    }

    /** 当前日志文件总大小（字节）；会列目录，不要在主线程上频繁调用。 */
    @JvmStatic
    fun usedBytes(): Long = sink?.totalBytes() ?: 0L

    /** 后台打包导出到外部存储根目录。 */
    @JvmStatic
    fun exportAsync(callback: ResultCallback) {
        ioExecutor.execute {
            var result: File? = null
            var error: Throwable? = null
            try {
                result = export()
                critical("日志已导出", "path", result.absolutePath, "bytes", result.length(), "removedOld", removedOld)
            } catch (t: Throwable) {
                error = t
                e(LogModule.SYSTEM, "日志导出失败", t)
            }
            mainHandler.post { callback.onResult(result, error) }
        }
    }

    /** 后台删除全部日志文件，删除后写一条清空记录。 */
    @JvmStatic
    fun clearAsync(callback: ResultCallback) {
        ioExecutor.execute {
            val ok = writer?.clear(BARRIER_TIMEOUT_MS) ?: false
            val error = if (ok) null else IllegalStateException("clear timeout")
            if (ok) critical("日志已清空") else e(LogModule.SYSTEM, "日志清空失败", error!!)
            mainHandler.post { callback.onResult(null, error) }
        }
    }

    @JvmStatic
    fun describeLevel(s: LevelSettings): String {
        if (!s.enabled) return "E(off)"
        val base = s.level.name.substring(0, 1)
        if (s.modules.isEmpty()) return base
        return base + s.modules.entries.joinToString(",", "{", "}") { "${it.key}=${it.value.name.substring(0, 1)}" }
    }

    private fun log(module: LogModule, level: LogLevel, event: String, error: Throwable?, critical: Boolean,
                    keyValues: Array<out Any?>) {
        if (!critical && !isLoggable(module, level)) return
        val body = if (error == null) LogFormatter.body(event, *keyValues)
        else LogFormatter.bodyWithError(event, error, *keyValues)
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, "YW-" + module.name, body)
        writer?.enqueue(LogRecord(System.currentTimeMillis(), module, level, body, critical))
    }

    /** 写线程每秒调用一次；也在初始化时调用。 */
    private fun checkDebugExpiry() {
        val cur = settings
        val now = System.currentTimeMillis()
        if (!LevelPolicy.isDebugExpired(cur, now)) return
        synchronized(this) {
            if (settings !== cur) return
            val next = LevelPolicy.expireDebug(cur).copy(setAtMs = now)
            settings = next
            appContext?.let { saveSettings(it, next) }
            critical("详细日志到期", "level", describeLevel(next), "source", next.source)
        }
    }

    @Throws(Exception::class)
    private fun export(): File {
        val w = writer ?: throw IllegalStateException("log not initialized")
        if (!w.flushBarrier(BARRIER_TIMEOUT_MS)) throw IllegalStateException("flush timeout")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val id = deviceId().ifEmpty { "unknown" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val root = Environment.getExternalStorageDirectory()
        // 根目录只留最新一份导出：旧 zip 和日志在同一分区，堆多了会让剩余空间跌破下限、暂停写日志
        removedOld = root.listFiles()?.filter {
            it.isFile && it.name.startsWith(EXPORT_PREFIX) && (it.name.endsWith(".zip") || it.name.endsWith(".zip.tmp"))
        }?.count { it.delete() } ?: 0
        val target = File(root, "$EXPORT_PREFIX$id-$stamp.zip")
        val dirs = LinkedHashMap<String, File>()
        sink?.let { dirs["logs"] = it.dir }
        dirs["crashLog"] = File(Environment.getExternalStorageDirectory(), "crashLog")
        val groups = sink?.filesByModule() ?: emptyList()
        LogArchiver.zip(target, dirs, mapOf("device-info.txt" to deviceInfo()),
            mapOf("all.log" to { w: java.io.Writer -> LogMerger.merge(groups, w) }))
        return target
    }

    private fun deviceInfo(): String {
        val s = settings
        val sb = StringBuilder()
        fun line(k: String, v: Any?) = sb.append(k).append('=').append(v).append('\n')
        line("exportTime", formatTime(System.currentTimeMillis()))
        line("version", BuildConfig.VERSION_NAME)
        line("versionCode", BuildConfig.VERSION_CODE)
        line("deviceId", deviceId())
        line("model", Build.MODEL)
        line("sdk", Build.VERSION.SDK_INT)
        line("freeBytes", Environment.getExternalStorageDirectory().usableSpace)
        line("logBytes", usedBytes())
        line("logLevel", describeLevel(s))
        line("logLevelSource", s.source)
        line("logLevelSetAt", if (s.setAtMs > 0) formatTime(s.setAtMs) else "-")
        line("debugUntil", if (s.debugUntilMs > 0) formatTime(s.debugUntilMs) else "-")
        sb.append("\n[tanks]\n")
        try {
            val data = MyApplication.getInstance().getmOilcanDataManager().data
            for (i in 0 until data.size()) {
                val c = data.valueAt(i) ?: continue
                sb.append(LogFormatter.body("tank",
                    "pos", c.oilCanPos, "probeAdr", c.probeAdr, "probeType", c.probeType,
                    "highTemp", c.highTempAlarm, "tempCorr", c.tempCorrection,
                    "high", c.highLevelAlarm, "hhigh", c.hhighLevelAlarm,
                    "low", c.lowLevelAlarm, "llow", c.llowLevelAlarm,
                    "highWater", c.highWaterLevelAlarm, "levelCorr", c.levelCorrection,
                    "waterCorr", c.waterLevelCorrection, "maxHeight", c.maxHeight)).append('\n')
            }
        } catch (t: Throwable) {
            sb.append("读取罐配置失败: ").append(t).append('\n')
        }
        return sb.toString()
    }

    private fun deviceId(): String {
        val ctx = appContext ?: return ""
        return SharePrefUtil.getString(ctx, SharePrefUtil.TB_DEVICE_ID, "") ?: ""
    }

    private fun formatTime(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun loadSettings(ctx: Context): LevelSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            LevelSettings(
                enabled = p.getBoolean("enabled", true),
                level = LogLevel.valueOf(p.getString("level", LevelPolicy.DEFAULT_ENABLED_LEVEL.name)!!),
                modules = parseModules(p.getString("modules", "") ?: ""),
                debugUntilMs = p.getLong("debugUntilMs", 0),
                source = LevelSource.valueOf(p.getString("source", LevelSource.LOCAL.name)!!),
                setAtMs = p.getLong("setAtMs", 0)
            )
        } catch (e: Exception) {
            Log.w(TAG, "日志级别设置无法解析，使用默认值", e)
            LevelSettings()
        }
    }

    private fun saveSettings(ctx: Context, s: LevelSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putString("level", s.level.name)
            .putString("modules", s.modules.entries.joinToString(",") { "${it.key.name}=${it.value.name}" })
            .putLong("debugUntilMs", s.debugUntilMs)
            .putString("source", s.source.name)
            .putLong("setAtMs", s.setAtMs)
            .apply()
    }

    private fun parseModules(text: String): Map<LogModule, LogLevel> {
        if (text.isBlank()) return emptyMap()
        return text.split(',').associate { pair ->
            val (m, l) = pair.split('=', limit = 2)
            LogModule.valueOf(m) to LogLevel.valueOf(l)
        }
    }
}
