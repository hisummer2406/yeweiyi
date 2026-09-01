package com.sansen.yeweiyi.logic.thingsboard;

/**
 * MQTT 断线重连退避策略：翻倍退避，封顶，一次连接成功后清零。
 * <p>
 * 替换旧 {@code BackService} 里"每 60 秒检查一次、断了就立即重连"的固定周期重连——
 * 那种策略在 broker 侧宕机时会造成所有设备同一节奏的重连风暴。这里改成指数退避，
 * 每台设备的退避序列相同但连接失败的时刻天然分散，不会再对齐。
 */
public final class ReconnectBackoff {

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;

    private long currentDelayMillis;

    public ReconnectBackoff(long initialDelayMillis, long maxDelayMillis, double multiplier) {
        if (initialDelayMillis <= 0 || maxDelayMillis <= 0 || multiplier <= 1.0) {
            throw new IllegalArgumentException("非法退避参数: initial=" + initialDelayMillis
                    + " max=" + maxDelayMillis + " multiplier=" + multiplier);
        }
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.currentDelayMillis = initialDelayMillis;
    }

    /** 返回本次应该等待的毫秒数，并把内部状态推进到下一次（翻倍，封顶）。 */
    public long nextDelayMillis() {
        long delay = currentDelayMillis;
        long next = (long) (currentDelayMillis * multiplier);
        currentDelayMillis = Math.min(next, maxDelayMillis);
        return delay;
    }

    /** 连接成功后调用，退避序列回到初始值。 */
    public void reset() {
        currentDelayMillis = initialDelayMillis;
    }
}
