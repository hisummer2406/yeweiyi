package com.sansen.yeweiyi.manager

import android.app.XzjhSystemManager
import android.content.Context
import com.sansen.yeweiyi.applog.PumpLog
import com.sansen.yeweiyi.bean.OilCan
import com.sansen.yeweiyi.logic.pump.LevelPumpController
import com.sansen.yeweiyi.logic.pump.LevelPumpController.Change
import com.sansen.yeweiyi.utils.SharePrefUtil
import java.util.Timer
import kotlin.concurrent.timer

/**
 * 单罐开关量输出（IO2 联动）：绑定罐低低液位闭合、高高液位断开，判断见 [LevelPumpController]。
 * 设置在串口设置页（默认关闭）。读数来自 SerialPortManager.hexDataParseFinish（只有真实探针数据，
 * 演示模式不进来），断线来自其 PRO_ERR_CONN。与 IO1 报警灯、接警/消警互不影响。
 */
object PumpIoManager {

    /** XzjhSystemManager 的 GPIO 编号；IO1 报警灯是 1，按同样用法推断 IO2 是 2，写 1 闭合。 */
    private const val GPIO_IO2 = 2
    private const val STALE_CHECK_PERIOD_MS = 10_000L

    /** 读数（串口线程）、设置（UI 线程）、超时检查（定时线程）共用这一把锁。 */
    private val lock = Any()
    private lateinit var appContext: Context
    private val controller = LevelPumpController()
    private var enabled = false
    private var tank = 0
    private var thresholdValid = true
    private var staleTimer: Timer? = null

    /** MyApplication.onCreate 调用：先断开 IO2，再读设置、启动超时检查。 */
    @JvmStatic
    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            writeIo(false)
            tank = SharePrefUtil.getInt(appContext, SharePrefUtil.PUMP_LINK_TANK, 0)
            enabled = tank > 0 && SharePrefUtil.getBoolean(appContext, SharePrefUtil.PUMP_LINK_ENABLED, false)
            PumpLog.onSettings(enabled, tank)
            if (staleTimer == null) {
                staleTimer = timer("pump-io-stale", daemon = true, period = STALE_CHECK_PERIOD_MS) {
                    checkStale()
                }
            }
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    /** 绑定罐号，0 表示未选。 */
    @JvmStatic
    fun getTank(): Int = synchronized(lock) { tank }

    /** 打开前必须已选罐号，否则返回 false 且不保存。 */
    @JvmStatic
    fun setEnabled(value: Boolean): Boolean {
        synchronized(lock) {
            if (value && tank <= 0) return false
            enabled = value
            SharePrefUtil.saveBoolean(appContext, SharePrefUtil.PUMP_LINK_ENABLED, value)
            applySettingsChange()
            return true
        }
    }

    @JvmStatic
    fun setTank(value: Int) {
        synchronized(lock) {
            tank = value
            SharePrefUtil.saveInt(appContext, SharePrefUtil.PUMP_LINK_TANK, value)
            applySettingsChange()
        }
    }

    @JvmStatic
    fun onReading(oilCan: OilCan) {
        synchronized(lock) {
            if (!enabled || oilCan.oilCanPos != tank || oilCan.errorTimes != 0) return
            val llow = oilCan.llowLevelAlarm
            val hhigh = oilCan.hhighLevelAlarm
            val valid = LevelPumpController.thresholdsValid(llow, hhigh)
            if (!valid && thresholdValid) PumpLog.onThresholdInvalid(tank, llow, hhigh)
            thresholdValid = valid
            val level = oilCan.oilLevel
            apply(controller.onReading(level, llow, hhigh, System.currentTimeMillis()), level)
        }
    }

    @JvmStatic
    fun onProbeLost(pos: Int) {
        synchronized(lock) {
            if (!enabled || pos != tank) return
            apply(controller.onProbeLost(), null)
        }
    }

    private fun checkStale() {
        synchronized(lock) {
            apply(controller.checkStale(System.currentTimeMillis()), null)
        }
    }

    private fun applySettingsChange() {
        PumpLog.onSettings(enabled, tank)
        thresholdValid = true
        apply(controller.reset(), null)
    }

    private fun apply(change: Change?, level: Double?) {
        if (change == null) return
        PumpLog.onSwitch(tank, change.on, change.reason.name, level)
        writeIo(change.on)
    }

    private fun writeIo(on: Boolean) {
        try {
            val mgr = appContext.getSystemService("xzjh_server") as? XzjhSystemManager
            if (mgr == null) {
                PumpLog.onWriteFailed(on, null)
                return
            }
            PumpLog.onWrite(on, mgr.xzjhSetGpioValue(GPIO_IO2, if (on) 1 else 0))
        } catch (e: Throwable) {
            PumpLog.onWriteFailed(on, e)
        }
    }
}
