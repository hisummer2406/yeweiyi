package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * issue #45：换新设备（新出厂编码）后，旧设备身份名下的待补投队列、ACTIVE 报警不能被新身份读到或发送；
 * 同一编码再次绑定时原状态完整可用（同设备重装/断网重连不受影响）。
 */
public class IdentityIsolationTest {

    /** 按身份分文件的内存模拟：键 = 身份，跟 IdentityScopedFile 的"一个身份一个文件"一致。 */
    private static final class Disk {
        final Map<String, List<String>> pending = new HashMap<>();

        PendingAlarmStore.Persistence pendingFor(final String id) {
            return new PendingAlarmStore.Persistence() {
                @Override
                public List<String> loadAll() {
                    List<String> v = pending.get(id);
                    return v == null ? new ArrayList<String>() : new ArrayList<>(v);
                }

                @Override
                public void saveAll(List<String> entries) {
                    pending.put(id, new ArrayList<>(entries));
                }
            };
        }
    }

    @Test
    public void pendingQueueOfOldIdentityIsNotSentUnderNewIdentity() {
        Disk disk = new Disk();
        PendingAlarmStore store = new PendingAlarmStore(disk.pendingFor("ZCT000001"), 10);
        store.enqueue("alarm-from-A");
        store.enqueue("stock-from-A");

        store.switchPersistence(disk.pendingFor("ZCT000002"));

        assertEquals(0, store.size());
        List<String> sentUnderB = new ArrayList<>();
        store.drainAndSend(event -> sentUnderB.add(event));
        assertTrue("旧身份的事件不能以新身份发送", sentUnderB.isEmpty());
        // 旧身份的队列原样留在盘上，不被删除
        assertEquals(Arrays.asList("alarm-from-A", "stock-from-A"), disk.pending.get("ZCT000001"));
    }

    @Test
    public void newIdentityEventsNeverLandInOldIdentityFile() {
        Disk disk = new Disk();
        PendingAlarmStore store = new PendingAlarmStore(disk.pendingFor("A"), 10);
        store.enqueue("a1");
        store.switchPersistence(disk.pendingFor("B"));
        store.enqueue("b1");

        assertEquals(Arrays.asList("a1"), disk.pending.get("A"));
        assertEquals(Arrays.asList("b1"), disk.pending.get("B"));
    }

    @Test
    public void sameIdentityRebindResumesItsOwnQueue() {
        Disk disk = new Disk();
        PendingAlarmStore store = new PendingAlarmStore(disk.pendingFor("A"), 10);
        store.enqueue("a1");
        store.switchPersistence(disk.pendingFor("B"));
        store.switchPersistence(disk.pendingFor("A"));

        List<String> sent = new ArrayList<>();
        store.drainAndSend(event -> {
            sent.add(event);
            return true;
        });
        assertEquals(Arrays.asList("a1"), sent);
    }

    @Test
    public void activeAlarmsOfOldIdentityAreNotRecoveredUnderNewIdentity() {
        final Map<String, List<ActiveAlarmTracker.Entry>> disk = new HashMap<>();
        class P implements ActiveAlarmTracker.Persistence {
            final String id;

            P(String id) {
                this.id = id;
            }

            @Override
            public List<ActiveAlarmTracker.Entry> loadAll() {
                List<ActiveAlarmTracker.Entry> v = disk.get(id);
                return v == null ? new ArrayList<ActiveAlarmTracker.Entry>() : new ArrayList<>(v);
            }

            @Override
            public void saveAll(List<ActiveAlarmTracker.Entry> entries) {
                disk.put(id, new ArrayList<>(entries));
            }
        }
        ActiveAlarmTracker tracker = new ActiveAlarmTracker(new P("A"));
        int type = firstReportableType();
        assertTrue(tracker.raise(1, type, 100, "x", 1L) != null);

        tracker.switchPersistence(new P("B"));

        // B 名下没有 ACTIVE：本地恢复不会在 B 下产生一条它从没收到过 ACTIVE 的 CLEARED
        assertNull(tracker.recover(1, type, 10, 2L));
        // A 的 ACTIVE 记录仍在盘上
        assertEquals(1, disk.get("A").size());
    }

    private static int firstReportableType() {
        for (int t = 0; t < 64; t++) {
            if (AlarmEventBuilder.isReportable(t)) {
                return t;
            }
        }
        throw new AssertionError("没有可上报的报警类型");
    }

    @Test
    public void fileSuffixKeepsLeadingZerosAndDistinguishesIds() {
        assertEquals("", IdentityScope.fileSuffix(null));
        assertEquals("", IdentityScope.fileSuffix("  "));
        assertEquals(IdentityScope.fileSuffix("ZCT000001"), IdentityScope.fileSuffix(" ZCT000001 "));
        assertNotEquals(IdentityScope.fileSuffix("0012"), IdentityScope.fileSuffix("12"));
        assertNotEquals(IdentityScope.fileSuffix("a/b"), IdentityScope.fileSuffix("a_b"));
        assertFalse(IdentityScope.fileSuffix("../x").contains("/"));
        assertEquals("_303031", IdentityScope.fileSuffix("001"));
    }
}
