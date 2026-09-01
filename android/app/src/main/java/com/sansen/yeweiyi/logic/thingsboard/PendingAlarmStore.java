package com.sansen.yeweiyi.logic.thingsboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 断网期间的报警事件补投队列。
 * <p>
 * 只用来存报警产生/恢复事件（{@link AlarmEventBuilder}），不存普通 30 秒快照——
 * 快照是"当前状态"，网络恢复后重发一条旧快照没有意义，直接按最新状态重新上报即可；
 * 报警/恢复则必须补投，否则云端会漏掉一次报警生命周期。见 #36 scope
 * "断网期间报警持久化补投；普通遥测按明确策略丢弃或合并，避免无限积压"。
 * <p>
 * 有界队列：超过 {@code maxEntries} 后丢最旧的一条，不无限增长（长时间断网也不会
 * 把设备内存/磁盘撑爆）。纯 Java，持久化通过 {@link Persistence} 接口注入，
 * 单测可以用一个内存实现验证"满了丢最旧"和"发送失败放回队首"两个策略，
 * 不需要真的读写文件。
 */
public final class PendingAlarmStore {

    /** 序列化后的 payload（JSON 字符串），由调用方决定序列化方式，这层不关心内容。 */
    public interface Persistence {
        List<String> loadAll();

        void saveAll(List<String> entries);
    }

    private final Persistence persistence;
    private final int maxEntries;
    private final LinkedList<String> entries;

    private int droppedCount = 0;

    public PendingAlarmStore(Persistence persistence, int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正数: " + maxEntries);
        }
        this.persistence = persistence;
        this.maxEntries = maxEntries;
        this.entries = new LinkedList<>(persistence.loadAll());
        trimToCapacity();
    }

    /** 入队一条新事件；队列已满则丢最旧的一条（先进先出），保留最新的报警状态。 */
    public synchronized void enqueue(String serializedEvent) {
        entries.addLast(serializedEvent);
        trimToCapacity();
        flush();
    }

    /** 网络恢复重连成功后调用：取走全部待发事件并清空队列。发送失败要 {@link #requeue} 放回去。 */
    public synchronized List<String> drainAll() {
        List<String> result = new ArrayList<>(entries);
        entries.clear();
        flush();
        return result;
    }

    /**
     * 补投过程中又断线：把还没发出去的事件放回队首（保持原有顺序，且比新产生的报警更早），
     * 而不是追加到队尾。
     */
    public synchronized void requeue(List<String> unsent) {
        for (int i = unsent.size() - 1; i >= 0; i--) {
            entries.addFirst(unsent.get(i));
        }
        trimToCapacity();
        flush();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 因为队列已满被丢弃的报警事件数——只增不减，供告警/日志观测用，不是队列当前状态。 */
    public synchronized int droppedCount() {
        return droppedCount;
    }

    private void trimToCapacity() {
        while (entries.size() > maxEntries) {
            entries.removeFirst();
            droppedCount++;
        }
    }

    private void flush() {
        persistence.saveAll(Collections.unmodifiableList(new ArrayList<>(entries)));
    }
}
