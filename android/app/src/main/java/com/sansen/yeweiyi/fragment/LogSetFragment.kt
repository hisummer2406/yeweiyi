package com.sansen.yeweiyi.fragment

import android.app.AlertDialog
import android.databinding.Observable
import android.databinding.ObservableField
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.activity.MainActivity
import com.sansen.yeweiyi.base.BaseFragment
import com.sansen.yeweiyi.bean.SerialPortEntrity
import com.sansen.yeweiyi.logic.applog.LevelPolicy
import com.sansen.yeweiyi.logic.applog.LevelSource
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.applog.AppLog
import com.sansen.yeweiyi.applog.LogExportUi
import com.sansen.yeweiyi.utils.SystemUtils
import com.sansen.yeweiyi.utils.ToastUtils
import java.util.Locale

/**
 * 日志诊断：日志记录开关、记录级别、导出、清空。级别规则见 [LevelPolicy]。
 * 给技术支持用，入口是长按高级设置底栏的"导出日志"按钮（见 SetSeniorFragment）。
 */
class LogSetFragment : BaseFragment() {

    private lateinit var swEnabled: Switch
    private lateinit var rgLevel: RadioGroup
    private lateinit var tvStatus: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvTime: TextView
    /** 代码里同步控件状态时置位，避免触发监听器又去改一次级别。 */
    private var syncing = false
    private var clockRegistered = false

    private val levelButtons = mapOf(
        LogLevel.ERROR to R.id.rb_level_error,
        LogLevel.WARN to R.id.rb_level_warn,
        LogLevel.INFO to R.id.rb_level_info,
        LogLevel.DEBUG to R.id.rb_level_debug
    )

    private val clockCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            @Suppress("UNCHECKED_CAST")
            tvTime.text = (sender as? ObservableField<String>)?.get()
            // 时钟每秒在主线程刷新一次，顺带刷新剩余时长（详细日志可能刚到期），不另起定时器
            syncFromSettings()
        }
    }

    override fun initView(inflater: LayoutInflater): View = inflater.inflate(R.layout.fragment_log_set, null)

    override fun initData(savedInstanceState: Bundle?) {
        findView()
    }

    override fun findView() {
        swEnabled = rootView.findViewById(R.id.sw_log_enabled)
        rgLevel = rootView.findViewById(R.id.rg_log_level)
        // 级别名统一显示英文缩写，不进资源翻译
        for ((level, id) in levelButtons) rootView.findViewById<TextView>(id).text = level.name.substring(0, 1)
        tvStatus = rootView.findViewById(R.id.tv_log_status)
        tvSize = rootView.findViewById(R.id.tv_log_size)
        rootView.findViewById<LinearLayout>(R.id.ly_export_log).setOnClickListener(this)
        rootView.findViewById<LinearLayout>(R.id.ly_clear_log).setOnClickListener(this)
        rootView.findViewById<LinearLayout>(R.id.ly_upper_page).setOnClickListener(this)

        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            // 打开时默认 W；关闭后只记 E，下次打开仍从 W 开始
            AppLog.applyLevel(checked, LevelPolicy.DEFAULT_ENABLED_LEVEL, emptyMap(), 0, LevelSource.LOCAL)
            syncFromSettings()
        }
        rgLevel.setOnCheckedChangeListener { _, checkedId ->
            if (syncing) return@setOnCheckedChangeListener
            val level = levelButtons.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            AppLog.applyLevel(true, level, emptyMap(), 0, LevelSource.LOCAL)
            syncFromSettings()
        }

        val headerView = rootView.findViewById<View>(R.id.title_bar)
        headerView.findViewById<TextView>(R.id.tv_title).setText(R.string.log_set_entry)
        tvTime = headerView.findViewById(R.id.main_time)
        SerialPortEntrity.time.addOnPropertyChangedCallback(clockCallback)
        clockRegistered = true

        syncFromSettings()
        refreshSize()
    }

    override fun processClick(v: View) {
        when (v.id) {
            R.id.ly_export_log -> export()
            R.id.ly_clear_log -> confirmClear()
            R.id.ly_upper_page -> goBack()
        }
    }

    private fun syncFromSettings() {
        val s = AppLog.getSettings()
        syncing = true
        swEnabled.isChecked = s.enabled
        rgLevel.check(levelButtons.getValue(s.level))
        for (i in 0 until rgLevel.childCount) rgLevel.getChildAt(i).isEnabled = s.enabled
        syncing = false

        // 来源直接显示 LOCAL/CLOUD；D 级时附剩余时长 hh:mm
        var detail = s.source.name
        if (LevelPolicy.usesDebug(s)) {
            val leftMin = ((s.debugUntilMs - System.currentTimeMillis()).coerceAtLeast(0) / 60_000).toInt()
            detail += String.format(Locale.US, " · D %02d:%02d", leftMin / 60, leftMin % 60)
        }
        tvStatus.text = SystemUtils.getRsStr(R.string.log_status_format, AppLog.describeLevel(s), detail)
    }

    /** 统计占用要列目录，放到后台线程。 */
    private fun refreshSize() {
        Thread({
            val bytes = AppLog.usedBytes()
            rootView.post {
                if (isAdded) {
                    tvSize.text = SystemUtils.getRsStr(R.string.log_size_format,
                        String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0))
                }
            }
        }, "log-size").start()
    }

    private fun export() {
        LogExportUi.exportAndToast { if (isAdded) refreshSize() }
    }

    private fun confirmClear() {
        AlertDialog.Builder(main)
            .setTitle(SystemUtils.getRsStr(R.string.log_clear))
            .setMessage(SystemUtils.getRsStr(R.string.isDeleteRecord))
            .setPositiveButton(SystemUtils.getRsStr(R.string.confirm)) { _, _ ->
                AppLog.clearAsync { _, error ->
                    ToastUtils.showToast(SystemUtils.getRsStr(
                        if (error == null) R.string.log_cleared else R.string.log_op_failed), Toast.LENGTH_LONG)
                    if (isAdded) refreshSize()
                }
            }
            .setNegativeButton(SystemUtils.getRsStr(R.string.cancle), null)
            .show()
    }

    /** 从哪个页面进来就返回哪个（高级设置 / 厂家设置），由 [open] 记录。 */
    private var returnTo: BaseFragment? = null

    private fun goBack() {
        val target = returnTo ?: main.setSeniorFragment ?: SetSeniorFragment().also { main.setSeniorFragment = it }
        main.switchContent(main.mContent, target)
    }

    companion object {
        /** 进入日志诊断页，返回时回到当前页面。 */
        @JvmStatic
        fun open(main: MainActivity) {
            val page = main.logSetFragment ?: LogSetFragment().also { main.logSetFragment = it }
            page.returnTo = main.mContent
            main.switchContent(main.mContent, page)
        }
    }

    /** 实例被复用（见 MainActivity.logSetFragment）：隐藏时停掉时钟回调，再显示时刷新状态和占用空间。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            unregisterClock()
        } else {
            if (!clockRegistered) {
                SerialPortEntrity.time.addOnPropertyChangedCallback(clockCallback)
                clockRegistered = true
            }
            syncFromSettings()
            refreshSize()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterClock()
    }

    private fun unregisterClock() {
        if (clockRegistered) {
            SerialPortEntrity.time.removeOnPropertyChangedCallback(clockCallback)
            clockRegistered = false
        }
    }

    override fun initTitle() {}
}
