package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 跨仓库设备协议版本号。随 telemetry 快照和 RPC 请求/响应一起携带，
 * 用于 {@code yeweiyi-cloud} 侧判断是否需要按旧版本兼容解析。
 * <p>
 * yeweiyi-cloud#7（跨仓库协议 V1）截至本次改动仍未冻结、也没有发布可复用的 fixture ——
 * 该仓库最新决策（2026-09-01）甚至转向了 ThingsBoard 仅管身份/OTA、业务走自建 EMQX 的方案，
 * 与本仓库这次实现的"Android 只连 ThingsBoard 原生协议"前提不一致。这里的字段名、payload
 * 形状均为 Android 侧按 ThingsBoard 官方设备 MQTT API 惯例自行定义，协议冻结后如与云端点位
 * 对齐结果不同，需要回来改这个版本号和下面的 payload builder。
 */
public final class ProtocolVersion {

    public static final String CURRENT = "tb-v1";

    private ProtocolVersion() {
    }
}
