package com.sansen.yeweiyi.logic.inout;

/**
 * 一次已经判定成立的出/入库事件：某个油罐的液位，从 {@code startHeight}
 * 变化到 {@code endHeight}，且已同时满足对应方向的时间/高度阀值。
 * 由 {@link TankLevelTracker} 产出，不可变，方向只在这里判定一次。
 */
public class InOutEvent {

    /** 发生事件的油罐号。 */
    private final int tankId;
    /** 事件方向（入库/出库），由 {@link TankLevelTracker} 判定，仅此一次。 */
    private final InOutDirection direction;
    /** 这段跟踪的起点液位（约等于变化开始前的基准高度）。 */
    private final double startHeight;
    /** 判定成立时刻的液位（稳定后的读数）。 */
    private final double endHeight;
    /** 起点对应的时间戳。 */
    private final long startTimeMillis;
    /** 判定成立时刻对应的时间戳。 */
    private final long endTimeMillis;

    public InOutEvent(int tankId, InOutDirection direction, double startHeight, double endHeight,
                       long startTimeMillis, long endTimeMillis) {
        this.tankId = tankId;
        this.direction = direction;
        this.startHeight = startHeight;
        this.endHeight = endHeight;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
    }

    public int getTankId() {
        return tankId;
    }

    public InOutDirection getDirection() {
        return direction;
    }

    public double getStartHeight() {
        return startHeight;
    }

    public double getEndHeight() {
        return endHeight;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }
}
