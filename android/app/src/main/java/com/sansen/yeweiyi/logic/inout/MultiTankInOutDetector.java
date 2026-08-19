package com.sansen.yeweiyi.logic.inout;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理多个油罐各自独立的 {@link TankLevelTracker}。
 *
 * <p>用 {@code Map<Integer, TankLevelTracker>} 按罐号懒创建，取代原先
 * {@code InOilManager} 里按 id 索引的 16 长度定长数组，也不再需要跨罐
 * 共享的静态节流/轮转状态——每个罐的跟踪状态完全独立，互不影响。
 */
public class MultiTankInOutDetector {

    /** 按罐号懒创建的独立跟踪器；每个罐的状态完全隔离，互不影响。 */
    private final Map<Integer, TankLevelTracker> trackers = new HashMap<>();

    /**
     * 喂入某个罐的一条新液位读数。返回本次读数触发的出/入库事件，没有则返回 {@code null}。
     */
    public InOutEvent onReading(int tankId, double height, long timestampMillis, InOutThresholds thresholds) {
        return tracker(tankId).onReading(height, timestampMillis, thresholds);
    }

    /**
     * 暂停/恢复某个罐的自动检测，见 {@link TankLevelTracker#setEnabled(boolean)}。
     */
    public void setEnabled(int tankId, boolean enabled) {
        tracker(tankId).setEnabled(enabled);
    }

    /**
     * 某个罐当前是否处于"正在跟踪一段液位变化"状态。
     */
    public boolean isTracking(int tankId) {
        TankLevelTracker tracker = trackers.get(tankId);
        return tracker != null && tracker.isTracking();
    }

    private synchronized TankLevelTracker tracker(int tankId) {
        TankLevelTracker tracker = trackers.get(tankId);
        if (tracker == null) {
            tracker = new TankLevelTracker(tankId);
            trackers.put(tankId, tracker);
        }
        return tracker;
    }
}
