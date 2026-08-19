package com.sansen.yeweiyi.logic.inout;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 验证多罐场景下各罐的跟踪状态完全独立——替代原先 InOilManager.setOil() 里
 * 跨罐共享的静态节流/轮转逻辑（current/RecordTime/isRecord[]）之后，
 * 不应再出现"一个罐的读数影响另一个罐判定节奏"的情况。
 */
public class MultiTankInOutDetectorTest {

    private static final InOutThresholds THRESHOLDS =
            new InOutThresholds(100, 60, 100, 60, 1.0, 5_000L, 3_600_000L);

    @Test
    public void tanksAreTrackedIndependently() {
        MultiTankInOutDetector detector = new MultiTankInOutDetector();

        detector.onReading(1, 0, 0L, THRESHOLDS);
        detector.onReading(2, 1000, 0L, THRESHOLDS);

        // 罐1开始变化并进入跟踪
        assertNull(detector.onReading(1, 200, 1_000L, THRESHOLDS));
        assertTrue(detector.isTracking(1));
        // 罐2此时读数没有变化，不应因为罐1的活动而被带入跟踪状态
        assertNull(detector.onReading(2, 1000, 1_000L, THRESHOLDS));
        assertFalse(detector.isTracking(2));

        // 罐1稳定并最终达标产出事件，不影响罐2
        detector.onReading(1, 200, 7_000L, THRESHOLDS);
        InOutEvent event = detector.onReading(1, 200, 61_000L, THRESHOLDS);
        assertNotNull(event);
        assertFalse("罐1产出事件后应复位", detector.isTracking(1));
        assertFalse("罐2全程不应被带入跟踪状态", detector.isTracking(2));
    }

    @Test
    public void setEnabledOnlyAffectsThatTank() {
        MultiTankInOutDetector detector = new MultiTankInOutDetector();
        detector.onReading(1, 0, 0L, THRESHOLDS);
        detector.onReading(2, 0, 0L, THRESHOLDS);

        detector.setEnabled(1, false);

        assertNull(detector.onReading(1, 500, 1_000L, THRESHOLDS));
        assertFalse("罐1被禁用，读数应被忽略", detector.isTracking(1));

        assertNull(detector.onReading(2, 500, 1_000L, THRESHOLDS));
        assertTrue("罐2未受影响，应正常开始跟踪", detector.isTracking(2));
    }

    @Test
    public void unknownTank_isTrackingReturnsFalse() {
        MultiTankInOutDetector detector = new MultiTankInOutDetector();
        assertFalse(detector.isTracking(99));
    }
}
