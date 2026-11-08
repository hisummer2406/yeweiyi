package com.sansen.yeweiyi.utils

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.widget.ProgressBar
import android.widget.Toast
import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.applog.AppLog
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.logic.export.ExcelRowsWriter
import com.sansen.yeweiyi.logic.export.ExportColumn
import com.sansen.yeweiyi.logic.export.RecordExportColumns
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 记录页"导出数据"：弹不可取消的 loading 挡住操作，后台取数并写 .xls 到外部存储根目录，
 * 结束后关 loading 并提示路径或失败。
 */
object RecordExportUi {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param columns 列定义（见 RecordExportColumns），表头和每行数据都由它生成
     * @param records 在后台线程执行，负责读库取出要导出的记录
     */
    @JvmStatic
    fun <T> export(activity: Activity, fileName: String, columns: List<ExportColumn<T>>, records: Callable<List<T>>) {
        // 没有存储权限时先申请，授权后自动接着导出
        StoragePermission.ensure(activity) { exportGranted(activity, fileName, columns, records) }
    }

    private fun <T> exportGranted(activity: Activity, fileName: String, columns: List<ExportColumn<T>>, records: Callable<List<T>>) {
        val headers = columns.map { SystemUtils.getRsStr(it.titleRes) }
        val loading = showLoading(activity)
        executor.execute {
            val file = File(Environment.getExternalStorageDirectory(), "$fileName.xls")
            var error: Throwable? = null
            try {
                val data = RecordExportColumns.rows(columns, records.call())
                FileOutputStream(file).use { ExcelRowsWriter.write(headers, data, it) }
            } catch (t: Throwable) {
                error = t
                AppLog.e(LogModule.SYSTEM, "记录导出失败", t, "file", file.absolutePath)
            }
            mainHandler.post {
                // 正在 finish 但未销毁时窗口仍在，要关掉否则销毁时报 WindowLeaked；已销毁时窗口已被系统移除，再 dismiss 会抛异常
                if (!activity.isDestroyed && loading.isShowing) loading.dismiss()
                val msg = if (error == null) SystemUtils.getRsStr(R.string.log_export_done, file.absolutePath)
                else SystemUtils.getRsStr(R.string.log_op_failed)
                ToastUtils.showToast(msg, Toast.LENGTH_LONG)
            }
        }
    }

    private fun showLoading(activity: Activity): Dialog =
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ProgressBar(activity))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
}
