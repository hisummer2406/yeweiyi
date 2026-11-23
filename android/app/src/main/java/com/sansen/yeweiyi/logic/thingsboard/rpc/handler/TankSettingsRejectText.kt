package com.sansen.yeweiyi.logic.thingsboard.rpc.handler

/**
 * 罐设置跨字段校验被拒时的说明文字（#65），小程序原样显示。由调用方取设备界面语言的 strings.xml 文案；
 * 抽成接口是为了 SetTankSettingsHandler 保持纯 Java、可单测。
 */
interface TankSettingsRejectText {
    /** [conflicts] 为"阈值名称(值)"，名称来自 [thresholdName] */
    fun overHeight(heightMm: Int, conflicts: List<String>): String

    /** slot：0 低低液位、1 低液位、2 高液位、3 高高液位、4 高水位 */
    fun thresholdName(slot: Int): String

    fun lowTempNotBelowHigh(lowC: Int, highC: Int): String
}
