package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.factory.ModbusTcpServer
import com.sansen.yeweiyi.factory.ZhongChengModbusSlave
import com.sansen.yeweiyi.logic.applog.LogLevel
import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.utils.DataUtils
import java.io.IOException

/**
 * 对外协议日志（EXTERNAL 模块）：串口 5 后台协议（VR350 / Modbus RTU）和 Modbus TCP（5020，RTU over TCP / 标准 MBAP）。
 * 只记录，不改应答内容。各事件级别见 docs/日志体系.md §6。
 */
object ExternalLog : ModbusTcpServer.Logger {

    private const val TAG = "ExternalLog"
    private val M = LogModule.EXTERNAL

    /** VR350Factory 串口 5 每次 Modbus 应答后调用（D 级，每轮轮询一次）。 */
    @JvmStatic
    fun onSerialRequest(request: ZhongChengModbusSlave.Request, response: ByteArray) =
        logRequest("serial", null, request, response)

    /** VR350Factory 解析串口 5 报文失败。 */
    @JvmStatic
    fun onPcParseFailed(raw: Any?, e: Exception) = logSafely(TAG) {
        AppLog.e(M, "后台协议解析失败", e, "raw", raw)
    }

    override fun onListening(port: Int) = logSafely(TAG) {
        AppLog.i(M, "Modbus TCP 开始监听", "port", port)
    }

    override fun onBindFailed(port: Int, e: IOException) = logSafely(TAG) {
        AppLog.e(M, "Modbus TCP 端口绑定失败", e, "port", port)
    }

    override fun onAcceptFailed(e: IOException) = logSafely(TAG) {
        AppLog.e(M, "Modbus TCP accept 失败", e)
    }

    override fun onRejected(remote: String, maxClients: Int) = logSafely(TAG) {
        AppLog.w(M, "Modbus TCP 连接数已满，拒绝", "remote", remote, "max", maxClients)
    }

    override fun onConnected(remote: String) = logSafely(TAG) {
        AppLog.i(M, "Modbus TCP 主站连接", "remote", remote)
    }

    override fun onIdleTimeout(remote: String) = logSafely(TAG) {
        AppLog.i(M, "Modbus TCP 主站空闲超时，断开", "remote", remote)
    }

    override fun onError(remote: String, e: Exception) = logSafely(TAG) {
        AppLog.w(M, "Modbus TCP 通信异常", "remote", remote, "err", e.toString())
    }

    override fun onDisconnected(remote: String) = logSafely(TAG) {
        AppLog.i(M, "Modbus TCP 主站断开", "remote", remote)
    }

    override fun onRequest(remote: String, framing: String, request: ZhongChengModbusSlave.Request?, response: ByteArray) =
        logRequest("tcp-$framing", remote, request, response)

    private fun logRequest(ch: String, remote: String?, request: ZhongChengModbusSlave.Request?, response: ByteArray) = logSafely(TAG) {
        // 每轮轮询都会走到，先判断级别，避免默认 W 级下也去格式化报文
        if (!AppLog.isLoggable(M, LogLevel.DEBUG)) return@logSafely
        AppLog.d(M, "Modbus 应答", "ch", ch, "remote", remote ?: "-", "start", request?.start ?: "-", "qty", request?.quantity ?: "-",
            "resp", DataUtils.bytesToHexString(response, response.size))
    }
}
