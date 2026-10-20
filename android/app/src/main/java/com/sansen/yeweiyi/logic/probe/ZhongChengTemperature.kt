package com.sansen.yeweiyi.logic.probe

/**
 * 中成探针 45 帧温度字节 → ℃。按 docs/协议/新版中成探针协议.doc「温度值」图：tempH 高 5 位是符号位，
 * 两字节为有符号 16 位补码，除以 16。
 */
object ZhongChengTemperature {

    /**
     * 不低于此值视为探针读错：现场旧探针偶发单比特翻转，CRC 照样正确，读成 87~2071℃；
     * 85℃ 本身也是 DS18B20 类芯片上电复位值，同样不可信。
     */
    const val INVALID_FROM_C = 85.0

    const val REASON_SIGN_BITS = "signBits"
    const val REASON_TOO_HIGH = "tooHigh"

    @JvmStatic
    fun decode(hi: Byte, lo: Byte): Double =
        ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort() / 16.0

    /** 有效返回 null，否则返回原因。符号位不一致（不全 0 也不全 1）按协议不可能出现。 */
    @JvmStatic
    fun invalidReason(hi: Byte, lo: Byte): String? {
        val signBits = hi.toInt() and 0xF8
        if (signBits != 0 && signBits != 0xF8) return REASON_SIGN_BITS
        if (decode(hi, lo) >= INVALID_FROM_C) return REASON_TOO_HIGH
        return null
    }
}
