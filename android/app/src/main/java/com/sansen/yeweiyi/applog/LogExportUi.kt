package com.sansen.yeweiyi.applog

import android.app.Activity
import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.utils.RecordExportUi
import com.sansen.yeweiyi.utils.StoragePermission
import com.sansen.yeweiyi.utils.SystemUtils
import com.sansen.yeweiyi.utils.ToastUtils

/**
 * 界面上的"导出日志"：高级设置底栏按钮和日志诊断页共用。导出期间弹不可取消的 loading，
 * 后台打包，完成后关 loading 并长提示 zip 路径。
 */
object LogExportUi {

    /** @param onDone 导出结束（成功或失败）后在主线程回调，可为 null；没有存储权限时先申请，被拒绝则不导出、不回调 */
    @JvmStatic
    @JvmOverloads
    fun exportAndToast(activity: Activity, onDone: Runnable? = null) {
        StoragePermission.ensure(activity) { export(activity, onDone) }
    }

    private fun export(activity: Activity, onDone: Runnable?) {
        val loading = RecordExportUi.showLoading(activity)
        AppLog.exportAsync { file, error ->
            // 同 RecordExportUi：已销毁时窗口已被系统移除，再 dismiss 会抛异常
            if (!activity.isDestroyed && loading.isShowing) loading.dismiss()
            // ToastUtils.showToast 的第二个参数直接当 Toast 时长，只认 LENGTH_SHORT/LONG
            if (error != null || file == null) {
                ToastUtils.showToast(SystemUtils.getRsStr(R.string.log_op_failed), Toast.LENGTH_LONG)
            } else {
                ToastUtils.showToast(SystemUtils.getRsStr(R.string.log_export_done, file.absolutePath), Toast.LENGTH_LONG)
            }
            onDone?.run()
        }
    }
}
