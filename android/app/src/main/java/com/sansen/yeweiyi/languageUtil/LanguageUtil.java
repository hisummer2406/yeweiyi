package com.sansen.yeweiyi.languageUtil;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.sansen.yeweiyi.base.BaseActivity;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.Locale;

/**
 * 语言切换的唯一入口。
 *
 * 持久化统一落在 {@link SharePrefUtil#LANGUAGE}（值 "zh"/"en"）这一个键上——
 * 它也是 OilCan/OilMessage/DataUtils/CreateOilTableFragment 等业务代码原本就在读的键，
 * 不再另开一份存储，避免"UI 语言切了、业务文案没跟着切"的不一致。
 * 实际的 Configuration 修改委托给 {@link BaseActivity#settingLanguage(Locale)}，避免两处重复实现。
 *
 * Android 7.0（API 24）起每个 Context 可以有独立于 Application 的一份 Resources，
 * 单靠 {@code Resources.updateConfiguration()} 改 Application 的那份不保证对已创建/新建的
 * Activity 生效。所以 Application 和 Activity 都要在 {@code attachBaseContext()} 里调用
 * {@link #wrap(Context)}，用持久化的语言包一层 {@code createConfigurationContext}，
 * 这样每次冷启动都能从一开始就拿到正确语言的 Resources。
 *
 * 语言切换生效走的是整体重启 app（见 {@link com.sansen.yeweiyi.manager.AppManager#restartApp}），
 * 不是 Activity.recreate()——本 app 里有几个 Fragment 只有带参构造函数、没有无参构造函数，
 * recreate() 会触发 FragmentManager 用反射恢复它们从而崩溃，整体重启则保证新进程拿到的
 * savedInstanceState 一定是 null，不会走到那条恢复路径。
 */
public class LanguageUtil {

    public static Locale getLocale(Context context) {
        String code = SharePrefUtil.getString(context, SharePrefUtil.LANGUAGE, "");
        return code.isEmpty() ? null : new Locale(code);
    }

    /**
     * 在 attachBaseContext() 里调用：若已有持久化的语言选择，返回一个套上该 Locale 的
     * ConfigurationContext；否则原样返回 base（跟随系统语言）。
     */
    public static Context wrap(Context base) {
        Locale locale = getLocale(base);
        if (locale == null) {
            return base;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }

    private static void setLocale(Context pContext, Locale pUserLocale) {
        SharePrefUtil.saveString(pContext, SharePrefUtil.LANGUAGE, pUserLocale.getLanguage());
    }

    public static boolean updateLocale(Context context, Locale locale) {
        if (needUpdateLocale(context, locale)) {
            BaseActivity.settingLanguage(locale);
            setLocale(context, locale);
            return true;
        }
        return false;
    }

    public static boolean needUpdateLocale(Context pContext, Locale newUserLocale) {
        return newUserLocale != null && !getCurrentLocale(pContext).equals(newUserLocale);
    }
    public static Locale getCurrentLocale(Context context) {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { //7.0有多语言设置获取顶部的语言
            locale = context.getResources().getConfiguration().getLocales().get(0);
        } else {
            locale = context.getResources().getConfiguration().locale;
        }
        return locale;
    }

    /** 当前界面语言是否为中文（只比较语言码，zh_CN/zh_TW 等地区都算中文）。 */
    public static boolean isChinese(Context context) {
        return Locale.CHINESE.getLanguage().equals(getCurrentLocale(context).getLanguage());
    }
}
