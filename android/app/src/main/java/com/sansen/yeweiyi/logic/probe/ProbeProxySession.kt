package com.sansen.yeweiyi.logic.probe

/**
 * 管理代理的采集范围与可用性；未启用代理的旧模式不创建缓存。
 * 请求携带配置代次，停采集或改地址之前发出的应答不能重新填充缓存。
 */
class ProbeProxySession(
    private val mode: Int,
    private val protocol: Int,
    now: () -> Long,
    report: (String) -> Unit = {}
) {
    private val proxy = if (Serial4Mode.isProxy(mode) && Serial4Mode.matches(mode, protocol))
        ProbeProxy(protocol, now, report) else null
    private var available = false
    private var pollingEnabled = true
    private var generation = 0L
    private var addresses: Set<Int> = emptySet()

    /** 输入来自第一台已启用的罐配置；即使地址不变，配置重载也让旧请求失效。 */
    @Synchronized fun configureAddresses(values: IntArray) {
        addresses = values.filter { it in 1..255 }.toSet()
        invalidate()
    }

    /** 由串口采集路径维护；重复报告可用不清缓存，状态切换则丢弃旧数据。 */
    @Synchronized fun setSourceAvailable(value: Boolean) {
        if (available == value) return
        available = value
        invalidate()
    }

    /** 显式暂停独立于串口可用状态，避免正在执行的轮询把暂停状态重新打开。 */
    @Synchronized fun setPollingEnabled(value: Boolean) {
        if (pollingEnabled == value) return
        pollingEnabled = value
        invalidate()
    }

    /** 推进代次并清空全部报文状态；不要在普通重复轮询时调用。 */
    @Synchronized fun invalidate() {
        generation++
        proxy?.clear()
    }

    /** 返回本次采集状态代次；-1 表示未开放采集或地址/协议不匹配，收包不得入缓存。 */
    @Synchronized fun sourceRequest(type: Int, bytes: ByteArray): Long {
        if (proxy == null || !available || !pollingEnabled || type != protocol) return -1
        val index = if (protocol == 0) 2 else 0
        if (bytes.size <= index || (bytes[index].toInt() and 255) !in addresses) return -1
        proxy.onSourceRequest(bytes)
        return generation
    }

    /** token 必须来自发送请求时；重载配置或暂停之后迟到的旧应答直接丢弃。 */
    @Synchronized fun sourceBytes(token: Long, bytes: ByteArray?) {
        if (token < 0 || token != generation || !available || !pollingEnabled) return
        proxy?.onSourceBytes(bytes ?: ByteArray(0))
    }

    /** 单探针故障不清空其他地址的缓存，只失效该地址及无法归属的残帧。 */
    @Synchronized fun probeLost(address: Int) { proxy?.invalidateAddress(address) }

    /** 采集不可用或已暂停时保持静默，让客户端沿用原有超时重试逻辑。 */
    @Synchronized fun reply(bytes: ByteArray): ByteArray? = if (available && pollingEnabled) proxy?.reply(bytes) else null
}
