package com.sansen.yeweiyi.logic.thingsboard;

import com.sansen.yeweiyi.bean.OilMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记录"哪些报警当前处于 ACTIVE"，把本地报警逻辑（{@code AlarmDetection}）的产生/恢复时刻
 * 转成云端的 ACTIVE/CLEARED 事件。<b>不做任何阈值判断</b>——什么时候算产生、什么时候算恢复
 * 完全由调用方（本地报警逻辑）决定，这里只负责：
 * <ul>
 *   <li>去重：同一罐同一类型已经 ACTIVE 时，再次产生不发事件（探头断线每次轮询都会报一次）；
 *       没有 ACTIVE 时的恢复也不发（本地"条件不成立"分支每次轮询都会走）；</li>
 *   <li>按次生成 alarmId，CLEARED 复用同一次 ACTIVE 的 alarmId 和文案；</li>
 *   <li>持久化 ACTIVE 集合，进程重启后读数恢复时仍能补发 CLEARED；</li>
 *   <li>汇总每罐 {@code alarmState}（NORMAL/ALARM/FAULT）供遥测快照使用。</li>
 * </ul>
 * 只跟踪云端认识的 7 种类型（{@link AlarmEventBuilder#isReportable}），其余类型直接忽略。
 * <p>
 * 纯 Java，线程安全：产生来自报警检测线程池和串口回调，读取来自遥测调度线程。
 */
public final class ActiveAlarmTracker {

    public static final String STATE_NORMAL = "NORMAL";
    public static final String STATE_ALARM = "ALARM";
    public static final String STATE_FAULT = "FAULT";

    /** 一条处于 ACTIVE 的报警。字段非 final 是为了 Gson 能反序列化。 */
    public static final class Entry {
        public int tank;
        public int type;
        public String alarmId;
        public String text;

        public Entry() {
        }

        Entry(int tank, int type, String alarmId, String text) {
            this.tank = tank;
            this.type = type;
            this.alarmId = alarmId;
            this.text = text;
        }
    }

    /** 一次需要上报的状态变化。 */
    public static final class Transition {
        public final Entry entry;
        public final boolean recovered;
        public final double value;
        public final long tsMs;

        Transition(Entry entry, boolean recovered, double value, long tsMs) {
            this.entry = entry;
            this.recovered = recovered;
            this.value = value;
            this.tsMs = tsMs;
        }
    }

    public interface Persistence {
        List<Entry> loadAll();

        void saveAll(List<Entry> entries);
    }

    private Persistence persistence;
    private final Map<String, Entry> active = new LinkedHashMap<>();

    public ActiveAlarmTracker(Persistence persistence) {
        this.persistence = persistence;
        loadFrom(persistence);
    }

    /**
     * 设备身份换了：ACTIVE 状态改读新身份名下的存储。旧身份的 ACTIVE 报警不会在新身份下补发"恢复"，
     * 新身份从自己的状态重新开始；旧存储原样留在盘上。
     */
    public synchronized void switchPersistence(Persistence newPersistence) {
        this.persistence = newPersistence;
        active.clear();
        loadFrom(newPersistence);
    }

    private void loadFrom(Persistence source) {
        for (Entry entry : source.loadAll()) {
            if (entry != null && entry.alarmId != null && AlarmEventBuilder.isReportable(entry.type)) {
                active.put(key(entry.tank, entry.type), entry);
            }
        }
    }

    /** @return 需要上报 ACTIVE 时返回事件；类型不可上报或已经 ACTIVE 时返回 null。 */
    public synchronized Transition raise(int tank, int type, double value, String text, long nowMs) {
        if (!AlarmEventBuilder.isReportable(type)) {
            return null;
        }
        String key = key(tank, type);
        if (active.containsKey(key)) {
            return null;
        }
        Entry entry = new Entry(tank, type, AlarmEventBuilder.alarmIdFor(type, tank, nowMs), text);
        active.put(key, entry);
        persistence.saveAll(new ArrayList<>(active.values()));
        return new Transition(entry, false, value, nowMs);
    }

    /** @return 需要上报 CLEARED 时返回事件；没有对应的 ACTIVE 时返回 null。 */
    public synchronized Transition recover(int tank, int type, double value, long nowMs) {
        Entry entry = active.remove(key(tank, type));
        if (entry == null) {
            return null;
        }
        persistence.saveAll(new ArrayList<>(active.values()));
        return new Transition(entry, true, value, nowMs);
    }

    /**
     * 不在 {@code enabledTanks} 里的罐（被删除或停用、不再被轮询），它们的 ACTIVE 全部恢复并清除。
     * 没有读数可填，value 为 0。
     *
     * @return 需要上报的 CLEARED，按原来的产生顺序；没有则为空列表。
     */
    public synchronized List<Transition> recoverTanksNotIn(Set<Integer> enabledTanks, long nowMs) {
        List<Transition> transitions = new ArrayList<>();
        Iterator<Entry> it = active.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (!enabledTanks.contains(entry.tank)) {
                it.remove();
                transitions.add(new Transition(entry, true, 0, nowMs));
            }
        }
        if (!transitions.isEmpty()) {
            persistence.saveAll(new ArrayList<>(active.values()));
        }
        return transitions;
    }

    /** 探头断线 ACTIVE → FAULT；其它任一 ACTIVE → ALARM；否则 NORMAL。 */
    public synchronized String alarmState(int tank) {
        boolean anyActive = false;
        for (Entry entry : active.values()) {
            if (entry.tank != tank) {
                continue;
            }
            if (entry.type == OilMessage.PRO_ERR_CONN) {
                return STATE_FAULT;
            }
            anyActive = true;
        }
        return anyActive ? STATE_ALARM : STATE_NORMAL;
    }

    private static String key(int tank, int type) {
        return tank + ":" + type;
    }
}
