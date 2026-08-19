package com.sansen.yeweiyi.activity;

import android.view.View;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.AlarmManager;
import com.sansen.yeweiyi.manager.AlarmManager.MessageListener;
import com.sansen.yeweiyi.manager.SocketManager;
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
 * openspec/changes/split-main-activity §5。
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
    public void initAlarm() {
        AlarmManager.getInstance().beginDetection();
        AlarmManager.getInstance().setMessageListener(new MessageListener() {
            long time = 0;

            public void getAlarmSate(OilMessage state) {
                if (state == null) {
                    if (System.currentTimeMillis() - time > 3000) {
                        barMainAlram.setText(SystemUtils
                                .getRsStr(R.string.alarm_no));
                        Const.isOpenAlarm = false;
                    }
                } else if (SharePrefUtil.getBoolean(main, SharePrefUtil.TEST_DATA, false) && main.myApplication.getmOilcanDataManager().getData(state.getOilCanId()) != null) {
                    time = System.currentTimeMillis();
                    if (main.myApplication.getmOilcanDataManager().getTestData(state.getOilCanId()) != null && state.getType() != OilMessage.PRO_ERR_CONN) {
                        barMainAlram.setText(state.toString());

                    } else {
                        barMainAlram.setText(SystemUtils
                                .getRsStr(R.string.alarm_no));
                        Const.isOpenAlarm = false;
                    }
                } else if (main.myApplication.getmOilcanDataManager().getData(state.getOilCanId()) != null) {
                    time = System.currentTimeMillis();
                    barMainAlram.setText(state.toString());
                    SocketManager.getInstance(main).sendAlarm(state.toString());

                } else {
                    SocketManager.getInstance(main).sendAlarm(state.toString());
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
