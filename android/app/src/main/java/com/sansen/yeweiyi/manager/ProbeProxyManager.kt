package com.sansen.yeweiyi.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.sansen.yeweiyi.applog.ProbeProxyLog
import com.sansen.yeweiyi.logic.probe.ProbeProxySession
import com.sansen.yeweiyi.logic.probe.Serial4Mode
import com.sansen.yeweiyi.utils.Constans
import com.sansen.yeweiyi.utils.SharePrefUtil

/** 启动时固定串口模式，把 Android 配置与日志接到可单测的代理会话。 */
object ProbeProxyManager {
    // 两个端口共用启动快照，避免运行中只有控制路由或采集协议的一侧发生切换。
    private var mode = Serial4Mode.OVERFLOW
    private var protocol = 0
    // init 重复调用不重读配置，保证本进程始终使用同一份模式和缓存会话。
    private var initialized = false
    // 会话负责来源可用性、采集范围和在途请求代次；原始报文缓存封装在其内部。
    private var session: ProbeProxySession? = null
    // 仅保留配置存储，动态读取演示开关，无需在单例中持有 Context。
    private var preferences: SharedPreferences? = null

    /** 在 HAL 和探针线程启动前调用一次；端口协议固定为启动配置，设置修改需重启。 */
    @JvmStatic @Synchronized fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        preferences = SharePrefUtil.preferences(ctx.applicationContext)
        mode = SharePrefUtil.getInt(ctx, Constans.ChuanKou4, 0)
        protocol = SharePrefUtil.getInt(ctx, Constans.ChuanKou2, 0)
        session = ProbeProxySession(mode, protocol, { SystemClock.elapsedRealtime() }, ProbeProxyLog::notice)
        if (!Serial4Mode.matches(mode, protocol)) ProbeProxyLog.notice("protocol-mismatch")
        ProbeProxyLog.startup(mode, protocol)
    }

    /** 返回运行模式，不读设置页尚未重启生效的配置。 */
    @JvmStatic @Synchronized fun getMode() = mode
    /** 供本机探针协议分发使用，与代理保持同一份启动配置。 */
    @JvmStatic @Synchronized fun getSourceProtocol() = protocol
    /** HAL 用此区分串口 4 的接收路径和轮询节奏。 */
    @JvmStatic @Synchronized fun isProxyMode() = Serial4Mode.isProxy(mode)
    /** 双显与原控制输出互斥；入队和实际写串口处均需判断。 */
    @JvmStatic @Synchronized fun allowsControl() = Serial4Mode.allowsControl(mode)

    /** OilcanDataManager 重载罐配置时调用，不依赖本机罐号匹配远端地址。 */
    @JvmStatic @Synchronized fun configureAddresses(values: IntArray) { session?.configureAddresses(values) }
    /** 演示模式没有真实采样来源，不能因串口仍打开而开放缓存应答。 */
    @JvmStatic @Synchronized fun setSourceAvailable(value: Boolean) { session?.setSourceAvailable(value && !isDemo()) }
    /** 对应编排层显式暂停/继续采集，不改变设备的其他业务开关。 */
    @JvmStatic @Synchronized fun setPollingEnabled(value: Boolean) { session?.setPollingEnabled(value) }
    /** 串口关闭或配置变化时丢弃旧数据；不保留等待补发的客户端请求。 */
    @JvmStatic @Synchronized fun invalidate() { session?.invalidate() }

    /** 串口 2 发请求前获取代次；演示切换立即失效采集状态，返回 -1。 */
    @JvmStatic @Synchronized fun sourceRequest(type: Int, bytes: ByteArray): Long {
        if (isDemo()) { session?.setSourceAvailable(false); return -1 }
        return session?.sourceRequest(type, bytes) ?: -1
    }

    /** 复用串口 2 现有读结果，避免另一个读取者抢走本机解析所需的数据。 */
    @JvmStatic @Synchronized fun sourceBytes(token: Long, bytes: ByteArray?) {
        if (isDemo()) { session?.setSourceAvailable(false); return }
        session?.sourceBytes(token, bytes)
    }

    /** 故障使用真实探针地址，不能传油罐显示编号。 */
    @JvmStatic @Synchronized fun probeLost(address: Int) { session?.probeLost(address) }

    /** HAL 输入串口 4 原始查询；返回 null 保持静默，非空交给同一串口立即写出。 */
    @JvmStatic @Synchronized fun serial4Request(bytes: ByteArray): ByteArray? {
        ProbeProxyLog.frame("request", bytes)
        if (isDemo()) {
            session?.setSourceAvailable(false)
            ProbeProxyLog.notice("source-unavailable")
            return null
        }
        return session?.reply(bytes)?.also { ProbeProxyLog.frame("response", it) }
    }

    /** 演示开关运行中可改，不能像串口协议一样只在启动时读取。 */
    private fun isDemo(): Boolean {
        val prefs = preferences ?: return true
        return prefs.getInt(Constans.isTestStatus, 0) != 0
    }
}
