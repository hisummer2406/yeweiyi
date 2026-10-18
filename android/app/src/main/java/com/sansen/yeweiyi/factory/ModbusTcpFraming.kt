package com.sansen.yeweiyi.factory

/**
 * Modbus TCP 通道的收帧/组帧，同一端口同时认两种格式：
 * - RTU over TCP：串口帧原样（`08 03 地址 数量 CRC`），应答也是 RTU 帧
 * - 标准 Modbus TCP：7 字节 MBAP 头（事务号、协议号 0、长度、单元号）+ PDU，无 CRC，应答原样带回事务号和单元号
 * 先按 RTU 找 CRC 正确的请求，找不到再按 MBAP 解析：地址为 0 的 RTU 请求第 3、4 字节也是 00 00，不能只看协议号。
 */
internal object ModbusTcpFraming {

    sealed class Frame(val consumed: Int)

    class Rtu(val request: ZhongChengModbusSlave.Request, consumed: Int) : Frame(consumed)

    /** [request] 为 null 表示不是"功能码 03 + 4 字节参数"的读请求，应回异常码 [exceptionCode] */
    class Mbap(
        val transactionId: Int,
        val unitId: Int,
        val function: Int,
        val request: ZhongChengModbusSlave.Request?,
        val exceptionCode: Int,
        consumed: Int
    ) : Frame(consumed)

    private const val MBAP_HEADER = 7
    /** MBAP 长度字段 = 单元号 1 字节 + PDU；PDU 最长 253 */
    private const val MBAP_MAX_LENGTH = 254
    private const val EXCEPTION_ILLEGAL_FUNCTION = 0x01
    private const val EXCEPTION_ILLEGAL_VALUE = 0x03

    /** 从缓冲区解析一帧；数据还不完整或不是合法帧时返回 null */
    fun parse(buf: ByteArray): Frame? {
        ZhongChengModbusSlave.findRequest(buf)?.let { return Rtu(it, buf.size) }
        if (buf.size < MBAP_HEADER || buf[2].toInt() != 0 || buf[3].toInt() != 0) return null
        val length = u16(buf, 4)
        if (length < 2 || length > MBAP_MAX_LENGTH || buf.size < 6 + length) return null
        val function = buf[7].toInt() and 0xFF
        val request = if (function == ZhongChengModbusSlave.FUNC_READ_HOLDING && length == 6) {
            ZhongChengModbusSlave.Request(u16(buf, 8), u16(buf, 10))
        } else null
        val code = if (function != ZhongChengModbusSlave.FUNC_READ_HOLDING) EXCEPTION_ILLEGAL_FUNCTION
        else EXCEPTION_ILLEGAL_VALUE
        return Mbap(u16(buf, 0), buf[6].toInt() and 0xFF, function, request, code, 6 + length)
    }

    /**
     * [parse] 返回 null 后调用：缓冲区开头既不可能是 RTU 请求的开头（08 03），也不是合法的 MBAP 头时返回 true，
     * 调用方应清空缓冲区。MBAP 没有校验，帧边界乱了无法可靠找回，所以整体丢弃而不是逐字节往后找。
     */
    fun isUnrecognizable(buf: ByteArray): Boolean {
        if (buf.isEmpty()) return false
        val rtuPrefix = (buf[0].toInt() and 0xFF) == ZhongChengModbusSlave.SLAVE_ID &&
            (buf.size < 2 || (buf[1].toInt() and 0xFF) == ZhongChengModbusSlave.FUNC_READ_HOLDING)
        if (rtuPrefix) return false
        val protocolOk = (buf.size < 3 || buf[2].toInt() == 0) && (buf.size < 4 || buf[3].toInt() == 0)
        val lengthOk = buf.size < 6 || u16(buf, 4) in 2..MBAP_MAX_LENGTH
        return !(protocolOk && lengthOk)
    }

    /** 把 [ZhongChengModbusSlave] 组好的 RTU 应答帧（地址 + PDU + CRC）改装成 MBAP 应答 */
    fun mbapResponse(frame: Mbap, rtuResponse: ByteArray): ByteArray =
        mbap(frame, rtuResponse.copyOfRange(1, rtuResponse.size - 2))

    fun mbapException(frame: Mbap): ByteArray =
        mbap(frame, byteArrayOf((frame.function or 0x80).toByte(), frame.exceptionCode.toByte()))

    private fun mbap(frame: Mbap, pdu: ByteArray): ByteArray {
        val length = pdu.size + 1
        val header = byteArrayOf(
            (frame.transactionId shr 8).toByte(), frame.transactionId.toByte(),
            0, 0,
            (length shr 8).toByte(), length.toByte(),
            frame.unitId.toByte()
        )
        return header + pdu
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
