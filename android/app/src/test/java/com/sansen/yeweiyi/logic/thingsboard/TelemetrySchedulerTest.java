package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    // ---- PR #38 Codex 第三轮审核 P2：慢任务不能造成追赶式连续触发 ----

    @Test
    public void startUsesFixedDelayNotFixedRateToAvoidCatchUpBursts() {
        // scheduleAtFixedRate 按固定节拍算下次触发时刻，一次慢 task 会攒出若干过期触发，
        // 在当前 task 一结束就背靠背连续补跑；scheduleWithFixedDelay 从上一次执行结束时刻
        // 起再等一个周期，天然不会补跑。这里不用真实计时（本仓库测试一贯不用真实 sleep/计时，
        // 避免引入不稳定），改成录制 executor 实际调了哪个方法、传了什么参数来验证接线正确。
        RecordingScheduledExecutor executor = new RecordingScheduledExecutor();
        TelemetryScheduler scheduler = new TelemetryScheduler(() -> {
        }, 30000, null, executor);

        scheduler.start();

        assertEquals("start() 必须用 scheduleWithFixedDelay 接线，不能用 scheduleAtFixedRate",
                1, executor.fixedDelayCalls.size());
        RecordingScheduledExecutor.Call call = executor.fixedDelayCalls.get(0);
        assertEquals(30000L, call.initialDelay);
        assertEquals(30000L, call.delay);
        assertEquals(TimeUnit.MILLISECONDS, call.unit);
    }

    /**
     * 只实现 {@link #scheduleWithFixedDelay}/{@link #scheduleAtFixedRate}（用于录制/拒绝）
     * 和 {@link TelemetryScheduler} 实际会调到的最小方法集合，其余 {@link ScheduledExecutorService}
     * 方法本测试用不到。
     */
    private static final class RecordingScheduledExecutor extends AbstractExecutorService
            implements ScheduledExecutorService {

        static final class Call {
            final long initialDelay;
            final long delay;
            final TimeUnit unit;

            Call(long initialDelay, long delay, TimeUnit unit) {
                this.initialDelay = initialDelay;
                this.delay = delay;
                this.unit = unit;
            }
        }

        final List<Call> fixedDelayCalls = new java.util.ArrayList<>();

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                           TimeUnit unit) {
            fixedDelayCalls.add(new Call(initialDelay, delay, unit));
            return noopFuture();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                        TimeUnit unit) {
            fail("修复目标就是不能再用 scheduleAtFixedRate——慢任务后会追赶式连续触发，"
                    + "跟单飞保护的\"不排队攒任务\"语义矛盾");
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }

        private static ScheduledFuture<Object> noopFuture() {
            return new ScheduledFuture<Object>() {
                @Override
                public long getDelay(TimeUnit unit) {
                    return 0;
                }

                @Override
                public int compareTo(Delayed o) {
                    return 0;
                }

                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public Object get() {
                    return null;
                }

                @Override
                public Object get(long timeout, TimeUnit unit) {
                    return null;
                }
            };
        }
    }
}
