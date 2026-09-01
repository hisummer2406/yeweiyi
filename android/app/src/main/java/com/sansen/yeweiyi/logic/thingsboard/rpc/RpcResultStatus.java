package com.sansen.yeweiyi.logic.thingsboard.rpc;

/**
 * 命令 ACK 状态机。设备侧只负责这六个终态/过程态里的一部分——
 * {@code pending}/{@code sent} 是云端在命令真正送达设备之前的状态，设备侧永远不会
 * 产出这两个值，见 issue #32 的完整状态列表与 issue #35 "全过程 ACK" 的范围。
 */
public enum RpcResultStatus {
    /** 收到命令，通过了协议版本/有效期/幂等校验，正式接受处理。 */
    RECEIVED,
    /** 未知方法、协议版本不支持、参数校验失败、高风险功能未开放等——不会执行。 */
    REJECTED,
    /** 正在执行（仅长耗时动作需要这个中间态；参考实现的同步方法可以跳过它直接到终态）。 */
    EXECUTING,
    SUCCEEDED,
    FAILED,
    /** 收到命令时已经超过 expireAt。 */
    EXPIRED
}
