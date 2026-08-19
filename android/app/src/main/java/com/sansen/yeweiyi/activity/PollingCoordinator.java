package com.sansen.yeweiyi.activity;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.manager.MyTimeTask;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.TimerTask;

/**
 * 定时任务的启动/停止，对接 {@link MyTimeTask}（20 分钟一次的 TEST_DATA 标志位
 * 看门狗）。从 MainActivity 搬出，MainActivity 的 onDestroy 负责调用 {@link #stop()}
 * 以保证生命周期正确绑定。见 openspec/changes/split-main-activity §3。
 *
 * <p>注意：MainActivity 里另有一条独立的 1 秒周期 Handler 循环（REFLASH_TIME，
 * 更新 {@code SerialPortEntrity.time} 与条件触发 {@code setTestModel()}），本组
 * 任务未覆盖它——tasks.md 3.1 明确只对接 MyTimeTask，且该 Handler 循环耦合了
 * MainActivity 自身的 Handler 字段与业务方法，不属于同一个自然可提取的单元。
 * 该循环目前在 onDestroy 里没有被显式取消（Handler 持有外部类隐式引用），是
 * 一个既有的、独立于本次拆分的生命周期问题，未在本组内处理。</p>
 */
public class PollingCoordinator {

    private MyTimeTask task;

    public void start() {
        //1000*60*60*4
        task = new MyTimeTask(1000 * 60 * 20, new TimerTask() {
            @Override
            public void run() {
                Long time = System.currentTimeMillis() - SharePrefUtil.getLong(MyApplication.getInstance(), SharePrefUtil.RECORDTIME, 0);

                if (time / (1000 * 60) > 30) {
                    SharePrefUtil.saveBoolean(MyApplication.getInstance(), SharePrefUtil.TEST_DATA, false);
                }
                SharePrefUtil.saveLong(MyApplication.getInstance(), SharePrefUtil.RECORDTIME, System.currentTimeMillis());
            }
        });
        task.start();
    }

    public void stop() {
        task.stop();
    }
}
