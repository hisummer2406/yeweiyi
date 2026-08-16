package com.sansen.yeweiyi.logic.inout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * 覆盖 openspec/changes/rewrite-stock-inout-detection/specs/stock/inout-event-detection/spec.md
 * 里列出的判定场景。纯 JVM 运行，不依赖 Android。
 */
public class TankLevelTrackerTest {

    private static final int TANK_ID = 1;
    /** 高度阀值100、时间阈值60秒、死区容差1、去抖窗口5秒、最长跟踪1分钟（测试用，比业务默认值短，便于覆盖兜底分支）。 */
    private static final InOutThresholds THRESHOLDS =
            new InOutThresholds(100, 60, 100, 60, 1.0, 5_000L, 60_000L);

    private TankLevelTracker tracker;

    @Before
    public void setUp() {
        tracker = new TankLevelTracker(TANK_ID);
    }

    @Test
    public void firstReading_onlyEstablishesBaseline_noEvent() {
        InOutEvent event = tracker.onReading(1000, 0L, THRESHOLDS);
        assertNull(event);
        assertFalse(tracker.isTracking());
    }

    @Test
    public void bothThresholdsSatisfied_producesInEvent() {
        tracker.onReading(0, 0L, THRESHOLDS); // baseline
        assertNull(tracker.onReading(200, 1_000L, THRESHOLDS)); // 开始变化
        assertTrue(tracker.isTracking());
        assertNull(tracker.onReading(200, 7_000L, THRESHOLDS)); // 稳定但时长(6s)不够60s

        InOutEvent event = tracker.onReading(200, 61_000L, THRESHOLDS); // 时长追上60s
        assertNotNull(event);
        assertEquals(InOutDirection.IN, event.getDirection());
        assertEquals(0, event.getStartHeight(), 0.0001);
        assertEquals(200, event.getEndHeight(), 0.0001);
        assertEquals(1_000L, event.getStartTimeMillis());
        assertEquals(61_000L, event.getEndTimeMillis());
        assertFalse("产出事件后应复位为空闲态", tracker.isTracking());
    }

    @Test
    public void bothThresholdsSatisfied_producesOutEvent() {
        tracker.onReading(500, 0L, THRESHOLDS);
        tracker.onReading(300, 1_000L, THRESHOLDS); // 下降 200
        tracker.onReading(300, 7_000L, THRESHOLDS);

        InOutEvent event = tracker.onReading(300, 61_000L, THRESHOLDS);
        assertNotNull(event);
        assertEquals(InOutDirection.OUT, event.getDirection());
        assertEquals(500, event.getStartHeight(), 0.0001);
        assertEquals(300, event.getEndHeight(), 0.0001);
    }

    @Test
    public void heightSatisfied_timeNotYet_keepsWaitingInsteadOfDiscarding() {
        // 这是修复前"根因 bug"对应的场景：稳定后第一次检查未达标不应清零，
        // 而应继续观察，等时长自然追上再产出事件。
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.onReading(200, 1_000L, THRESHOLDS); // 高度差200，已超过阀值100

        // 第一次稳定检查：时长只有 6s，远小于 60s 阈值
        assertNull(tracker.onReading(200, 7_000L, THRESHOLDS));
        assertTrue("未同时满足阈值时应继续跟踪，而不是被清零", tracker.isTracking());

        // 后续多次相同高度的读数，时长逐渐逼近但仍不够
        assertNull(tracker.onReading(200, 30_000L, THRESHOLDS));
        assertTrue(tracker.isTracking());

        // 时长终于追上 60s 阈值，应产出事件，且高度仍是最初记录的起点
        InOutEvent event = tracker.onReading(200, 61_000L, THRESHOLDS);
        assertNotNull(event);
        assertEquals(0, event.getStartHeight(), 0.0001);
        assertEquals(200, event.getEndHeight(), 0.0001);
    }

    @Test
    public void timeSatisfied_heightNotYet_neverFiresAndEventuallyGivesUp() {
        // 单独放宽 maxTrackingMillis，与"时间阈值已够但高度不够"的检查点错开，
        // 避免和 60s 的时间阈值取同一个数字混淆。
        InOutThresholds thresholds = new InOutThresholds(100, 60, 100, 60, 1.0, 5_000L, 120_000L);
        tracker.onReading(0, 0L, thresholds);
        tracker.onReading(20, 1_000L, thresholds); // 高度差只有20，远小于阀值100

        // 时长(69s)早已超过60s时间阈值，但高度差不够，不应产出事件
        assertNull(tracker.onReading(20, 70_000L, thresholds));
        assertTrue(tracker.isTracking());

        // 超过最长跟踪时长(120s)后应放弃这段跟踪
        InOutEvent event = tracker.onReading(20, 125_000L, thresholds);
        assertNull(event);
        assertFalse("超过最长跟踪时长应兜底放弃，不再无限期悬挂", tracker.isTracking());
    }

    @Test
    public void noiseWithinTolerance_doesNotResetOrCountAsChange() {
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.onReading(200, 1_000L, THRESHOLDS);
        // 后续读数在死区容差(1.0)内抖动，不应被当作"仍在变化"，也不应打断跟踪
        assertNull(tracker.onReading(200.4, 2_000L, THRESHOLDS));
        assertNull(tracker.onReading(199.7, 3_000L, THRESHOLDS));
        assertTrue(tracker.isTracking());

        InOutEvent event = tracker.onReading(200.2, 62_000L, THRESHOLDS);
        assertNotNull("噪声抖动不应阻止最终判定成立", event);
        assertEquals(InOutDirection.IN, event.getDirection());
    }

    @Test
    public void resumesChangingWhileWaiting_continuesSameSegmentNotNewOne() {
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.onReading(150, 1_000L, THRESHOLDS); // 第一段变化，净差150已超高度阈值

        // 稳定但时长不够，进入"继续观察"状态
        assertNull(tracker.onReading(150, 7_000L, THRESHOLDS));
        assertTrue(tracker.isTracking());

        // 恢复流动（暂停后继续），应视为同一段事件的延续，不重新起算起点
        assertNull(tracker.onReading(250, 8_000L, THRESHOLDS));
        assertTrue(tracker.isTracking());

        assertNull(tracker.onReading(250, 13_000L, THRESHOLDS)); // 再次稳定，等待时长

        InOutEvent event = tracker.onReading(250, 62_000L, THRESHOLDS);
        assertNotNull(event);
        // 起点仍是最初的 0（不是恢复流动时的 150），说明延续了同一段跟踪
        assertEquals(0, event.getStartHeight(), 0.0001);
        assertEquals(250, event.getEndHeight(), 0.0001);
        assertEquals(1_000L, event.getStartTimeMillis());
    }

    @Test
    public void afterEvent_startsFreshBaselineAtEndHeight() {
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.onReading(200, 1_000L, THRESHOLDS);
        tracker.onReading(200, 7_000L, THRESHOLDS);
        InOutEvent first = tracker.onReading(200, 61_000L, THRESHOLDS);
        assertNotNull(first);

        // 新基准应为上次事件的结束高度，微小抖动不应立刻触发新一段跟踪
        assertNull(tracker.onReading(200.5, 61_100L, THRESHOLDS));
        assertFalse(tracker.isTracking());
    }

    @Test
    public void setEnabledFalse_ignoresReadings() {
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.setEnabled(false);
        assertNull(tracker.onReading(500, 1_000L, THRESHOLDS));
        assertFalse(tracker.isTracking());
    }

    @Test
    public void setEnabledTrueAfterFalse_resetsAndStartsFreshBaseline() {
        tracker.onReading(0, 0L, THRESHOLDS);
        tracker.onReading(200, 1_000L, THRESHOLDS); // 开始跟踪
        assertTrue(tracker.isTracking());

        tracker.setEnabled(false);
        tracker.setEnabled(true);
        assertFalse("重新启用后应清空跟踪状态", tracker.isTracking());

        // 重新启用后的第一条读数只建立新基准，不会立刻当成变化
        InOutEvent event = tracker.onReading(200, 2_000L, THRESHOLDS);
        assertNull(event);
    }
}
