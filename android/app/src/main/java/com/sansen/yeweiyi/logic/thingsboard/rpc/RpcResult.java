package com.sansen.yeweiyi.logic.thingsboard.rpc;

import java.util.Collections;
import java.util.Map;

/** 一次 RPC 方法执行的结果：终态 + 错误码（成功时为 NONE）+ 人类可读消息 + 可选结果数据。 */
public final class RpcResult {

    private final RpcResultStatus status;
    private final RpcErrorCode errorCode;
    private final String message;
    private final Map<String, Object> data;

    private RpcResult(RpcResultStatus status, RpcErrorCode errorCode, String message, Map<String, Object> data) {
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data == null ? Collections.emptyMap() : data;
    }

    public static RpcResult succeeded(String message, Map<String, Object> data) {
        return new RpcResult(RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, message, data);
    }

    public static RpcResult failed(RpcErrorCode errorCode, String message) {
        return new RpcResult(RpcResultStatus.FAILED, errorCode, message, null);
    }

    public static RpcResult rejected(RpcErrorCode errorCode, String message) {
        return new RpcResult(RpcResultStatus.REJECTED, errorCode, message, null);
    }

    static RpcResult expired(String message) {
        return new RpcResult(RpcResultStatus.EXPIRED, RpcErrorCode.EXPIRED, message, null);
    }

    static RpcResult of(RpcResultStatus status, RpcErrorCode errorCode, String message, Map<String, Object> data) {
        return new RpcResult(status, errorCode, message, data);
    }

    public RpcResultStatus getStatus() {
        return status;
    }

    public RpcErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public boolean isTerminal() {
        return status == RpcResultStatus.SUCCEEDED || status == RpcResultStatus.FAILED
                || status == RpcResultStatus.REJECTED || status == RpcResultStatus.EXPIRED;
    }
}
