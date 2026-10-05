package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.logic.thingsboard.ActiveAlarmTracker;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.Set;

/**
 * 本地报警逻辑 → 云端报警上报的胶水层（角色同 {@code InOilManager} 之于 {@code logic/inout}）。
 * <p>
 * 上报时机跟本地报警逻辑完全一致，不另做阈值判断：
 * <ul>
 *   <li>产生：{@link AlarmManager#addAlarmState} 调 {@link #onRaised}——{@code AlarmDetection} 的
 *       6 种报警和 {@code SerialPortManager} 的探头断线都经过那里；</li>
 *   <li>恢复：{@code AlarmDetection} 里"条件不再成立"的分支、{@code SerialPortManager} 通讯
 *       恢复处调 {@link #onRecovered}。</li>
 * </ul>
 * 本地报警逻辑的已知缺陷（开机时已在报警区域不报等）会原样体现在云端，见
 * {@code docs/ThingsBoard两版迭代执行方案.md} §3.10。
 */
public final class CloudAlarmReporter {

    private static volatile CloudAlarmReporter instance;

    private final Context context;
    private final ActiveAlarmTracker tracker;

    private CloudAlarmReporter(Context context) {
        this.context = context.getApplicationContext();
        this.tracker = new ActiveAlarmTracker(new ActiveAlarmFilePersistence(this.context));
    }

    public static CloudAlarmReporter getInstance() {
        if (instance == null) {
            synchronized (CloudAlarmReporter.class) {
                if (instance == null) {
                    instance = new CloudAlarmReporter(MyApplication.getInstance());
                }
            }
        }
        return instance;
    }

    /*
     * onRaised/onRecovered/onTanksReloaded 在同一把锁里完成"判断状态变化 + 提交给发送线程"：
     * 产生和恢复来自不同线程（探头断线的产生在主线程 Handler，恢复在串口线程），如果提交放在锁外，
     * 两条事件可能以相反顺序进入发送队列，云端先收到"恢复"、后收到"产生"，报警永远停在 ACTIVE。
     * 提交本身只是往单线程执行器里放一个任务，很快，不会长时间持锁。
     */

    /** 本地报警产生。同一罐同一类型已经 ACTIVE 时不重复上报。 */
    public void onRaised(OilMessage message) {
        if (isDemoMode()) {
            return;
        }
        String text = message.toString();
        synchronized (this) {
            report(tracker.raise(message.getOilCanId(), message.getType(), message.getValue(), text,
                    System.currentTimeMillis()));
        }
    }

    /**
     * 本地报警条件不再成立。本地每次轮询都会走到这里，没有对应 ACTIVE 时什么都不做。
     *
     * @param value 当前读数（液位/水位/温度），探头断线传 0。
     */
    public void onRecovered(int tank, int oilMessageType, double value) {
        if (isDemoMode()) {
            return;
        }
        synchronized (this) {
            report(tracker.recover(tank, oilMessageType, value, System.currentTimeMillis()));
        }
    }

    /**
     * 罐配置重新加载后调用：已经不在当前启用罐里的罐（被删除或停用），它们的 ACTIVE 报警全部上报恢复
     * 并清除。这些罐不再被轮询，本地永远不会走到它们的恢复分支，不清理的话云端会一直显示报警；
     * 以后有新罐沿用同一个罐号时，它真正的报警还会被当成重复而不上报。
     */
    public void onTanksReloaded(Set<Integer> enabledTanks) {
        synchronized (this) {
            for (ActiveAlarmTracker.Transition transition :
                    tracker.recoverTanksNotIn(enabledTanks, System.currentTimeMillis())) {
                report(transition);
            }
        }
    }

    /** 供遥测快照填 {@code tank{n}.alarmState}。 */
    public String alarmState(int tank) {
        return tracker.alarmState(tank);
    }

    private void report(ActiveAlarmTracker.Transition transition) {
        if (transition != null) {
            ThingsBoardManager.getInstance(context).reportAlarmTransition(transition);
        }
    }

    /** 演示模式下的报警来自演示数据，跟现状一致不上报。 */
    private boolean isDemoMode() {
        return SharePrefUtil.getBoolean(context, SharePrefUtil.TEST_DATA, false);
    }
}
