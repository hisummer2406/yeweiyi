package com.sansen.yeweiyi.vendor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import com.sansen.yeweiyi.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 标题栏中间 logo（`bar_iv_center_logo`）的定制：厂家页面选的图片缩放后存到应用私有目录，
 * 有自定义图片就用它，没有就用程序自带的 `top_title_logo`。
 */
object BrandLogo {

    /** 存储上限：标题栏高 80dp，设备密度 160，2 倍余量足够。 */
    private const val MAX_WIDTH = 640
    private const val MAX_HEIGHT = 160

    private fun file(context: Context) = File(File(context.filesDir, "brand"), "top_logo.png")

    @JvmStatic
    fun isCustom(context: Context): Boolean = file(context).exists()

    /** 标题栏初始化和更换后调用。自定义图片读不出来时退回默认图。 */
    @JvmStatic
    fun applyTo(view: ImageView?) {
        if (view == null) return
        val f = file(view.context)
        if (f.exists()) {
            val bitmap = BitmapFactory.decodeFile(f.path)
            if (bitmap != null) {
                view.setImageBitmap(bitmap)
                return
            }
        }
        view.setImageResource(R.drawable.top_title_logo)
    }

    /** 读取用户选的图片，等比缩到上限以内，存成 PNG（保留透明）。在后台线程调用。 */
    @JvmStatic
    @Throws(IOException::class)
    fun save(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 只读尺寸时 decodeStream 本来就返回 null，不能拿返回值判断是否打开成功
        val stream = resolver.openInputStream(uri) ?: throw IOException("无法打开图片")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("不是可识别的图片")
        // 先用 inSampleSize 粗缩，避免大图解码时内存溢出
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH || bounds.outHeight / (sample * 2) >= MAX_HEIGHT) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("图片解码失败")
        val scale = minOf(1f, MAX_WIDTH.toFloat() / decoded.width, MAX_HEIGHT.toFloat() / decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else decoded

        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("图片保存失败") }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("图片保存失败")
        }
    }

    /** 删除自定义 logo，恢复默认。 */
    @JvmStatic
    fun reset(context: Context) {
        file(context).delete()
    }
}
