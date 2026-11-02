package com.sansen.yeweiyi.activity;

import android.view.View;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.AlarmManager;
import com.sansen.yeweiyi.manager.AlarmManager.MessageListener;
import com.sansen.yeweiyi.manager.SoundLightManager;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;

/**
 * 报警响应 UI 协调：{@link AlarmManager} 回调驱动的顶部报警文案更新
 * （{@link #initAlarm()}）、"解警"动作（{@link #btn_jieJing(View)}）。从
 * MainActivity 搬出，持有 MainActivity 引用以复用其 {@code myApplication}
 * （用 {@code main} 代替原来的 {@code ct}——两者本是同一个实例，见
 * {@code BaseActivity.onCreate} 里的 {@code ct = this}；{@code ct} 是
 * protected 且与本类不同包，直接用 main 更简单）。见
 * openspec/changes/archive/2026-11-02-split-main-activity §5。
 *
 * <p>5.1 核查：{@code layer-data-and-hardware-boundaries} 的 {@code AlarmRepository}
 * 只存在于另一个未合并分支（PR #21），当前分支上不存在。5.2 核查：本组搬移的
 * {@code initAlarm}/{@code btn_jieJing} 均不直接访问
 * {@code DBDataManager}/{@code AlarmRepository}（只操作内存态的 Singleton
 * 标志位与 AlarmManager/SoundLightManager/SocketManager），没有需要标注
 * TODO 的持久化依赖。</p>
 */
public class AlarmCoordinator {

    private final MainActivity main;
    private final TextView barMainAlram;

    public AlarmCoordinator(MainActivity main, TextView barMainAlram) {
        this.main = main;
        this.barMainAlram = barMainAlram;
    }

    /**
     * AlarmManager的回调函数
     */
    // [不可达-2026-09-21] barMainAlram 就是 activity_main.xml 里 bar_main_alram，而它的父容器
    // alarm_info 恒为 visibility="gone"、全仓库没有任何地方切回可见（西语翻译覆盖率核查时用户
    // 复核指出，之前判定"可达"是错的——只看到 setText() 被调用，没往上查父容器是不是隐藏的）。
    // 这个回调里 barMainAlram.setText(...) 写的所有文案（含下面 alarm_no）用户都看不到。
    // 报警上报云端不经过这个回调：由 CloudAlarmReporter 在 AlarmManager.addAlarmState 和
    // AlarmDetection 的恢复分支直接上报（原来这里调 SocketManager.sendAlarm，已停用）。
    // [已更正-2026-09-23] 这里原来还写了一句"OilMessage.getState() 产出的全部 15 个字符串资源都
    // 不可达"——是错的，那句话只对"经本回调这条路径"成立，OilMessage 的文案还有另一条完全独立的
    // 消费路径（AlarmRepositoryImpl.saveAlarm → 报警记录页），7 个类型经那条路径是可达的，具体
    // 哪些可达/哪些不可达见 OilMessage.java 里 getState() 的逐条注释，不要看这个文件就下结论。
    public void initAlarm() {
        AlarmManager.getInstance().beginDetection();
        AlarmManager.getInstance().setMessageListener(new MessageListener() {
            long time = 0;

            public void getAlarmSate(OilMessage state) {
                if (state == null) {
                    if (System.currentTimeMillis() - time > 3000) {
                        // [已屏蔽-2026-09-23] alarm_no 已删除（这个回调整段文案不可达，见上方注释）。
                        // barMainAlram.setText(SystemUtils
                        //         .getRsStr(R.string.alarm_no));
                        Const.isOpenAlarm = false;
                    }
                } else if (SharePrefUtil.getBoolean(main, SharePrefUtil.TEST_DATA, false) && main.myApplication.getmOilcanDataManager().getData(state.getOilCanId()) != null) {
                    time = System.currentTimeMillis();
                    if (main.myApplication.getmOilcanDataManager().getTestData(state.getOilCanId()) != null && state.getType() != OilMessage.PRO_ERR_CONN) {
                        barMainAlram.setText(state.toString());

                    } else {
                        // [已屏蔽-2026-09-23] alarm_no 已删除（这个回调整段文案不可达，见上方注释）。
                        // barMainAlram.setText(SystemUtils
                        //         .getRsStr(R.string.alarm_no));
                        Const.isOpenAlarm = false;
                    }
                } else if (main.myApplication.getmOilcanDataManager().getData(state.getOilCanId()) != null) {
                    time = System.currentTimeMillis();
                    barMainAlram.setText(state.toString());
                } else {
                    barMainAlram.setText(state.toString());
                }
            }
        });
    }

    public void btn_jieJing(View v) {
        for (int i = 0; i < 14; i++) {
            OilCan oilCan = main.myApplication.getmOilcanDataManager().getData(i);
            if (main.myApplication.getmOilcanDataManager().getData(i) != null && main.myApplication.getmOilcanDataManager().getData(i).getAlarmState() != 0) {
                Singleton.getInstance().isTemperAlarm[i] = false;
                Singleton.getInstance().SoundLightAlarm[i] = false;
                Singleton.getInstance().isWaterAlarm[i] = false;
                Singleton.getInstance().isLowAlarms[i] = false;
                SoundLightManager.getInstance().CanleAlarm();
            }
        }
    }
}
