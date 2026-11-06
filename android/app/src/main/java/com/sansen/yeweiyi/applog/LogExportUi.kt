package com.sansen.yeweiyi.applog

import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.utils.SystemUtils
import com.sansen.yeweiyi.utils.ToastUtils

/**
 * 界面上的"导出日志"：高级设置底栏按钮和日志诊断页共用。导出在后台做，完成后长提示 zip 路径。
 */
object LogExportUi {

    /** @param onDone 导出结束（成功或失败）后在主线程回调，可为 null */
    @JvmStatic
    @JvmOverloads
    fun exportAndToast(onDone: Runnable? = null) {
        AppLog.exportAsync { file, error ->
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
