package com.sansen.yeweiyi.logic.probe

/** 配置值保持兼容；双显仅占用串口 4，不改变本机其他联动。 */
object Serial4Mode {
    const val OVERFLOW = 0
    const val RELAY_MODBUS = 1
    const val PROXY_ZHONGCHENG = 2
    const val PROXY_MODBUS = 3

    @JvmStatic fun isProxy(mode: Int) = mode == PROXY_ZHONGCHENG || mode == PROXY_MODBUS
    @JvmStatic fun allowsControl(mode: Int) = mode == OVERFLOW || mode == RELAY_MODBUS
    @JvmStatic fun matches(mode: Int, probeType: Int) = when (mode) {
        OVERFLOW, RELAY_MODBUS -> probeType in 0..2
        PROXY_ZHONGCHENG -> probeType == 0
        PROXY_MODBUS -> probeType == 1
        else -> false
    }
}
