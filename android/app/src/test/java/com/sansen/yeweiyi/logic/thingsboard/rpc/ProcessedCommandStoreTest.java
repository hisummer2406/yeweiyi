package com.sansen.yeweiyi.logic.thingsboard.rpc;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProcessedCommandStoreTest {

    private static class InMemoryPersistence implements ProcessedCommandStore.Persistence {
        Map<String, ProcessedCommandStore.Record> saved = new HashMap<>();
        boolean failWrites = false;

        @Override
        public Map<String, ProcessedCommandStore.Record> loadAll() {
            return new HashMap<>(saved);
        }

        @Override
        public boolean saveAll(Map<String, ProcessedCommandStore.Record> records) {
            if (failWrites) {
                return false;
            }
            saved = new HashMap<>(records);
            return true;
        }
    }

    @Test
    public void recordAndFindRoundTrip() {
        ProcessedCommandStore store = new ProcessedCommandStore(new InMemoryPersistence(), 86400000L);
        store.record("cmd-1", RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, "ok", 1000L);

        ProcessedCommandStore.Record found = store.find("cmd-1");
        assertEquals(RpcResultStatus.SUCCEEDED, found.status);
        assertEquals("ok", found.message);
    }

    @Test
    public void unknownCommandIdReturnsNull() {
        ProcessedCommandStore store = new ProcessedCommandStore(new InMemoryPersistence(), 86400000L);
        assertNull(store.find("never-seen"));
    }

    @Test
    public void recoverAfterRestartFailsNonTerminalRecords() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        persistence.saved.put("cmd-executing",
                new ProcessedCommandStore.Record(RpcResultStatus.EXECUTING, RpcErrorCode.NONE, "running", 1000L));
        persistence.saved.put("cmd-received",
                new ProcessedCommandStore.Record(RpcResultStatus.RECEIVED, RpcErrorCode.NONE, "received", 1000L));
        persistence.saved.put("cmd-done",
                new ProcessedCommandStore.Record(RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, "done", 1000L));

        // 模拟进程重启：用同一份 persistence 重新构造 store。
        ProcessedCommandStore restarted = new ProcessedCommandStore(persistence, 86400000L);
        int recovered = restarted.recoverAfterRestart(2000L);

        assertEquals(2, recovered);
        assertEquals(RpcResultStatus.FAILED, restarted.find("cmd-executing").status);
        assertEquals(RpcResultStatus.FAILED, restarted.find("cmd-received").status);
        assertEquals(RpcResultStatus.SUCCEEDED, restarted.find("cmd-done").status);
    }

    @Test
    public void evictExpiredRemovesOnlyOldRecords() {
        ProcessedCommandStore store = new ProcessedCommandStore(new InMemoryPersistence(), 1000L);
        store.record("old", RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, "old", 0L);
        store.record("fresh", RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, "fresh", 5000L);

        store.evictExpired(5000L); // old: 5000-0=5000 > 1000 保留期 -> 清掉；fresh: 0 未过期

        assertNull(store.find("old"));
        assertEquals(RpcResultStatus.SUCCEEDED, store.find("fresh").status);
    }

    @Test
    public void recordReportsPersistenceFailureButKeepsMemoryCopy() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        ProcessedCommandStore store = new ProcessedCommandStore(persistence, 86400000L);
        persistence.failWrites = true;

        assertFalse("写盘失败必须如实返回 false", store.record("cmd-1", RpcResultStatus.RECEIVED,
                RpcErrorCode.NONE, "已受理，执行中", 1000L));
        // 内存里仍要留着：本进程内的重复投递还是能去重，丢的只是跨重启的那份保证。
        assertEquals(RpcResultStatus.RECEIVED, store.find("cmd-1").status);
        assertTrue("失败的那次不应该污染盘上的内容", persistence.saved.isEmpty());
    }

    @Test
    public void recordReturnsTrueWhenPersisted() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        ProcessedCommandStore store = new ProcessedCommandStore(persistence, 86400000L);

        assertTrue(store.record("cmd-1", RpcResultStatus.SUCCEEDED, RpcErrorCode.NONE, "ok", 1000L));
        assertEquals(1, persistence.saved.size());
    }
}
