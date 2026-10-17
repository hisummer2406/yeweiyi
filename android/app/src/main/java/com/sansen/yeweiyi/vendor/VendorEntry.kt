package com.sansen.yeweiyi.vendor

import android.os.SystemClock
import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.activity.MainActivity
import com.sansen.yeweiyi.applog.AppLog
import com.sansen.yeweiyi.fragment.LoginDialog
import com.sansen.yeweiyi.fragment.VendorSettingsFragment
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.PWDTools
import com.sansen.yeweiyi.utils.SystemUtils

/**
 * 厂家页面的隐藏入口：标题栏版本号 3 秒内连点 7 次，再输入动态密码（[PWDTools.getFactoryPwd]）。
 */
object VendorEntry {

    private const val TAPS = 7
    private const val WINDOW_MS = 3000L

    /** 剩余次数 ≤ 此值时开始提示。 */
    private const val HINT_FROM = 2

    private var count = 0
    private var hint: Toast? = null
    private var firstTapMs = 0L

    @JvmStatic
    fun onVersionTapped(main: MainActivity) {
        val now = SystemClock.elapsedRealtime()
        if (count == 0 || now - firstTapMs > WINDOW_MS) {
            count = 0
            firstTapMs = now
        }
        count++
        val left = TAPS - count
        if (left <= 0) {
            count = 0
            hint?.cancel()
            askPassword(main)
        } else if (left <= HINT_FROM) {
            // 同 Android 开发者模式：最后几次提示剩余次数，复用一个 Toast 避免排队。
            // 用 applicationContext：本对象是单例，持有 Activity 会泄漏
            hint?.cancel()
            hint = Toast.makeText(main.applicationContext,SystemUtils.getRsStr(R.string.vendor_taps_left, left), Toast.LENGTH_SHORT)
            hint?.show()
        }
    }

    private fun askPassword(main: MainActivity) {
        val dialog = LoginDialog()
        dialog.setOnConfirmListener { input ->
            if (input == PWDTools.getFactoryPwd()) {
                dialog.dismiss()
                AppLog.w(LogModule.SYSTEM, "进入厂家页面")
                if (main.vendorSettingsFragment == null) {
                    main.vendorSettingsFragment = VendorSettingsFragment()
                }
                main.switchContent(main.mContent, main.vendorSettingsFragment)
            } else {
                AppLog.w(LogModule.SYSTEM, "厂家页面密码错误")
                Toast.makeText(main, SystemUtils.getRsStr(R.string.wrong_password_input, input), Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show(main.fragmentManager, "VendorLogin")
    }
}
