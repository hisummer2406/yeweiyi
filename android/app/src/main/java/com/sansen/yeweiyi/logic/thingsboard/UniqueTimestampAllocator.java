package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 给同一个 telemetry key 的即时事件分配互不相同的外层 {@code ts}。
 * <p>
 * ThingsBoard 时序库按"设备 + key + ts"定位一个点，三者相同时后写的覆盖先写的；云端
 * {@code /api/oil-in-out} 直接从时序库读，所以两条 {@code stockEvent} 外层 ts 相同，云端就只剩一条。
 * 这里保证分配出的值严格递增：候选值不大于上一次分配的值时取"上一次 + 1 毫秒"。
 * <p>
 * 只在进程内有效，不落盘：重启后系统时钟已经走过了上次分配的值，不会再撞上（系统时钟被往回调的
 * 情况除外，那属于时钟本身的问题）。
 */
public final class UniqueTimestampAllocator {

    private long lastAllocated = Long.MIN_VALUE;

    /**
     * @param candidateMillis 期望使用的时间戳（通常是事件生成的时刻）
     * @return 不小于 {@code candidateMillis}、且严格大于之前所有返回值的时间戳
     */
    public synchronized long allocate(long candidateMillis) {
        long allocated = candidateMillis > lastAllocated ? candidateMillis : lastAllocated + 1;
        lastAllocated = allocated;
        return allocated;
    }
}
