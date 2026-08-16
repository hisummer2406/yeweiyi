package com.sansen.yeweiyi.logic.inout;

/**
 * 出/入库事件判定阈值。
 *
 * <p>前四个字段是用户在"基础设置"页面配置的业务阈值（对应
 * {@code Singleton.InHight}/{@code InTime}/{@code OutHight}/{@code OutTime}）。
 * 后三个是判定逻辑内部使用的去抖/兜底参数，不对用户暴露：
 * <ul>
 *   <li>{@code noiseTolerance}：两次读数差的绝对值不超过此值视为"未变化"，
 *       用于过滤传感器噪声，避免要求读数完全相等。</li>
 *   <li>{@code settleWindowMillis}：读数进入"未变化"状态后，需要再持续这么久
 *       才确认"真的稳定了"（去抖确认窗口），量级应远小于业务时间阈值。</li>
 *   <li>{@code maxTrackingMillis}：一段跟踪从开始变化起最长可以持续多久；
 *       超过仍未同时满足双阈值就放弃，避免状态无限期悬挂。</li>
 * </ul>
 * 三者的具体取值是占位默认值，真实取值需要结合现场传感器噪声特征和轮询频率
 * 在真机验证阶段确认（见 openspec/changes/rewrite-stock-inout-detection/tasks.md 第6组）。
 */
public class InOutThresholds {

    public static final double DEFAULT_NOISE_TOLERANCE = 1.0;
    public static final long DEFAULT_SETTLE_WINDOW_MILLIS = 3_000L;
    public static final long DEFAULT_MAX_TRACKING_MILLIS = 2L * 60 * 60 * 1000; // 2小时

    /** 入库判定的高度阈值：净高度变化需达到此值才可能判定为入库。 */
    private final double inHeight;
    /** 入库判定的时间阈值（秒）：净变化持续时长需达到此值才可能判定为入库。 */
    private final long inTimeSeconds;
    /** 出库判定的高度阈值：净高度变化需达到此值才可能判定为出库。 */
    private final double outHeight;
    /** 出库判定的时间阈值（秒）：净变化持续时长需达到此值才可能判定为出库。 */
    private final long outTimeSeconds;
    /** 死区容差：两次读数差的绝对值不超过此值视为"未变化"，用于过滤传感器噪声。 */
    private final double noiseTolerance;
    /** 去抖确认窗口（毫秒）：读数进入"未变化"状态后需再持续这么久才确认"真的稳定了"。 */
    private final long settleWindowMillis;
    /**
     * 单段跟踪的最长允许时长（毫秒）：仅在时长本身迟迟凑不够时间阈值时才会用到——
     * 超过此时长仍未凑够时间阈值就放弃，避免状态无限期悬挂。时长一旦达标，是否
     * 继续跟踪就只取决于高度是否够（够则产出事件，不够立即放弃），不会再走到
     * 这个兜底（见 {@link TankLevelTracker}）。
     */
    private final long maxTrackingMillis;

    public InOutThresholds(double inHeight, long inTimeSeconds,
                            double outHeight, long outTimeSeconds,
                            double noiseTolerance, long settleWindowMillis,
                            long maxTrackingMillis) {
        this.inHeight = inHeight;
        this.inTimeSeconds = inTimeSeconds;
        this.outHeight = outHeight;
        this.outTimeSeconds = outTimeSeconds;
        this.noiseTolerance = noiseTolerance;
        this.settleWindowMillis = settleWindowMillis;
        this.maxTrackingMillis = maxTrackingMillis;
    }

    /**
     * 使用业务阈值 + 默认的去抖/兜底参数构造。
     */
    public static InOutThresholds of(double inHeight, long inTimeSeconds,
                                      double outHeight, long outTimeSeconds) {
        return new InOutThresholds(inHeight, inTimeSeconds, outHeight, outTimeSeconds,
                DEFAULT_NOISE_TOLERANCE, DEFAULT_SETTLE_WINDOW_MILLIS, DEFAULT_MAX_TRACKING_MILLIS);
    }

    public double getInHeight() {
        return inHeight;
    }

    public long getInTimeSeconds() {
        return inTimeSeconds;
    }

    public double getOutHeight() {
        return outHeight;
    }

    public long getOutTimeSeconds() {
        return outTimeSeconds;
    }

    public double getNoiseTolerance() {
        return noiseTolerance;
    }

    public long getSettleWindowMillis() {
        return settleWindowMillis;
    }

    public long getMaxTrackingMillis() {
        return maxTrackingMillis;
    }
}
