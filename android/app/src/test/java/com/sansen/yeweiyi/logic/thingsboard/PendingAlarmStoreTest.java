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
