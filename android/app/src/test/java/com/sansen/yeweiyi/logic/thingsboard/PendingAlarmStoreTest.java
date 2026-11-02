package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PendingAlarmStoreTest {

    private static class InMemoryPersistence implements PendingAlarmStore.Persistence {
        List<String> saved = new ArrayList<>();

        @Override
        public List<String> loadAll() {
            return new ArrayList<>(saved);
        }

        @Override
        public void saveAll(List<String> entries) {
            saved = new ArrayList<>(entries);
        }
    }

    @Test
    public void enqueueThenDrainReturnsInOrderAndClears() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 10);
        store.enqueue("a");
        store.enqueue("b");

        assertEquals(2, store.size());
        assertEquals(Arrays.asList("a", "b"), store.drainAll());
        assertEquals(0, store.size());
    }

    @Test
    public void dropsOldestWhenOverCapacityInsteadOfGrowingUnbounded() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 3);
        store.enqueue("1");
        store.enqueue("2");
        store.enqueue("3");
        store.enqueue("4"); // 应该丢掉 "1"

        assertEquals(3, store.size());
        assertEquals(1, store.droppedCount());
        assertEquals(Arrays.asList("2", "3", "4"), store.drainAll());
    }

    /**
     * 模拟 {@link AlarmEventBuilder#EVICTION_POLICY}：{@code active:X} 是报警"产生"（低优先级），
     * {@code cleared:X} 是同一 alarmId 的"恢复"，两者同组；{@code stock-n} 是出入库记录。
     */
    private static final PendingAlarmStore.EvictionPolicy ACTIVE_IS_LOW = new PendingAlarmStore.EvictionPolicy() {
        @Override
        public boolean isLowPriority(String event) {
            return event.startsWith("active:");
        }

        @Override
        public String groupKey(String event) {
            int colon = event.indexOf(':');
            return colon < 0 ? null : event.substring(colon + 1);
        }

        @Override
        public boolean isProtected(String event) {
            return event.startsWith("cleared:");
        }
    };

    /**
     * 代码审核 M3：没有"产生"可丢、队首恰好是"恢复"时，兜底淘汰不能丢它，要跳过去丢后面最旧的一条记录。
     * 之前的 removeFirst() 会把这条"恢复"单独丢掉，云端报警永远停在 ACTIVE。
     */
    @Test
    public void fallbackSkipsClearedAtHead() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 2, ACTIVE_IS_LOW);
        store.enqueue("cleared:A");
        store.enqueue("stock-1");
        store.enqueue("stock-2");

        assertEquals(1, store.droppedCount());
        assertEquals(Arrays.asList("cleared:A", "stock-2"), store.drainAll());
    }

    /** 整个队列都是"恢复"时只能丢最旧的一条，队列不能无限增长。 */
    @Test
    public void queueOfOnlyClearedStillStaysBounded() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 2, ACTIVE_IS_LOW);
        store.enqueue("cleared:A");
        store.enqueue("cleared:B");
        store.enqueue("cleared:C");

        assertEquals(Arrays.asList("cleared:B", "cleared:C"), store.drainAll());
    }

    @Test
    public void whenFullDropsOldestActiveAlarmBeforeAnyRecord() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 3, ACTIVE_IS_LOW);
        store.enqueue("stock-1");
        store.enqueue("active:A");
        store.enqueue("active:B");
        store.enqueue("stock-2"); // 满了：丢最旧的"产生" active:A，不丢更旧的 stock-1

        assertEquals(1, store.droppedCount());
        assertEquals(Arrays.asList("stock-1", "active:B", "stock-2"), store.drainAll());
    }

    /** 丢一条"产生"时把它的"恢复"一起丢，云端不会收到孤立的一条。 */
    @Test
    public void droppingActiveAlsoDropsItsCleared() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 3, ACTIVE_IS_LOW);
        store.enqueue("active:A");
        store.enqueue("cleared:A");
        store.enqueue("stock-1");
        store.enqueue("stock-2"); // 满了：active:A 连同 cleared:A 一起丢

        assertEquals(2, store.droppedCount());
        assertEquals(Arrays.asList("stock-1", "stock-2"), store.drainAll());
    }

    /**
     * "产生"已经送达、只有"恢复"在排队时，"恢复"不能被丢——否则云端那条报警永远停在 ACTIVE
     * （review 第 1 条）。没有"产生"可丢时退化为丢最旧的一条，这里是 stock-1。
     */
    @Test
    public void clearedIsNeverDroppedAsLowPriority() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 2, ACTIVE_IS_LOW);
        store.enqueue("stock-1");
        store.enqueue("cleared:A");
        store.enqueue("stock-2");

        assertEquals(Arrays.asList("cleared:A", "stock-2"), store.drainAll());
    }

    @Test
    public void whenFullOfRecordsFallsBackToDroppingOldest() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 2, ACTIVE_IS_LOW);
        store.enqueue("stock-1");
        store.enqueue("stock-2");
        store.enqueue("stock-3"); // 没有报警可丢，退化为丢最旧

        assertEquals(Arrays.asList("stock-2", "stock-3"), store.drainAll());
    }

    /** 大量报警涌入的场景：出入库记录不能被挤掉。 */
    @Test
    public void floodOfAlarmsNeverEvictsStockEvent() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 5, ACTIVE_IS_LOW);
        store.enqueue("stock-1");
        for (int i = 0; i < 100; i++) {
            store.enqueue("active:" + i);
        }

        List<String> remaining = store.drainAll();
        assertEquals(5, remaining.size());
        assertEquals("stock-1", remaining.get(0));
        assertEquals("active:99", remaining.get(4));
    }

    @Test
    public void requeuePutsUnsentEventsBackAtFront() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 10);
        store.enqueue("old-1");
        store.enqueue("old-2");

        List<String> drained = store.drainAll();
        assertEquals(Arrays.asList("old-1", "old-2"), drained);

        // 假设 "old-1" 发送成功、"old-2" 发送失败，同时又产生了一条新报警。
        store.requeue(java.util.Collections.singletonList("old-2"));
        store.enqueue("new-1");

        assertEquals(Arrays.asList("old-2", "new-1"), store.drainAll());
    }

    @Test
    public void drainAndSendOnlyRemovesEntriesThatActuallySent() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PendingAlarmStore store = new PendingAlarmStore(persistence, 10);
        store.enqueue("a");
        store.enqueue("b");
        store.enqueue("c");

        List<String> sentPayloads = new ArrayList<>();
        int sent = store.drainAndSend(payload -> {
            if (payload.equals("b")) {
                return false; // 模拟第二条发送失败
            }
            sentPayloads.add(payload);
            return true;
        });

        assertEquals(1, sent);
        assertEquals(Arrays.asList("a"), sentPayloads);
        assertEquals("失败的一条和之后的都应该原样留在队列里", 2, store.size());
        assertEquals("持久化状态要跟内存队列一致，不能出现已清空但没真正发出去的窗口",
                Arrays.asList("b", "c"), persistence.loadAll());
    }

    @Test
    public void drainAndSendStopsAtFirstFailureAndPreservesOrder() {
        PendingAlarmStore store = new PendingAlarmStore(new InMemoryPersistence(), 10);
        store.enqueue("a");
        store.enqueue("b");

        int sent = store.drainAndSend(payload -> false);

        assertEquals(0, sent);
        assertEquals(Arrays.asList("a", "b"), store.drainAll());
    }

    @Test
    public void persistsAcrossRestartViaInjectedPersistence() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PendingAlarmStore first = new PendingAlarmStore(persistence, 10);
        first.enqueue("survives-restart");

        // 模拟进程重启：用同一份 persistence 重新构造一个 store。
        PendingAlarmStore second = new PendingAlarmStore(persistence, 10);
        assertEquals(1, second.size());
        assertTrue(second.drainAll().contains("survives-restart"));
    }
}
