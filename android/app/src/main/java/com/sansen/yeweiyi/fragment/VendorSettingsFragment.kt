package com.sansen.yeweiyi.fragment

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.databinding.Observable
import android.databinding.ObservableField
import android.os.Bundle
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.applog.AppLog
import com.sansen.yeweiyi.base.BaseFragment
import com.sansen.yeweiyi.bean.SerialPortEntrity
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.SystemUtils
import com.sansen.yeweiyi.utils.ToastUtils
import com.sansen.yeweiyi.vendor.BrandLogo

/**
 * 厂家设置页（入口见 VendorEntry：版本号连点 + 动态密码）。纯文字列表，新增厂家功能往 [items] 里加一项。
 * 只有中文，不做多语言。实例由 MainActivity.vendorSettingsFragment 复用，页面切换只 hide 不销毁。
 */
class VendorSettingsFragment : BaseFragment() {

    private class Item(val title: Int, val summary: () -> String, val action: () -> Unit)

    private lateinit var adapter: ItemAdapter
    private lateinit var tvTime: TextView
    private var clockRegistered = false

    private val items: List<Item> by lazy {
        listOf(
            Item(R.string.vendor_logo_change, {
                SystemUtils.getRsStr(R.string.vendor_logo_change_summary, SystemUtils.getRsStr(
                    if (BrandLogo.isCustom(main) ) R.string.vendor_logo_custom else R.string.vendor_logo_default))
            }) { pickLogo() },
            Item(R.string.vendor_logo_reset, { SystemUtils.getRsStr(R.string.vendor_logo_reset_summary) }) { resetLogo() },
            Item(R.string.vendor_log_diag, { SystemUtils.getRsStr(R.string.vendor_log_diag_summary) }) { openLogDiag() }
        )
    }

    private val clockCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            @Suppress("UNCHECKED_CAST")
            tvTime.text = (sender as? ObservableField<String>)?.get()
        }
    }

    override fun initView(inflater: LayoutInflater): View = inflater.inflate(R.layout.fragment_vendor_settings, null)

    override fun initData(savedInstanceState: Bundle?) {
        findView()
    }

    override fun findView() {
        val rv = rootView.findViewById<RecyclerView>(R.id.rv_vendor_items)
        rv.layoutManager = LinearLayoutManager(main)
        rv.addItemDecoration(DividerItemDecoration(main, DividerItemDecoration.VERTICAL))
        adapter = ItemAdapter()
        rv.adapter = adapter
        rootView.findViewById<View>(R.id.ly_upper_page).setOnClickListener(this)

        val headerView = rootView.findViewById<View>(R.id.title_bar)
        headerView.findViewById<TextView>(R.id.tv_title).setText(R.string.vendor_title)
        tvTime = headerView.findViewById(R.id.main_time)
        registerClock()
    }

    override fun processClick(v: View) {
        if (v.id == R.id.ly_upper_page) {
            main.switchContent(main.mContent, main.mainFragment)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) unregisterClock() else {
            registerClock()
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterClock()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_LOGO || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val appContext = main.applicationContext
        Thread({
            val ok = try {
                BrandLogo.save(appContext, uri)
                true
            } catch (e: Exception) {
                AppLog.e(LogModule.SYSTEM, "更换中间logo失败", e)
                false
            }
            if (ok) AppLog.w(LogModule.SYSTEM, "更换中间logo")
            main.runOnUiThread {
                refreshTitleLogo()
                if (isAdded) adapter.notifyDataSetChanged()
                ToastUtils.showToast(SystemUtils.getRsStr(
                    if (ok) R.string.vendor_logo_saved else R.string.vendor_logo_failed), Toast.LENGTH_LONG)
            }
        }, "vendor-logo").start()
    }

    private fun pickLogo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_LOGO)
        } catch (e: ActivityNotFoundException) {
            // 部分终端没有文件管理器/图库
            AppLog.e(LogModule.SYSTEM, "没有可用的图片选择器", e)
            ToastUtils.showToast(SystemUtils.getRsStr(R.string.vendor_no_picker), Toast.LENGTH_LONG)
        }
    }

    private fun resetLogo() {
        BrandLogo.reset(main)
        AppLog.w(LogModule.SYSTEM, "恢复默认中间logo")
        refreshTitleLogo()
        adapter.notifyDataSetChanged()
        ToastUtils.showToast(SystemUtils.getRsStr(R.string.vendor_logo_reset_done), Toast.LENGTH_LONG)
    }

    private fun openLogDiag() {
        LogSetFragment.open(main)
    }

    /** 主界面标题栏在 Activity 上，不在本页面里。 */
    private fun refreshTitleLogo() {
        BrandLogo.applyTo(main.findViewById<ImageView>(R.id.bar_iv_center_logo))
    }

    private fun registerClock() {
        if (!clockRegistered) {
            SerialPortEntrity.time.addOnPropertyChangedCallback(clockCallback)
            clockRegistered = true
        }
    }

    private fun unregisterClock() {
        if (clockRegistered) {
            SerialPortEntrity.time.removeOnPropertyChangedCallback(clockCallback)
            clockRegistered = false
        }
    }

    override fun initTitle() {}

    private inner class ItemAdapter : RecyclerView.Adapter<ItemAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tv_vendor_title)
            val summary: TextView = view.findViewById(R.id.tv_vendor_summary)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_vendor_setting, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.setText(item.title)
            holder.summary.text = item.summary()
            holder.itemView.setOnClickListener { item.action() }
        }
    }

    private companion object {
        const val REQUEST_PICK_LOGO = 0x51
    }
}
