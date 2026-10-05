package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.OilMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActiveAlarmTrackerTest {

    /** 内存版持久化，记录写入次数，模拟进程重启时把同一份数据交给新的 tracker。 */
    static final class MemoryPersistence implements ActiveAlarmTracker.Persistence {
        List<ActiveAlarmTracker.Entry> stored = new ArrayList<>();
        int saves;

        @Override
        public List<ActiveAlarmTracker.Entry> loadAll() {
            return new ArrayList<>(stored);
        }

        @Override
        public void saveAll(List<ActiveAlarmTracker.Entry> entries) {
            stored = new ArrayList<>(entries);
            saves++;
        }
    }

    private static final int HIGH = OilMessage.HEIGHT_OIL_ALARM;
    private static final int HH = OilMessage.HHEIGHT_OIL_ALARM;
    private static final int PROBE = OilMessage.PRO_ERR_CONN;

    @Test
    public void raiseOnceThenDeduplicateUntilRecovered() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        ActiveAlarmTracker.Transition first = tracker.raise(1, PROBE, 0, "1号罐探头断线", 1000L);
        assertNotNull(first);
        assertFalse(first.recovered);
        assertEquals(1000L, first.tsMs);

        // 探头断线每次轮询都会经过 addAlarmState，已经 ACTIVE 时不重复上报
        assertNull(tracker.raise(1, PROBE, 0, "1号罐探头断线", 4000L));
        assertNull(tracker.raise(1, PROBE, 0, "1号罐探头断线", 7000L));
    }

    @Test
    public void recoverOnlyWhenActive() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        // 本地"条件不成立"分支每次轮询都会走，没有 ACTIVE 时不能发 CLEARED
        assertNull(tracker.recover(1, HIGH, 1200.0, 1000L));

        ActiveAlarmTracker.Transition raised = tracker.raise(1, HIGH, 1600.0, "1号罐高液位报警", 2000L);
        ActiveAlarmTracker.Transition cleared = tracker.recover(1, HIGH, 1400.0, 3000L);

        assertNotNull(cleared);
        assertTrue(cleared.recovered);
        assertEquals(raised.entry.alarmId, cleared.entry.alarmId);
        assertEquals("CLEARED 复用 ACTIVE 时的文案", "1号罐高液位报警", cleared.entry.text);
        assertEquals(1400.0, cleared.value, 0.0);
        assertNull("已经恢复过一次，不能再发", tracker.recover(1, HIGH, 1300.0, 4000L));
    }

    @Test
    public void eachOccurrenceGetsNewAlarmId() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        String first = tracker.raise(1, HIGH, 1600.0, "t", 1000L).entry.alarmId;
        tracker.recover(1, HIGH, 1400.0, 2000L);
        String second = tracker.raise(1, HIGH, 1610.0, "t", 3000L).entry.alarmId;

        assertNotEquals(first, second);
    }

    /** 跟本地逻辑一致：先报高、再报高高，两者同时 ACTIVE，各自恢复。 */
    @Test
    public void highAndHighHighCanBeActiveAtTheSameTime() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        assertNotNull(tracker.raise(1, HIGH, 1600.0, "高", 1000L));
        assertNotNull(tracker.raise(1, HH, 1900.0, "高高", 2000L));

        assertNotNull("液位降到高高以下：只有高高恢复", tracker.recover(1, HH, 1700.0, 3000L));
        assertEquals(ActiveAlarmTracker.STATE_ALARM, tracker.alarmState(1));
        assertNotNull(tracker.recover(1, HIGH, 1400.0, 4000L));
        assertEquals(ActiveAlarmTracker.STATE_NORMAL, tracker.alarmState(1));
    }

    @Test
    public void tanksAreIndependent() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        tracker.raise(1, HIGH, 1600.0, "t", 1000L);
        assertNull("2 号罐没有 ACTIVE", tracker.recover(2, HIGH, 1400.0, 2000L));
        assertEquals(ActiveAlarmTracker.STATE_ALARM, tracker.alarmState(1));
        assertEquals(ActiveAlarmTracker.STATE_NORMAL, tracker.alarmState(2));
    }

    @Test
    public void alarmStateFaultWinsOverAlarm() {
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new MemoryPersistence());

        tracker.raise(1, HIGH, 1600.0, "t", 1000L);
        tracker.raise(1, PROBE, 0, "t", 2000L);
        assertEquals(ActiveAlarmTracker.STATE_FAULT, tracker.alarmState(1));

        tracker.recover(1, PROBE, 0, 3000L);
        assertEquals(ActiveAlarmTracker.STATE_ALARM, tracker.alarmState(1));
    }

    @Test
    public void unreportableTypesAreIgnored() {
        MemoryPersistence persistence = new MemoryPersistence();
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(persistence);

        assertNull(tracker.raise(1, OilMessage.LOW_TEMPER, -5.0, "t", 1000L));
        assertNull(tracker.raise(1, OilMessage.IN_OIL, 0, "t", 1000L));
        assertEquals(ActiveAlarmTracker.STATE_NORMAL, tracker.alarmState(1));
        assertEquals(0, persistence.saves);
    }

    /** 进程重启后读回 ACTIVE 集合：液位恢复时仍用原 alarmId 补发 CLEARED，不重复发 ACTIVE。 */
    @Test
    public void activeAlarmsSurviveRestart() {
        MemoryPersistence persistence = new MemoryPersistence();
        ActiveAlarmTracker beforeRestart = new ActiveAlarmTracker(persistence);
        String alarmId = beforeRestart.raise(1, HIGH, 1600.0, "1号罐高液位报警", 1000L).entry.alarmId;

        ActiveAlarmTracker afterRestart = new ActiveAlarmTracker(persistence);
        assertEquals(ActiveAlarmTracker.STATE_ALARM, afterRestart.alarmState(1));
        assertNull("重启后仍在报警，不重复发 ACTIVE", afterRestart.raise(1, HIGH, 1610.0, "t", 5000L));

        ActiveAlarmTracker.Transition cleared = afterRestart.recover(1, HIGH, 1400.0, 6000L);
        assertEquals(alarmId, cleared.entry.alarmId);
        assertEquals("1号罐高液位报警", cleared.entry.text);
        assertTrue(persistence.stored.isEmpty());
    }

    /** review 第 3 条：被删除或停用的罐不再被轮询，它们的报警要在配置重新加载时恢复并清除。 */
    @Test
    public void recoverTanksNotInClearsAlarmsOfRemovedTanks() {
        MemoryPersistence persistence = new MemoryPersistence();
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(persistence);
        String tank1Id = tracker.raise(1, HIGH, 1600.0, "1号罐", 1000L).entry.alarmId;
        String tank3Id = tracker.raise(3, PROBE, 0, "3号罐", 2000L).entry.alarmId;

        List<ActiveAlarmTracker.Transition> cleared =
                tracker.recoverTanksNotIn(new java.util.HashSet<>(java.util.Arrays.asList(1, 2)), 5000L);

        assertEquals(1, cleared.size());
        assertTrue(cleared.get(0).recovered);
        assertEquals(tank3Id, cleared.get(0).entry.alarmId);
        assertEquals(ActiveAlarmTracker.STATE_NORMAL, tracker.alarmState(3));
        assertEquals("仍启用的罐不受影响", tank1Id, persistence.stored.get(0).alarmId);
        assertNotNull("罐号被新罐沿用后，它的报警能正常产生，不会被当成重复",
                tracker.raise(3, PROBE, 0, "3号罐", 6000L));
    }

    @Test
    public void recoverTanksNotInDoesNothingWhenAllTanksStillEnabled() {
        MemoryPersistence persistence = new MemoryPersistence();
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(persistence);
        tracker.raise(1, HIGH, 1600.0, "t", 1000L);
        int savesBefore = persistence.saves;

        assertTrue(tracker.recoverTanksNotIn(java.util.Collections.singleton(1), 2000L).isEmpty());
        assertEquals("没有变化不写文件", savesBefore, persistence.saves);
    }

    @Test
    public void persistsOnlyOnChange() {
        MemoryPersistence persistence = new MemoryPersistence();
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(persistence);

        tracker.raise(1, PROBE, 0, "t", 1000L);
        tracker.raise(1, PROBE, 0, "t", 2000L);
        tracker.recover(1, HIGH, 0, 3000L);
        assertEquals("只有真正的状态变化才写文件，每次轮询都会调用", 1, persistence.saves);
    }
}
