package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UniqueTimestampAllocatorTest {

    @Test
    public void increasingCandidatesAreUsedAsIs() {
        UniqueTimestampAllocator allocator = new UniqueTimestampAllocator();
        assertEquals(1000L, allocator.allocate(1000L));
        assertEquals(2000L, allocator.allocate(2000L));
    }

    @Test
    public void sameCandidateIsBumpedByOneMillisecond() {
        UniqueTimestampAllocator allocator = new UniqueTimestampAllocator();
        assertEquals(1000L, allocator.allocate(1000L));
        assertEquals("同一毫秒生成的两条事件外层 ts 相同会在 ThingsBoard 里互相覆盖",
                1001L, allocator.allocate(1000L));
        assertEquals(1002L, allocator.allocate(1000L));
    }

    @Test
    public void earlierCandidateNeverReusesAnAllocatedValue() {
        UniqueTimestampAllocator allocator = new UniqueTimestampAllocator();
        allocator.allocate(5000L);
        assertEquals(5001L, allocator.allocate(4000L));
        assertEquals(6000L, allocator.allocate(6000L));
    }
}
