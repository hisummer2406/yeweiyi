package com.sansen.yeweiyi.logic.thingsboard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单一调度器，固定周期触发一次完整快照上报，并且用 {@link AtomicBoolean} 做单飞保护——
 * 上一次快照还没跑完（比如探头轮询/MQTT publish 卡住），下一次 tick 直接跳过，不重入、
 * 不排队攒任务。
 * <p>
 * 只用 {@code java.util.concurrent}，无 Android 依赖，可在 JVM 单测里注入很短的周期
 * 验证"不重入""可重复 start/stop"。真机跑 30 秒周期由调用方传参决定，这里不写死。
 */
public final class TelemetryScheduler {

    /** 由调用方实现真正的快照生成 + 发布，抛异常会被吞掉并记一条失败，不影响下一次调度。 */
    public interface SnapshotTask {
        void run();
    }

    private final ScheduledExecutorService executor;
    private final SnapshotTask task;
    private final long periodMillis;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final FailureListener failureListener;

    private volatile ScheduledFuture<?> future;

    public interface FailureListener {
        void onSnapshotFailed(Throwable error);
    }

    public TelemetryScheduler(SnapshotTask task, long periodMillis, FailureListener failureListener) {
        this(task, periodMillis, failureListener, Executors.newSingleThreadScheduledExecutor(daemonThreadFactory()));
    }

    /** 供测试注入自定义 executor（例如直接执行、不经过真实线程调度）。 */
    TelemetryScheduler(SnapshotTask task, long periodMillis, FailureListener failureListener,
                        ScheduledExecutorService executor) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis 必须为正数: " + periodMillis);
        }
        this.task = task;
        this.periodMillis = periodMillis;
        this.failureListener = failureListener;
        this.executor = executor;
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "tb-telemetry-scheduler");
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 幂等：已经在跑就不重复安排。
     * <p>
     * 用 {@code scheduleWithFixedDelay} 而不是 {@code scheduleAtFixedRate}（PR #38 Codex
     * 第三轮审核 P2）：后者按固定节拍算下次触发时刻，跟单次 task 实际耗时无关——一旦某次
     * {@link #tick} 跑得比 {@code periodMillis} 久（探头轮询/MQTT publish 卡住），
     * 单线程 executor 会攒下若干个已经过期的触发，在当前 task 一结束就背靠背连续补跑，
     * 跟类头注释"单飞保护、不排队攒任务"的意图矛盾——{@link #inFlight} 只挡得住真正的并发
     * 重入，挡不住这种顺序补跑的连续 burst。{@code scheduleWithFixedDelay} 从上一次
     * 执行**结束**时刻起再等一个 {@code periodMillis}，天然不会补跑。
     */
    public synchronized void start() {
        if (future != null && !future.isDone()) {
            return;
        }
        future = executor.scheduleWithFixedDelay(this::tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    /** 立即触发一次（用于测试，或者上线后第一条快照不等一个完整周期）。 */
    public void triggerNow() {
        tick();
    }

    private void tick() {
        if (!inFlight.compareAndSet(false, true)) {
            // 上一次还没跑完，本次直接跳过——避免探头轮询被并发快照任务阻塞。
            return;
        }
        try {
            task.run();
        } catch (Throwable t) {
            if (failureListener != null) {
                failureListener.onSnapshotFailed(t);
            }
        } finally {
            inFlight.set(false);
        }
    }

    public boolean isRunning() {
        return future != null && !future.isDone();
    }
}
