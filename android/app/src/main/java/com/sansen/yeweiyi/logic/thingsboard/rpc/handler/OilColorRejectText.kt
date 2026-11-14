package com.sansen.yeweiyi.logic.thingsboard.rpc.handler

/**
 * 远程改色被拒时的说明文字。设备端界面多语言（AGENTS.md：新提示一律走 strings.xml），
 * 由调用方用 SystemUtils.getRsStr 取当前语言的文案；抽成接口是为了单测不依赖 Android 资源。
 */
fun interface OilColorRejectText {
    /** rgbHex 形如 "#123456" */
    fun notInPalette(rgbHex: String): String
}
