package com.sansen.yeweiyi.logic.thingsboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 交班/库存记录的独立上报通道（契约 §2.3）。 */
public class RecordOutboxTest {

    private static final class MemoryPersistence implements RecordOutbox.Persistence {
        List<RecordOutbox.Entry> stored = new ArrayList<>();

        @Override
        public List<RecordOutbox.Entry> loadAll() {
            return new ArrayList<>(stored);
        }

        @Override
        public void saveAll(List<RecordOutbox.Entry> entries) {
            stored = new ArrayList<>(entries);
        }
    }

    private MemoryPersistence persistence;
    private List<String> sent;
    private List<String> resolvedShifts;

    private final RecordOutbox.ShiftResolver resolver = (localId, ts) -> {
        resolvedShifts.add(localId + "@" + ts);
        return localId == 404 ? null : "shift-" + localId + "@" + ts;
    };

    private final RecordOutbox.Sender acceptAll = json -> {
        sent.add(json);
        return true;
    };

    @Before
    public void setUp() {
        persistence = new MemoryPersistence();
        sent = new ArrayList<>();
        resolvedShifts = new ArrayList<>();
    }

    @Test
    public void shiftEntryIsResolvedFromDatabaseAtSendTime() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueShift(7, 1000L);

        assertEquals(1, outbox.drainAndSend(resolver, acceptAll));
        assertEquals(1, sent.size());
        assertEquals("shift-7@1000", sent.get(0));
        assertEquals(0, outbox.size());
    }

    @Test
    public void unsentUpdatesOfTheSameShiftMergeKeepingTheLatestTs() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueShift(7, 1000L);
        outbox.enqueueStock("stock-a", 1500L);
        outbox.enqueueShift(7, 2000L);

        assertEquals(2, outbox.size());
        outbox.drainAndSend(resolver, acceptAll);
        assertEquals("shift-7@2000", sent.get(0));
        assertEquals("stock-a", sent.get(1));
    }

    @Test
    public void shiftChangedAfterItWasSentIsReportedAgain() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueShift(7, 1000L);
        outbox.drainAndSend(resolver, acceptAll);
        outbox.enqueueShift(7, 3000L);
        outbox.drainAndSend(resolver, acceptAll);

        assertEquals("shift-7@3000", sent.get(1));
    }

    @Test
    public void shiftDeletedLocallyBeforeSendingIsDroppedWithoutBlockingTheQueue() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueShift(404, 1000L);
        outbox.enqueueStock("stock-a", 1500L);

        outbox.drainAndSend(resolver, acceptAll);

        assertEquals(1, sent.size());
        assertEquals("stock-a", sent.get(0));
        assertEquals(0, outbox.size());
    }

    @Test
    public void failedSendStopsAndKeepsTheEntryForTheNextDrain() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueStock("stock-a", 1000L);
        outbox.enqueueStock("stock-b", 2000L);

        assertEquals(0, outbox.drainAndSend(resolver, json -> false));
        assertEquals(2, outbox.size());

        outbox.drainAndSend(resolver, acceptAll);
        assertEquals("stock-a", sent.get(0));
        assertEquals("stock-b", sent.get(1));
    }

    @Test
    public void entriesSurviveARestart() {
        RecordOutbox before = new RecordOutbox(persistence, 100);
        before.enqueueShift(7, 1000L);
        before.enqueueStock("stock-a", 1500L);

        RecordOutbox after = new RecordOutbox(persistence, 100);
        after.drainAndSend(resolver, acceptAll);

        assertEquals("shift-7@1000", sent.get(0));
        assertEquals("stock-a", sent.get(1));
    }

    @Test
    public void overCapacityDropsTheOldestEntries() {
        RecordOutbox outbox = new RecordOutbox(persistence, 2);
        outbox.enqueueStock("stock-a", 1L);
        outbox.enqueueStock("stock-b", 2L);
        outbox.enqueueStock("stock-c", 3L);

        outbox.drainAndSend(resolver, acceptAll);
        assertEquals(2, sent.size());
        assertEquals("stock-b", sent.get(0));
        assertEquals(1, outbox.droppedCount());
    }

    @Test
    public void switchPersistenceLoadsTheOtherIdentitysQueue() {
        RecordOutbox outbox = new RecordOutbox(persistence, 100);
        outbox.enqueueStock("old-identity", 1L);
        MemoryPersistence other = new MemoryPersistence();

        outbox.switchPersistence(other);
        outbox.drainAndSend(resolver, acceptAll);

        assertTrue(sent.isEmpty());
        assertEquals(1, persistence.stored.size());
    }
}
