package com.sansen.yeweiyi.logic.shift;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.TimeZone;

public class ShiftTimeTest {

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Test
    public void truncatesEpochToWholeSeconds() {
        assertEquals(1762146400000L, ShiftTime.truncateToSecond(1762146400999L));
    }

    @Test
    public void formatsLocalTimeToTheSecond() {
        // 1762146400000 = 2025-11-03T05:06:40Z = 13:06:40 北京时间
        assertEquals("2025-11-03 13:06:40", ShiftTime.formatSeconds(1762146400000L, SHANGHAI));
    }

    @Test
    public void storedStringAndEpochDescribeTheSameSecond() {
        ShiftTime t = ShiftTime.of(1762146400999L, SHANGHAI);
        assertEquals(1762146400000L, t.epochMs);
        assertEquals("2025-11-03 13:06:40", t.local);
    }
}
