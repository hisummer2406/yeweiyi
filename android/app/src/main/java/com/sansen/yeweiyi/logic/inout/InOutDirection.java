package com.sansen.yeweiyi.logic.inout;

/**
 * 出/入库事件的方向。由 {@link TankLevelTracker} 依据净高度变化的符号判定，
 * 只算一次；下游（落库、通知）直接消费该结果，不得重新推导方向。
 */
public enum InOutDirection {
    /** 液位上升——入库 */
    IN,
    /** 液位下降——出库 */
    OUT
}
