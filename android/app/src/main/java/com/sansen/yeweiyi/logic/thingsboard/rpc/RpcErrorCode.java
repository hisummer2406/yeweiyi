package com.sansen.yeweiyi.logic.thingsboard.rpc;

/**
 * 统一错误码，配合 {@link RpcResultStatus#REJECTED}/{@link RpcResultStatus#FAILED} 使用。
 * 成功场景（{@code RECEIVED}/{@code EXECUTING}/{@code SUCCEEDED}）用 {@link #NONE}。
 */
public enum RpcErrorCode {
    NONE,
    UNKNOWN_METHOD,
    PROTOCOL_VERSION_UNSUPPORTED,
    MALFORMED_PARAMS,
    INVALID_PARAMS,
    EXPIRED,
    /** 高风险动作（泵/阀等）默认关闭，见 #34 —— 未完成真机联调验证前不开放。 */
    FEATURE_DISABLED,
    INTERNAL_ERROR
}
