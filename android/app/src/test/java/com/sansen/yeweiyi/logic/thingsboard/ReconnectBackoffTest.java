package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReconnectBackoffTest {

    // 用 NO_JITTER 这条测试专用通道验证退避上限序列本身（翻倍/封顶/重置），
    // 不受抖动返回值随机性的干扰——真机路径用的默认构造函数走 FULL_JITTER，见下面
    // jitter 相关用例。
    @Test
    public void doublesEachTimeUntilCap() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0, ReconnectBackoff.NO_JITTER);
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
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0, ReconnectBackoff.NO_JITTER);
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

    // ---- 抖动（PR #38 Codex 第三轮审核 P2：broker 重启时所有设备退避序列不再对齐）----

    @Test
    public void jitterFunctionReceivesUnjitteredUpperBoundsAndDoesNotDisturbProgression() {
        List<Long> upperBoundsSeen = new ArrayList<>();
        ReconnectBackoff.JitterFunction recording = upperBoundInclusive -> {
            upperBoundsSeen.add(upperBoundInclusive);
            // 故意返回一个跟上限无关的小值，验证它不会污染内部的翻倍/封顶状态。
            return 1;
        };
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0, recording);

        assertEquals(1L, backoff.nextDelayMillis());
        assertEquals(1L, backoff.nextDelayMillis());
        assertEquals(1L, backoff.nextDelayMillis());

        assertEquals("传给抖动函数的上限值必须是未抖动过的指数退避序列本身，"
                        + "不能因为上一次的抖动返回值被污染",
                java.util.Arrays.asList(1000L, 2000L, 4000L), upperBoundsSeen);
    }

    @Test
    public void defaultConstructorAppliesFullJitterWithinBounds() {
        ReconnectBackoff backoff = new ReconnectBackoff(1000, 30000, 2.0);
        long[] expectedUpperBounds = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (long upperBound : expectedUpperBounds) {
            long delay = backoff.nextDelayMillis();
            assertTrue("抖动后的延迟必须落在 [0, " + upperBound + "]（本次退避上限）区间内，实际是 " + delay,
                    delay >= 0 && delay <= upperBound);
        }
    }
}
