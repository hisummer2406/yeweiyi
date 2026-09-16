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
    EXPIRED;

    /**
     * 跟 {@code yeweiyi-cloud} 侧 {@code internal/protocol.CmdStatus*} 常量对齐（issue #39）：
     * 小写字符串，不是 Java 枚举名（{@link #name()} 是大写的 {@code "RECEIVED"} 等）。
     */
    public String wireValue() {
        switch (this) {
            case RECEIVED:
                return "received";
            case REJECTED:
                return "rejected";
            case EXECUTING:
                return "executing";
            case SUCCEEDED:
                return "succeeded";
            case FAILED:
                return "failed";
            case EXPIRED:
                return "expired";
            default:
                throw new IllegalStateException("未覆盖的 RpcResultStatus: " + this);
        }
    }
}
