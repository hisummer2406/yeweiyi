package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetrySchedulerTest {

    @Test
    public void reentrantTriggerDuringRunIsSkippedNotQueued() {
        AtomicInteger runs = new AtomicInteger(0);
        // triggerNow() 用调用方线程同步跑 tick()，task 内部再调一次 triggerNow() 就是同线程
        // 重入——不需要真的起并发线程也能验证"上一次没跑完就跳过"这条规则：
        // 内层那次调用应该被 inFlight guard 直接挡掉，task 本体不会被再跑一次。
        final TelemetryScheduler[] holder = new TelemetryScheduler[1];
        TelemetryScheduler scheduler = new TelemetryScheduler(() -> {
            int callDepth = runs.incrementAndGet();
            if (callDepth == 1) {
                holder[0].triggerNow(); // 重入：此时 inFlight 已经是 true
            }
        }, 30000, null);
        holder[0] = scheduler;

        scheduler.triggerNow();

        assertEquals("重入的那次应该被跳过，不应该真的又跑一次 task", 1, runs.get());
    }

    @Test
    public void failingTaskDoesNotBreakSubsequentTicks() {
        AtomicInteger failures = new AtomicInteger(0);
        TelemetryScheduler scheduler = new TelemetryScheduler(() -> {
            throw new RuntimeException("模拟一次快照发布失败");
        }, 30000, error -> failures.incrementAndGet());

        scheduler.triggerNow();
        scheduler.triggerNow();

        assertEquals(2, failures.get());
    }

    @Test
    public void startIsIdempotentAndStopCancelsSchedule() {
        TelemetryScheduler scheduler = new TelemetryScheduler(() -> {
        }, 60000, null);

        assertFalse(scheduler.isRunning());
        scheduler.start();
        assertTrue(scheduler.isRunning());
        scheduler.start(); // 幂等：不应该抛异常，也不应该另起一个并行的调度
        assertTrue(scheduler.isRunning());

        scheduler.stop();
        assertFalse(scheduler.isRunning());
        scheduler.stop(); // 幂等：已经停了再停一次不应该抛异常
        assertFalse(scheduler.isRunning());
    }
}
