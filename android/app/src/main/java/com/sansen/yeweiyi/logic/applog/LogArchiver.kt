package com.sansen.yeweiyi.logic.applog

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 把若干目录和附加文本打成一个 zip。先写 `.tmp` 再改名，导出中途失败不会留下半个 zip。 */
object LogArchiver {

    /**
     * @param dirs  zip 内目录名 → 本地目录（不存在则跳过，只打包一层文件）
     * @param texts zip 内文件名 → 文本内容
     * @param generated zip 内文件名 → 边生成边写入的内容（如合并后的 all.log，不整体放进内存）
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun zip(target: File, dirs: Map<String, File>, texts: Map<String, String>,
            generated: Map<String, (Writer) -> Unit> = emptyMap()) {
        val tmp = File(target.path + ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
                for ((name, dir) in dirs) {
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                        zos.putNextEntry(ZipEntry("$name/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                for ((name, text) in texts) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                for ((name, produce) in generated) {
                    zos.putNextEntry(ZipEntry(name))
                    // 不能 close 这个 writer，否则会把整个 zip 流关掉
                    val w = OutputStreamWriter(zos, Charsets.UTF_8)
                    produce(w)
                    w.flush()
                    zos.closeEntry()
                }
            }
            if (target.exists() && !target.delete()) throw IOException("无法覆盖已有文件: $target")
            if (!tmp.renameTo(target)) throw IOException("重命名失败: $tmp -> $target")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}
