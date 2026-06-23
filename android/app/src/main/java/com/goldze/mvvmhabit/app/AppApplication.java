package com.goldze.mvvmhabit.app;

import android.os.Debug;
import android.util.Log;

import com.ctc.wstx.stax.WstxEventFactory;
import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.goldze.mvvmhabit.BuildConfig;
import com.goldze.mvvmhabit.R;
import com.goldze.mvvmhabit.db.GreenDaoUtils;
import com.goldze.mvvmhabit.ui.login.LoginActivity;
import com.squareup.leakcanary.LeakCanary;

import me.goldze.mvvmhabit.base.BaseApplication;
import me.goldze.mvvmhabit.crash.CaocConfig;
import me.goldze.mvvmhabit.utils.KLog;

/**
 * Created by goldze on 2017/7/16.
 */

public class AppApplication extends BaseApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        //是否开启打印日志
        KLog.init(BuildConfig.DEBUG);
        //初始化全局异常崩溃
        initCrash();
        //内存泄漏检测
        if (!LeakCanary.isInAnalyzerProcess(this)) {
            LeakCanary.install(this);
        }
        Log.e("TAG","初始化DaoUtiles");
        GreenDaoUtils.init(this);

        // 1. 强制 StAX 使用 Woodstox 实现
        System.setProperty("javax.xml.stream.XMLInputFactory", WstxInputFactory.class.getName());
        System.setProperty("javax.xml.stream.XMLOutputFactory", WstxOutputFactory.class.getName());
        System.setProperty("javax.xml.stream.XMLEventFactory", WstxEventFactory.class.getName());

        // 2. 禁用 AWT 字体渲染（避免 FontRenderContext 异常）
        System.setProperty("org.apache.poi.util.FontDrawable", "false");
    }

    private void initCrash() {
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //背景模式,开启沉浸式
                .enabled(true) //是否启动全局异常捕获
                .showErrorDetails(true) //是否显示错误详细信息
                .showRestartButton(true) //是否显示重启按钮
                .trackActivities(true) //是否跟踪Activity
                .minTimeBetweenCrashesMs(2000) //崩溃的间隔时间(毫秒)
                .errorDrawable(R.mipmap.ic_launcher) //错误图标
                .restartActivity(LoginActivity.class) //重新启动后的activity
//                .errorActivity(YourCustomErrorActivity.class) //崩溃后的错误activity
//                .eventListener(new YourCustomEventListener()) //崩溃后的错误监听
                .apply();
    }
}
