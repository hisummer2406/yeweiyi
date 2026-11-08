package com.sansen.yeweiyi.applog

import com.sansen.yeweiyi.logic.applog.LogModule
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcDispatcher
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus

/**
 * ThingsBoard 下发指令日志（CLOUD 模块）：RPC 从收到到出结果的关键节点，以及共享属性生效，全部记 E。
 * 只记录，不改处理流程。各事件见 docs/日志体系.md §6。
 */
object CloudCmdLog : RpcDispatcher.Listener {

    private const val TAG = "CloudCmdLog"
    private val M = LogModule.CLOUD

    /** 参数、原始报文的最大记录长度，超出截断 */
    private const val MAX_TEXT = 1024

    override fun onMalformed(mqttRequestId: String?, error: String?, payload: String?) = logSafely(TAG) {
        AppLog.e(M, "云端指令解析失败", "req", mqttRequestId, "err", error, "payload", cut(payload))
    }

    override fun onReceived(request: RpcRequestEnvelope) = logSafely(TAG) {
        AppLog.e(M, "云端指令收到", "method", request.method, "cmd", request.commandId, "req", request.mqttRequestId,
            "operator", request.operator ?: "-", "reason", request.reason ?: "-",
            "issuedAt", request.issuedAt, "expireAt", request.expireAt, "args", cut(request.args?.toString()))
    }

    override fun onDuplicate(request: RpcRequestEnvelope, previousStatus: RpcResultStatus?, previousErrorCode: RpcErrorCode?) =
        logSafely(TAG) {
            AppLog.e(M, "云端指令重复", "method", request.method, "cmd", request.commandId, "req", request.mqttRequestId,
                "prevStatus", previousStatus, "prevCode", previousErrorCode)
        }

    override fun onRejected(request: RpcRequestEnvelope, internalStatus: RpcResultStatus?, errorCode: RpcErrorCode?, message: String?) =
        logSafely(TAG) {
            AppLog.e(M, "云端指令拒绝", "method", request.method, "cmd", request.commandId, "status", internalStatus,
                "code", errorCode, "msg", message)
        }

    override fun onExecuted(request: RpcRequestEnvelope, result: RpcResult, error: Throwable?, costMillis: Long) = logSafely(TAG) {
        val kv = arrayOf<Any?>("method", request.method, "cmd", request.commandId, "status", result.status,
            "code", result.errorCode, "msg", result.message, "data", cut(result.data?.toString()), "costMs", costMillis)
        if (error != null) AppLog.e(M, "云端指令结果", error, *kv) else AppLog.e(M, "云端指令结果", *kv)
    }

    override fun onTerminalPersistFailed(request: RpcRequestEnvelope, status: RpcResultStatus?) = logSafely(TAG) {
        AppLog.e(M, "云端指令终态写盘失败", "method", request.method, "cmd", request.commandId, "status", status)
    }

    /** RPC ACK 没发出去（未连接或 publish 失败）。 */
    @JvmStatic
    fun onAckPublishFailed(mqttRequestId: String?, commandId: String?, status: RpcResultStatus?, errorCode: RpcErrorCode?) =
        logSafely(TAG) {
            AppLog.e(M, "云端指令应答发送失败", "req", mqttRequestId, "cmd", commandId, "status", status, "code", errorCode)
        }

    /** 启动时发现上次进程留下的未完成指令，已按 FAILED 处理。 */
    @JvmStatic
    fun onRecoveredAfterRestart(count: Int) = logSafely(TAG) {
        AppLog.e(M, "云端指令重启恢复", "count", count)
    }

    /** RPC 分发线程上未被 RpcDispatcher 自己处理的异常。 */
    @JvmStatic
    fun onDispatchCrashed(mqttRequestId: String?, error: Throwable) = logSafely(TAG) {
        AppLog.e(M, "云端指令处理异常", error, "req", mqttRequestId)
    }

    /** 共享属性改了设备配置；值没变时调用方不调（每次重连都会拉一次）。[source] push/response。 */
    @JvmStatic
    fun onSharedAttributeApplied(key: String, from: Any?, to: Any?, source: String) = logSafely(TAG) {
        AppLog.e(M, "云端属性下发", "key", key, "from", from, "to", to, "source", source)
    }

    private fun cut(s: String?): String {
        if (s == null) return "-"
        return if (s.length <= MAX_TEXT) s else s.substring(0, MAX_TEXT) + "...(" + s.length + ")"
    }
}
