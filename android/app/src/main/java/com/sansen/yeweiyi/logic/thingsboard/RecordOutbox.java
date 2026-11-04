package com.sansen.yeweiyi.logic.thingsboard;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * 交班/库存记录的独立上报通道（契约 §2.3），不占报警/出入库共用的 {@link PendingAlarmStore}。纯 Java。
 * <ul>
 *   <li>交班条目只记本地 id 与报文生成时刻，发送时按数据库里该行的最新内容组装（{@link ShiftResolver}）；
 *       同一班还没发出的多次变化合并为一条、取最大 ts。已发出后再变化（结束、手改）会重新入队、整班重报。</li>
 *   <li>库存记录保存后不再修改，入队时就存好整条报文，避免与异步落库竞争，也不因本地先删了而漏报。</li>
 *   <li>行已被本地删除（resolver 返回 null）则丢弃该条、继续发后面的。超出容量丢最旧的。</li>
 * </ul>
 */
public final class RecordOutbox {

    public static final String KIND_SHIFT = "shift";
    public static final String KIND_STOCK = "stock";

    /** 字段非 final 是为了 Gson 能反序列化。 */
    public static final class Entry {
        public String kind;
        public int localId;
        public long ts;
        /** 仅库存条目：完整 telemetry JSON。 */
        public String payload;

        public Entry() {
        }

        Entry(String kind, int localId, long ts, String payload) {
            this.kind = kind;
            this.localId = localId;
            this.ts = ts;
            this.payload = payload;
        }
    }

    public interface Persistence {
        List<Entry> loadAll();

        void saveAll(List<Entry> entries);
    }

    /** @return 该班当前内容组装成的 telemetry JSON（信封 ts 用传入值）；行已不存在时返回 null。 */
    public interface ShiftResolver {
        String resolve(int localId, long ts);
    }

    /** @return 是否已发出（PUBACK）。 */
    public interface Sender {
        boolean send(String telemetryJson);
    }

    private Persistence persistence;
    private final int maxEntries;
    private final LinkedList<Entry> entries = new LinkedList<>();
    private int droppedCount;

    public RecordOutbox(Persistence persistence, int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正数: " + maxEntries);
        }
        this.persistence = persistence;
        this.maxEntries = maxEntries;
        load(persistence);
    }

    /** 设备身份换了：改读新身份名下的队列，旧身份的不发也不删。 */
    public synchronized void switchPersistence(Persistence newPersistence) {
        this.persistence = newPersistence;
        entries.clear();
        load(newPersistence);
    }

    public synchronized void enqueueShift(int localId, long ts) {
        for (Entry e : entries) {
            if (KIND_SHIFT.equals(e.kind) && e.localId == localId) {
                e.ts = Math.max(e.ts, ts);
                flush();
                return;
            }
        }
        add(new Entry(KIND_SHIFT, localId, ts, null));
    }

    public synchronized void enqueueStock(String telemetryJson, long ts) {
        add(new Entry(KIND_STOCK, 0, ts, telemetryJson));
    }

    /** 按顺序发送，遇到发送失败就停（留待下次）。@return 实际发出的条数。 */
    public synchronized int drainAndSend(ShiftResolver resolver, Sender sender) {
        int sent = 0;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry head = it.next();
            String json = KIND_SHIFT.equals(head.kind) ? resolver.resolve(head.localId, head.ts) : head.payload;
            if (json != null) {
                if (!sender.send(json)) {
                    break;
                }
                sent++;
            }
            it.remove();
            flush();
        }
        return sent;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int droppedCount() {
        return droppedCount;
    }

    private void add(Entry entry) {
        entries.addLast(entry);
        trim();
        flush();
    }

    private void load(Persistence source) {
        for (Entry e : source.loadAll()) {
            if (e != null && (KIND_SHIFT.equals(e.kind) || (KIND_STOCK.equals(e.kind) && e.payload != null))) {
                entries.add(e);
            }
        }
        trim();
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            entries.removeFirst();
            droppedCount++;
        }
    }

    private void flush() {
        persistence.saveAll(new ArrayList<>(entries));
    }
}
