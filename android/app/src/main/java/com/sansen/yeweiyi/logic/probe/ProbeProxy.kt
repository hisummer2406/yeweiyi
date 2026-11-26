package com.sansen.yeweiyi.logic.probe

import com.sansen.yeweiyi.utils.CRCheck

/**
 * 串口 2 原始应答缓存：串口 4 查询只读缓存，不触发采集或补偿。
 * now 必须返回单调毫秒；采集线程和代理线程共用本对象的锁。
 */
class ProbeProxy(
    private val protocol: Int,
    private val now: () -> Long,
    private val report: (String) -> Unit = {}
) {
    // Modbus 只允许固定范围 0x0002/2；扩展范围时必须把寄存器范围也纳入键。
    private data class Key(val address: Int, val function: Int)
    private data class Sample(val bytes: ByteArray, val receivedAt: Long)
    private data class ValidatedFrame(val key: Key, val bytes: ByteArray)
    private val samples = HashMap<Key, Sample>()
    private val requests = HashMap<Key, Long>()
    private val source = Frames(false)
    private val client = Frames(true)

    init { require(protocol == 0 || protocol == 1) }

    /** 实际发送到探针前登记；只接受匹配窗口内的应答，拒绝无请求来源的报文。 */
    @Synchronized fun onSourceRequest(bytes: ByteArray) {
        val time = now()
        requests.entries.removeAll { time - it.value >= SOURCE_WINDOW_MS }
        val key = requestKey(bytes)
        // Modbus 应答没有寄存器地址；切换到不支持的查询时不能沿用旧请求上下文。
        if (protocol == 1 && key == null) {
            requests.clear()
            source.clear()
        }
        key?.let { requests[it] = time }
    }

    /** 输入可含半帧或多帧；只在收到完整且匹配请求的源应答时更新采样时间。 */
    @Synchronized fun onSourceBytes(bytes: ByteArray) {
        val time = now()
        val accepted = HashSet<Key>()
        for (frame in source.feed(bytes, time)) {
            val key = frame.key
            val sentAt = requests[key] ?: continue
            if (time - sentAt !in 0 until SOURCE_WINDOW_MS) continue
            // 分帧器已复制出独占数组；不再重复校验或复制，返回客户端时另做副本。
            samples[key] = Sample(frame.bytes, time)
            accepted.add(key)
        }
        accepted.forEach { requests.remove(it) }
    }

    /** 最新完整合法查询立即返回字节副本；半帧、缓存缺失或过期返回 null，不排队等采集。 */
    @Synchronized fun reply(bytes: ByteArray): ByteArray? {
        val time = now()
        val frame = client.feed(bytes, time).lastOrNull() ?: return null
        val key = frame.key
        val sample = samples[key]
        if (sample == null) {
            report("cache-miss")
            return null
        }
        if (time - sample.receivedAt !in 0 until lifetime(key)) {
            samples.remove(key)
            report("cache-expired")
            return null
        }
        return sample.bytes.copyOf()
    }

    /** 该探针断线后立即失效；残帧无法可靠归属地址，清掉两路残帧避免跨故障拼接。 */
    @Synchronized fun invalidateAddress(address: Int) {
        samples.keys.removeAll { it.address == address }
        requests.keys.removeAll { it.address == address }
        source.clear()
        client.clear()
    }

    /** 配置/采集状态变化时清空缓存、等待请求和残帧；恢复必须收到新的源应答。 */
    @Synchronized fun clear() {
        samples.clear()
        requests.clear()
        source.clear()
        client.clear()
    }

    /** 温度、长度采集频率低于液位，独立过期；客户端重复查询不会延长有效期。 */
    private fun lifetime(key: Key) = when (key.function) {
        0x45 -> TEMPERATURE_TTL_MS
        0x42 -> LENGTH_TTL_MS
        else -> LEVEL_TTL_MS
    }

    /** 只读查询白名单；先验证长度再取字段，拒绝写命令和其他 Modbus 寄存器范围。 */
    private fun requestKey(b: ByteArray): Key? {
        if (protocol == 0) {
            if (b.size != 9 || !zcHeader(b) || u(b[3]) != 3 || b[5] != 0.toByte() ||
                b[6] != 0.toByte() || u(b[4]) !in ZC_FUNCTIONS || !validCrc(b)) return null
            return Key(u(b[2]), u(b[4]))
        }
        if (b.size != 8 || u(b[0]) !in 1..255 || u(b[1]) != 3 ||
            u(b[2]) != 0 || u(b[3]) != 2 || u(b[4]) != 0 || u(b[5]) != 2 || !validCrc(b)) return null
        return Key(u(b[0]), 3)
    }

    /** 只识别正常应答；Modbus 不携带寄存器地址，范围必须由源请求上下文约束。 */
    private fun responseKey(b: ByteArray): Key? {
        if (protocol == 0) {
            if (b.size < 5 || !zcHeader(b)) return null
            val length = zcResponseLength(u(b[4]))
            if (length == 0 || b.size != length || u(b[3]) != length - 6 || !validCrc(b)) return null
            return Key(u(b[2]), u(b[4]))
        }
        if (b.size != 9 || u(b[0]) !in 1..255 || u(b[1]) != 3 || u(b[2]) != 4 || !validCrc(b)) return null
        return Key(u(b[0]), 3)
    }

    private fun zcHeader(b: ByteArray) = u(b[0]) == 0xFE && u(b[1]) == 0xFE && u(b[2]) in 1..255
    private fun zcResponseLength(function: Int) = when (function) { 0x44 -> 19; 0x45 -> 17; 0x42 -> 14; else -> 0 }
    /** 中成 CRC12 排除 FEFE 且高字节在前；Modbus CRC16 覆盖整段帧体且低字节在前。 */
    private fun validCrc(b: ByteArray): Boolean {
        val expected = if (protocol == 0) {
            val body = b.copyOfRange(2, b.size - 2)
            CRCheck.crc12(body.size, body).toInt(16)
        } else CRCheck.crc16Modbus(b.copyOf(b.size - 2))
        return if (protocol == 0) u(b[b.size - 2]) == expected shr 8 && u(b.last()) == expected and 255
        else u(b[b.size - 2]) == expected and 255 && u(b.last()) == expected shr 8
    }

    /** 两路各自保留残帧，避免源应答与客户端查询互相拼接；调用方持有 ProbeProxy 的锁。 */
    private inner class Frames(private val query: Boolean) {
        private var pending = ByteArray(0)
        private var lastAt = 0L

        fun clear() { pending = ByteArray(0) }

        /** CRC 错误逐字节重新找帧；不足一帧则保留尾部，超时或超限丢弃。 */
        fun feed(bytes: ByteArray, time: Long): List<ValidatedFrame> {
            val timeout = if (query) QUERY_FRAGMENT_MS else SOURCE_WINDOW_MS
            if (pending.isNotEmpty() && time - lastAt !in 0 until timeout) clear()
            if (bytes.isEmpty()) return emptyList()
            lastAt = time
            if (pending.size + bytes.size > MAX_BUFFER) {
                clear()
                report("buffer-overflow")
                return emptyList()
            }
            pending += bytes
            val frames = ArrayList<ValidatedFrame>()
            var start = 0
            while (start < pending.size) {
                val remain = pending.size - start
                val length: Int
                if (protocol == 0) {
                    if (u(pending[start]) != 0xFE) { start++; continue }
                    if (remain < 2) break
                    if (u(pending[start + 1]) != 0xFE) { start++; continue }
                    if (remain < 5) break
                    length = if (query) 9 else zcResponseLength(u(pending[start + 4]))
                    if (length == 0 || u(pending[start + 3]) != length - 6) { start++; continue }
                } else {
                    if (u(pending[start]) == 0) { start++; continue }
                    if (remain < 2) break
                    if (u(pending[start + 1]) != 3) { start++; continue }
                    if (!query && remain < 3) break
                    if (!query && u(pending[start + 2]) != 4) { start++; continue }
                    length = if (query) 8 else 9
                }
                if (remain < length) break
                val frame = pending.copyOfRange(start, start + length)
                val key = if (query) requestKey(frame) else responseKey(frame)
                if (key == null) {
                    report(if (query) "invalid-query" else "invalid-source")
                    start++
                } else {
                    frames.add(ValidatedFrame(key, frame))
                    start += length
                }
            }
            pending = pending.copyOfRange(start, pending.size)
            return frames
        }
    }

    companion object {
        // 30 秒覆盖多探针失联拖慢轮询的情况；故障、停采集等仍提前失效，现场需核实余量。
        const val LEVEL_TTL_MS = 30_000L
        const val TEMPERATURE_TTL_MS = 60_000L
        const val LENGTH_TTL_MS = 600_000L
        // 这是源请求匹配/残帧上限，不是对客户端延迟应答的等待时间。
        private const val SOURCE_WINDOW_MS = 1_000L
        private const val QUERY_FRAGMENT_MS = 100L
        private const val MAX_BUFFER = 2048
        private val ZC_FUNCTIONS = setOf(0x44, 0x45, 0x42)
        private fun u(b: Byte) = b.toInt() and 255
    }
}
