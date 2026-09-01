package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReconnectBackoffTest {

    @Test
    public void doublesEachTimeUntilCap() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0);
        assertEquals(1000, backoff.nextDelayMillis());
        assertEquals(2000, backoff.nextDelayMillis());
        assertEquals(4000, backoff.nextDelayMillis());
        assertEquals(8000, backoff.nextDelayMillis());
        assertEquals(16000, backoff.nextDelayMillis());
        // 16000 * 2 = 32000 > 30000 封顶
        assertEquals(30000, backoff.nextDelayMillis());
        assertEquals(30000, backoff.nextDelayMillis());
    }

    @Test
    public void resetGoesBackToInitialDelay() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0);
        backoff.nextDelayMillis();
        backoff.nextDelayMillis();
        backoff.reset();
        assertEquals(1000, backoff.nextDelayMillis());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveInitialDelay() {
        new ReconnectBackoff(0, 30000, 2.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMultiplierNotGreaterThanOne() {
        new ReconnectBackoff(1000, 30000, 1.0);
    }
}
