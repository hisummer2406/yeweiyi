package com.sansen.yeweiyi.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import com.sansen.yeweiyi.applog.AppLog
import com.sansen.yeweiyi.logic.applog.LogModule

/**
 * 外部存储读写权限（记录导出、日志导出、罐表导入导出读写 /sdcard）。只在用户点这类按钮时申请，
 * 不在启动时申请；授权后自动接着执行这次点击的操作；已被永久拒绝（系统不再弹框）时跳到本应用的系统设置页。
 */
object StoragePermission {

    const val REQUEST_CODE = 102

    private val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    @JvmStatic
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** 等授权结果的操作；只保留最近一次点击的，结果回来后清掉 */
    private var pending: Pair<Activity, Runnable>? = null

    /** 已授权时立即执行 [onGranted]；否则发起申请，授权成功后执行，被拒绝则丢弃（结果走 Activity.onRequestPermissionsResult → [onRequestResult]）。 */
    @JvmStatic
    fun ensure(activity: Activity, onGranted: Runnable) {
        if (isGranted(activity)) {
            onGranted.run()
            return
        }
        pending = activity to onGranted
        ActivityCompat.requestPermissions(activity, PERMISSIONS, REQUEST_CODE)
    }

    @JvmStatic
    fun onRequestResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        // grantResults 为空表示申请被打断（如同时弹了悬浮窗设置页），不算拒绝
        if (requestCode != REQUEST_CODE || grantResults.isEmpty()) return
        val action = pending?.takeIf { it.first === activity }?.second
        pending = null
        if (isGranted(activity)) {
            if (!activity.isDestroyed) action?.run()
            return
        }
        // 拒绝后仍不需要展示理由 = 已永久拒绝，系统不会再弹框，只能去设置页手动开
        if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            AppLog.w(LogModule.SYSTEM, "存储权限已被永久拒绝，跳转应用设置页")
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        }
    }
}
