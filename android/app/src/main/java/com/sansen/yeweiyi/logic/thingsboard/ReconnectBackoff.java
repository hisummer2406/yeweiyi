package com.sansen.yeweiyi.logic.thingsboard;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MQTT 断线重连退避策略：翻倍退避，封顶，一次连接成功后清零，叠加全抖动（full jitter）。
 * <p>
 * 替换旧 {@code BackService} 里"每 60 秒检查一次、断了就立即重连"的固定周期重连——
 * 那种策略在 broker 侧宕机时会造成所有设备同一节奏的重连风暴。这里改成指数退避；
 * 但纯指数退避本身不够——broker 重启/断网这类事件是同一时刻打断所有设备的连接，
 * 各设备退避序列的翻倍节奏完全一致，即使起始时刻略有差异，重连尝试也会持续对齐，
 * 无法真正打散（PR #38 Codex 第三轮审核 P2）。每次返回值在 {@code [0, 指数退避封顶值]}
 * 区间内均匀取随机数（AWS 架构博客里的 "full jitter"），退避的上限序列还是原来的翻倍/封顶，
 * 只是不再确定性地返回封顶本身。
 */
public final class ReconnectBackoff {

    /** 供测试注入固定抖动，验证退避上限序列本身；真机用 {@link #FULL_JITTER}。 */
    interface JitterFunction {
        long applyJitter(long upperBoundInclusive);
    }

    static final JitterFunction NO_JITTER = upperBoundInclusive -> upperBoundInclusive;

    private static final JitterFunction FULL_JITTER = upperBoundInclusive -> upperBoundInclusive <= 0
            ? 0
            : ThreadLocalRandom.current().nextLong(upperBoundInclusive + 1);

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;
    private final JitterFunction jitterFunction;

    private long currentDelayMillis;

    public ReconnectBackoff(long initialDelayMillis, long maxDelayMillis, double multiplier) {
        this(initialDelayMillis, maxDelayMillis, multiplier, FULL_JITTER);
    }

    ReconnectBackoff(long initialDelayMillis, long maxDelayMillis, double multiplier, JitterFunction jitterFunction) {
        if (initialDelayMillis <= 0 || maxDelayMillis <= 0 || multiplier <= 1.0) {
            throw new IllegalArgumentException("非法退避参数: initial=" + initialDelayMillis
                    + " max=" + maxDelayMillis + " multiplier=" + multiplier);
        }
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.jitterFunction = jitterFunction;
        this.currentDelayMillis = initialDelayMillis;
    }

    /**
     * 返回本次应该等待的毫秒数（指数退避上限值经过抖动后的结果），并把内部的退避上限
     * 推进到下一次（翻倍，封顶）——抖动只作用于返回值，不影响上限序列本身的推进，
     * 否则一次很小的随机值会让后续退避提前封顶或提前重置。
     */
    public long nextDelayMillis() {
        long upperBound = currentDelayMillis;
        long next = (long) (currentDelayMillis * multiplier);
        currentDelayMillis = Math.min(next, maxDelayMillis);
        return jitterFunction.applyJitter(upperBound);
    }

    /** 连接成功后调用，退避序列回到初始值。 */
    public void reset() {
        currentDelayMillis = initialDelayMillis;
    }
}
