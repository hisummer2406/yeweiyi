package com.sansen.yeweiyi.logic.inout;

/**
 * 单个油罐的出/入库事件判定状态机。纯 Java 实现，不依赖 Android
 * {@code Context} 或任何 Android 单例，可在 JVM 单元测试中直接验证。
 *
 * <p>规则（详见
 * openspec/changes/rewrite-stock-inout-detection/specs/stock/inout-event-detection/spec.md）：
 * <ol>
 *   <li>液位偏离基准超过死区容差（{@link InOutThresholds#getNoiseTolerance()}）时开始跟踪；</li>
 *   <li>液位在死区容差内保持不变、且已持续超过去抖窗口（{@link InOutThresholds#getSettleWindowMillis()}）
 *       后，才认为"真的稳定了"，进入阀值判定——下面两步判定（时长、高度）都只在
 *       这个"已经确认稳定"的前提下发生，不会拿一次尚未确认稳定的读数去比阈值；</li>
 *   <li>阀值判定分两步、**时长先行**：先看"从开始变化到当前"的时长是否达到
 *       对应方向的时间阈值——时长不够时，不论这个已确认稳定的净高度变化是否
 *       已经够了，都继续跟踪等待，不清空已跟踪的起点（时长是相对"当前时刻"
 *       计算的，只要液位保持在稳定值不变，时长会随真实时钟推进自然增长，
 *       直到追上时间阈值）；</li>
 *   <li>时长一旦达标，才检查这个"已确认稳定"的净高度变化是否也达到对应方向的
 *       高度阀值：够了才产出事件；不够则直接放弃这段跟踪、以当前稳定值重新
 *       建立基准——净变化量不会再随时间推移自己变大，继续等待没有意义（这也是
 *       "一段跟踪内先涨后跌、来回穿越阈值，但等到液位稳定、时长也达标后，净
 *       变化仍被抵消到不够阈值"这种场景，会在满足"稳定+时长达标"的那一刻就被
 *       了结、不会无限期挂起、也不会被之后不相关的小波动接续计算的原因）；</li>
 *   <li>时长未达标、继续等待期间液位又发生真正的变化，视为同一段事件的延续
 *       （暂停后恢复），不重新起算起点；</li>
 *   <li>一段跟踪若从开始变化起持续超过 {@link InOutThresholds#getMaxTrackingMillis()}
 *       仍未凑够时间阈值（时长阈值本身配置得过大等异常情况），判定为异常/长期
 *       悬空，放弃并以当前液位重新建立基准。</li>
 * </ol>
 */
public class TankLevelTracker {

    private enum State {
        /** 空闲：液位在基准附近，未在跟踪一段变化。 */
        IDLE,
        /** 跟踪中：已偏离基准，正在观察这段变化能否发展成一次出/入库事件。 */
        TRACKING
    }

    /** 本跟踪器所属的油罐号，仅用于产出 {@link InOutEvent} 时标注来源。 */
    private final int tankId;

    /** 当前所处状态，见 {@link State}。 */
    private State state = State.IDLE;
    /** 是否启用自动检测；为 false 时 {@link #onReading} 直接忽略读数。 */
    private boolean enabled = true;
    /** {@link #baselineHeight} 是否已经被首条读数初始化过（避免用 0 作哨兵值）。 */
    private boolean baselineSet = false;

    /** IDLE 态下的当前基准液位；小于死区容差的读数会让它跟着漂移。 */
    private double baselineHeight;
    /** 当前这段跟踪的起点高度，只有产出事件或放弃时才会移动。 */
    private double trackStartHeight;
    /** 当前这段跟踪的起点时间，用于计算相对"当前时刻"的持续时长。 */
    private long trackStartTime;
    /** 最近一次被确认为"仍在变化"的读数值，用于判断后续读数是否已进入稳定区间。 */
    private double lastHeight;
    /** 最近一次被确认为"仍在变化"的读数时间，用于计算是否已越过去抖窗口。 */
    private long lastChangeTime;

    public TankLevelTracker(int tankId) {
        this.tankId = tankId;
    }

    /**
     * 暂停/恢复自动检测。恢复时清空跟踪状态，下一次读数重新建立基准——
     * 对应 {@code InOilFragment} 人工卸油流程里 setEnable(false)/(true) 的语义。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            state = State.IDLE;
            baselineSet = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 是否处于"正在跟踪一段液位变化"状态（尚未产出事件也未被放弃）。
     */
    public boolean isTracking() {
        return state == State.TRACKING;
    }

    /**
     * 喂入一条新的液位读数。返回本次读数触发的出/入库事件，没有则返回 {@code null}。
     */
    public InOutEvent onReading(double height, long timestampMillis, InOutThresholds thresholds) {
        if (!enabled) {
            return null;
        }

        if (state == State.IDLE) {
            return onReadingWhileIdle(height, timestampMillis, thresholds);
        }
        return onReadingWhileTracking(height, timestampMillis, thresholds);
    }

    private InOutEvent onReadingWhileIdle(double height, long timestampMillis, InOutThresholds thresholds) {
        if (!baselineSet) {
            baselineHeight = height;
            baselineSet = true;
            return null;
        }
        if (Math.abs(height - baselineHeight) > thresholds.getNoiseTolerance()) {
            state = State.TRACKING;
            trackStartHeight = baselineHeight;
            trackStartTime = timestampMillis;
            lastHeight = height;
            lastChangeTime = timestampMillis;
        } else {
            baselineHeight = height;
        }
        return null;
    }

    private InOutEvent onReadingWhileTracking(double height, long timestampMillis, InOutThresholds thresholds) {
        if (Math.abs(height - lastHeight) > thresholds.getNoiseTolerance()) {
            // 仍在变化（含"稳定等待期间又恢复变化"的情况）：延续同一段跟踪，
            // 起点（trackStartHeight/trackStartTime）不变。
            lastHeight = height;
            lastChangeTime = timestampMillis;
            return null;
        }

        long stableFor = timestampMillis - lastChangeTime;
        if (stableFor < thresholds.getSettleWindowMillis()) {
            // 读数刚进入不变区间，还不能确认是真的稳定了，继续等待。
            return null;
        }

        double delta = lastHeight - trackStartHeight;
        if (delta == 0) {
            // 净变化为 0 理论上不会真正发生（进入 TRACKING 的前提就是偏离基准
            // 超过容差），防御性地当作无事件处理，以当前高度重新建立基准。
            resetTo(lastHeight);
            return null;
        }

        InOutDirection direction = delta > 0 ? InOutDirection.IN : InOutDirection.OUT;
        double heightThreshold = direction == InOutDirection.IN ? thresholds.getInHeight() : thresholds.getOutHeight();
        long timeThreshold = direction == InOutDirection.IN ? thresholds.getInTimeSeconds() : thresholds.getOutTimeSeconds();

        // 时长先行判断：时长相对"当前读数时刻"计算，而不是相对最后一次变化的
        // 时刻——只要液位保持在稳定值不变，真实时钟推进也会让这个值自然增长，
        // 所以时长不够时，不论高度是否已经够了，都值得继续等待（万一是"高度已
        // 够、时长还没追上"，等一等就会产出事件）。
        long durationSeconds = (timestampMillis - trackStartTime) / 1000;
        if (durationSeconds < timeThreshold) {
            if (timestampMillis - trackStartTime > thresholds.getMaxTrackingMillis()) {
                // 时长阈值本身配置得过大、迟迟凑不够：兜底放弃这段跟踪。
                resetTo(lastHeight);
            }
            return null;
        }

        // 走到这里说明三个条件已经同时成立：①液位已经过去抖窗口确认真正稳定
        // （前面两个 return 已经把"还没稳定"的情况挡掉了）；②时长也已经达标；
        // 这时候才用这个已确认稳定的净变化量 delta，检查③高度是否也够。
        if (Math.abs(delta) >= heightThreshold) {
            InOutEvent event = new InOutEvent(tankId, direction, trackStartHeight, lastHeight,
                    trackStartTime, timestampMillis);
            resetTo(lastHeight);
            return event;
        }

        // 时长已经够了，但净变化量还是不够：不会再随时间推移自己变大（除非液位
        // 之后又真的发生新的变化，那种情况会在下一次读数时被当成全新一段重新
        // 处理），直接放弃这段跟踪，以当前稳定值重新建立基准——覆盖"一段跟踪内
        // 先涨后跌、来回穿越阈值，但等够了时长后净变化仍被抵消到不够阈值"的场景，
        // 避免占着很久以前的起点不放。
        resetTo(lastHeight);
        return null;
    }

    private void resetTo(double height) {
        state = State.IDLE;
        baselineSet = true;
        baselineHeight = height;
    }
}
