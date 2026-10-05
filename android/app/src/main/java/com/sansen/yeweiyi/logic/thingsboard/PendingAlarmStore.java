package com.sansen.yeweiyi.logic.thingsboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
 * 有界队列：超过 {@code maxEntries} 后丢事件，不无限增长（长时间断网也不会
 * 把设备内存/磁盘撑爆）。丢哪些由 {@link EvictionPolicy} 决定：先丢最旧的一条低优先级
 * 事件，<b>连同队列里跟它同组的事件一起丢</b>；没有低优先级的才丢最旧的一条。
 * <p>
 * 实际使用中（{@link AlarmEventBuilder#EVICTION_POLICY}）只有报警"产生"是低优先级，同组 =
 * 同一个 alarmId：丢一条"产生"时把它后面的"恢复"一起丢，云端要么两条都收到、要么都收不到，
 * 不会出现孤立的一条。"恢复"是受保护事件，兜底淘汰时也跳过——如果它的"产生"已经送达，丢掉"恢复"
 * 会让云端那条报警永远停在 ACTIVE；只有整个队列都是"恢复"时才丢最旧的一条。出入库和命令结果是
 * 记录，丢了就永久缺失，不优先丢，但兜底时排在"恢复"之前。
 * <p>
 * 纯 Java，持久化通过 {@link Persistence} 接口注入，单测可以用一个内存实现验证淘汰策略
 * 和"发送失败放回队首"，不需要真的读写文件。
 */
public final class PendingAlarmStore {

    /** 序列化后的 payload（JSON 字符串），由调用方决定序列化方式，这层不关心内容。 */
    public interface Persistence {
        List<String> loadAll();

        void saveAll(List<String> entries);
    }

    /** 单条事件的实际发送动作，由调用方注入（比如走 MQTT publish）。 */
    public interface Sender {
        boolean send(String serializedEvent);
    }

    /** 队列满时判断丢哪些事件，由调用方注入（这层不解析内容）。 */
    public interface EvictionPolicy {
        /** 能否优先丢弃。 */
        boolean isLowPriority(String serializedEvent);

        /** 分组 key：丢一条低优先级事件时，队列里同组的事件一起丢。null = 不属于任何组。 */
        String groupKey(String serializedEvent);

        /**
         * 兜底淘汰（没有低优先级事件可丢）时也要跳过的事件；只有整个队列都是这类事件时，才丢最旧的一条，
         * 以免队列无限增长。
         */
        boolean isProtected(String serializedEvent);
    }

    /** 所有事件同等优先级：退化为纯先进先出，丢最旧的一条。 */
    public static final EvictionPolicy FIFO = new EvictionPolicy() {
        @Override
        public boolean isLowPriority(String serializedEvent) {
            return false;
        }

        @Override
        public String groupKey(String serializedEvent) {
            return null;
        }

        @Override
        public boolean isProtected(String serializedEvent) {
            return false;
        }
    };

    private final Persistence persistence;
    private final int maxEntries;
    private final EvictionPolicy evictionPolicy;
    private final LinkedList<String> entries;

    private int droppedCount = 0;

    public PendingAlarmStore(Persistence persistence, int maxEntries) {
        this(persistence, maxEntries, FIFO);
    }

    public PendingAlarmStore(Persistence persistence, int maxEntries, EvictionPolicy evictionPolicy) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正数: " + maxEntries);
        }
        this.persistence = persistence;
        this.maxEntries = maxEntries;
        this.evictionPolicy = evictionPolicy;
        this.entries = new LinkedList<>(persistence.loadAll());
        trimToCapacity();
    }

    /** 入队一条新事件；队列已满则按 {@link EvictionPolicy} 丢一条，见类注释。 */
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

    /**
     * 网络恢复重连成功后调用：按顺序尝试发送每一条待发事件，只有真正发送成功才把那一条
     * 从队列里移除并落盘。跟"先 {@link #drainAll()} 清空、发送失败再 {@link #requeue}"的
     * 用法相比，不会出现"整条队列已经从磁盘清空、但还没发完就断电"的窗口——每条事件从
     * 磁盘上消失，当且仅当它已经真正发出去。遇到第一条发送失败就停止，失败的那条和还没
     * 试过的都原样留在队首，顺序不变。
     *
     * @return 本次成功发出的事件数
     */
    public synchronized int drainAndSend(Sender sender) {
        int sent = 0;
        while (!entries.isEmpty()) {
            String head = entries.peekFirst();
            if (!sender.send(head)) {
                break;
            }
            entries.removeFirst();
            flush();
            sent++;
        }
        return sent;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 因为队列已满被丢弃的事件数——只增不减，供告警/日志观测用，不是队列当前状态。 */
    public synchronized int droppedCount() {
        return droppedCount;
    }

    private void trimToCapacity() {
        while (entries.size() > maxEntries) {
            int removed = removeOldestLowPriorityWithGroup();
            if (removed == 0 && removeOldestUnprotected()) {
                removed = 1;
            }
            if (removed == 0) {
                entries.removeFirst(); // 整个队列都是受保护事件：只能丢最旧的一条
                removed = 1;
            }
            droppedCount += removed;
        }
    }

    /**
     * 兜底：丢最旧的一条非受保护事件。之前直接 {@code removeFirst()}，队首恰好是"恢复"事件时会把它
     * 单独丢掉，云端那条报警永远停在 ACTIVE（代码审核 M3）。
     */
    private boolean removeOldestUnprotected() {
        Iterator<String> it = entries.iterator();
        while (it.hasNext()) {
            if (!evictionPolicy.isProtected(it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** @return 丢掉的条数；没有低优先级事件时返回 0。 */
    private int removeOldestLowPriorityWithGroup() {
        Iterator<String> it = entries.iterator();
        while (it.hasNext()) {
            String event = it.next();
            if (evictionPolicy.isLowPriority(event)) {
                it.remove();
                String group = evictionPolicy.groupKey(event);
                return 1 + (group == null ? 0 : removeGroup(group));
            }
        }
        return 0;
    }

    private int removeGroup(String group) {
        int removed = 0;
        Iterator<String> it = entries.iterator();
        while (it.hasNext()) {
            if (group.equals(evictionPolicy.groupKey(it.next()))) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private void flush() {
        persistence.saveAll(Collections.unmodifiableList(new ArrayList<>(entries)));
    }
}
