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
 * 不在启动时申请；已被永久拒绝（系统不再弹框）时跳到本应用的系统设置页，由现场人员手动打开。
 */
object StoragePermission {

    const val REQUEST_CODE = 102

    private val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    @JvmStatic
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** 已授权返回 true；否则发起申请并返回 false（结果走 Activity.onRequestPermissionsResult → [onRequestResult]）。 */
    @JvmStatic
    fun ensure(activity: Activity): Boolean {
        if (isGranted(activity)) return true
        ActivityCompat.requestPermissions(activity, PERMISSIONS, REQUEST_CODE)
        return false
    }

    @JvmStatic
    fun onRequestResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        // grantResults 为空表示申请被打断（如同时弹了悬浮窗设置页），不算拒绝
        if (requestCode != REQUEST_CODE || grantResults.isEmpty() || isGranted(activity)) return
        // 拒绝后仍不需要展示理由 = 已永久拒绝，系统不会再弹框，只能去设置页手动开
        if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            AppLog.w(LogModule.SYSTEM, "存储权限已被永久拒绝，跳转应用设置页")
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        }
    }
}
