package com.sansen.yeweiyi.logic.probe

import java.io.ByteArrayOutputStream

/**
 * 探针口发完请求后的自适应收包（issue #8）：串口是 VTIME=0/VMIN=0 的非阻塞快照读，
 * 原先固定睡一段再读一次，应答慢于这段时间就读空、字节拖到下一次与下一条应答粘连。
 * 改为每 [pollMs] 读一次累积字节：收到过字节后连续 [idlePolls] 次无新字节即认为一帧收完；
 * 始终没有字节或一直有字节，到 [maxWaitMs] 为止。
 */
class AdaptiveReceive(
    private val read: () -> ByteArray?,
    private val sleep: (Long) -> Unit,
    private val nowMs: () -> Long,
    private val pollMs: Long = POLL_MS,
    private val idlePolls: Int = IDLE_POLLS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {

    /** 返回本次累积到的全部字节；一个字节都没收到返回 null（与原 receiveData 一致）。 */
    fun receive(): ByteArray? {
        val buf = ByteArrayOutputStream()
        val start = nowMs()
        var idle = 0
        while (true) {
            sleep(pollMs)
            val chunk = read()
            if (chunk != null && chunk.isNotEmpty()) {
                buf.write(chunk, 0, chunk.size)
                idle = 0
            } else if (buf.size() > 0) {
                idle++
                if (idle >= idlePolls) break
            }
            if (nowMs() - start >= maxWaitMs) break
        }
        return if (buf.size() > 0) buf.toByteArray() else null
    }

    companion object {
        const val POLL_MS = 8L
        /** 9600 波特每字节约 1ms，连续发送的一帧内部不会有 24ms 空档。 */
        const val IDLE_POLLS = 3
        /** 原固定等待 100ms；上限放宽到 200ms，给慢探针（实测 02 号罐）留余量。 */
        const val MAX_WAIT_MS = 200L
    }
}
