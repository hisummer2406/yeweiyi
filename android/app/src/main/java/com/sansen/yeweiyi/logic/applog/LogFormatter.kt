package com.sansen.yeweiyi.logic.applog

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行格式统一在这里生成：`时间 [模块][级别] 事件名 key=value ...`，调用方只给事件名和键值对。
 * [formatLine] 内部用 SimpleDateFormat，加了同步，崩溃同步写入和写线程可以共用一个实例。
 */
class LogFormatter {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun formatLine(record: LogRecord): String =
        timeFormat.format(Date(record.timeMs)) + " [" + record.module.name + "]" + record.level.mark +
            " " + LogRedactor.redact(record.body)

    companion object {
        /**
         * 拼内容：事件名后接成对的 key/value。个数为奇数时最后一个 key 记为 `key=?`，不抛异常——
         * 日志调用写错不能让业务线程崩掉。
         */
        @JvmStatic
        fun body(event: String, vararg keyValues: Any?): String {
            val sb = StringBuilder(event)
            var i = 0
            while (i < keyValues.size) {
                sb.append(' ').append(keyValues[i]).append('=')
                sb.append(if (i + 1 < keyValues.size) keyValues[i + 1].toString() else "?")
                i += 2
            }
            return sb.toString()
        }

        /** 带异常：首行追加 `err=类名: 消息`，后面接堆栈（每行一个 tab 缩进）。 */
        @JvmStatic
        fun bodyWithError(event: String, error: Throwable?, vararg keyValues: Any?): String {
            val head = body(event, *keyValues)
            if (error == null) return head
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            // 堆栈第一行就是 err= 后面那段，跳过；其余每行统一一个 tab 缩进
            val stack = sw.toString().lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            return head + " err=" + error + stack.joinToString("") { "\n\t" + it }
        }
    }
}

/**
 * 凭据脱敏：键名含 token/password/secret/pwd（不区分大小写）的 `key=value`，值替换成 `***`。
 * 作用在整行内容上，调用方即使把凭据拼进了事件名也会被掩掉。
 */
object LogRedactor {
    const val MASK = "***"

    private val SENSITIVE =
        Regex("(?i)([A-Za-z0-9_.]*(?:token|password|secret|pwd)[A-Za-z0-9_.]*)=(\\S+)")

    @JvmStatic
    fun redact(text: String): String = SENSITIVE.replace(text) { it.groupValues[1] + "=" + MASK }
}
