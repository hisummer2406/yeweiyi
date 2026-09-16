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
    INTERNAL_ERROR,
    /** 设备端联锁拒绝（例如低液位禁止启泵）——预留，issue #34 联锁补齐前没有产生它的真实逻辑。 */
    INTERLOCK_BLOCKED,
    /** 另一条命令正在执行——预留，目前没有并发执行限制逻辑会产生它。 */
    BUSY,
    /** 本机型无此硬件——预留。 */
    NOT_SUPPORTED,
    /** 执行中硬件故障——预留。 */
    HARDWARE_FAULT,
    /** 设备处于现场手动模式，拒绝远程控制——预留，见 #34。 */
    LOCAL_MODE_LOCKED
}
