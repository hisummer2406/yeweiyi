package com.sansen.yeweiyi.utils;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * SharePreferences操作工具类
 */

public class SharePrefUtil {
    public static final String MQTTADDRESS="MqttAddress";
    public static final String LOG="log";
    public static final String SYSTEMPWD="systemPwd";
    public static final String RECORDTIME="RecordTime";
    public static final String ISUPLOADING="isUploading";
    public static final String VERSIONHOWL="versionHowL";
    public static final String LONGITUDE="Longitude";//纬度
    public static final String LATITUDE="Latitude";//经度
    public static final String VERSION = "version";// 版本
    public static final String COMPANY = "company";
    public static final String Net_CONNECT = "network";
    private static final String TAG = "SharePrefUtil";
    private final static String SP_NAME = "config";    //配置信息文件名
    public static final String AUTO_LOGIN = "autoLogin";//自动登录
    public static final String SHOW_VOLUME = "showVolume";//显示体积
    public static final String TEST_DATA = "isTestData";
    /** 单罐开关量输出（IO2 联动）开关、绑定罐号（0 = 未选），见 PumpIoManager */
    public static final String PUMP_LINK_ENABLED = "pumpLinkEnabled";
    public static final String PUMP_LINK_TANK = "pumpLinkTank";
    public static final String CONNECT_PROBE = "isConnectProbe";
    public static final String LIGHT_TIME = "lightTime";
    public static final String LIGHT_AUTO = "isAutoBlack";
    public static final String ENABLE_KEY = "isShowKey";
    public static final String LIGHT_VOLUME = "lightVolume";//灯光值
    public static final String LANGUAGE = "language";
    public static final String CN_OIlTYPE = "cn_oiltype";
    public static final String EN_OIlTYPE = "en_oiltype";
    public static final String TH_OIlTYPE = "th_oiltype";

    public static final String TIME = "time";
    public static final String MAXTIME = "Maxtime";

    public static final String LOCK_SCREEN = "lock_screen";//锁住屏幕，不让长亮
    //改软件包是否允许被执行
    public static final String ISLAUNCH = "isLaunch";
    //该软件时否被注册
    public static final String ISREGISTER = "isRegister";
    public static final String MIN_ITEM_SELECT = "minItemSelect";
    public static final String DEVICESECRET = "devicesecret";

    // ThingsBoard 设备凭据：每台设备独立配网时写入，见 manager.ThingsBoardCredentialStore。
    // 不走 local.properties/BuildConfig 那条构建期注入通道——那条通道是全部设备共享一份，
    // 做不到"每台设备独立凭据"。
    public static final String TB_DEVICE_ID = "tbDeviceId";
    public static final String TB_ACCESS_TOKEN = "tbAccessToken";
    /** 连接成功过的编号，配网页据此锁定编号和令牌，见 ThingsBoardCredentialStore#markConnected。 */
    public static final String TB_CONNECTED_DEVICE_ID = "tbConnectedDeviceId";

    public static final String BrandSet = "BrandSet";
    private static SharedPreferences sp;
    public static String ISFIRST = "isFirst";
    public static String OPENGUIDE="openGuide";
    public static String TIME_REMAINING="time_remaining";
    public static String BOOL_RRMAINNING="bool_remaining";

    /**
     * 保存布尔值
     *
     * @param context
     * @param key
     * @param value
     */
    public static void saveBoolean(Context context, String key, boolean value) {
        //Log.i(TAG, "saveBoolean: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().putBoolean(key, value).commit();
    }

    /**
     * 保存字符串
     *
     * @param context
     * @param key
     * @param value
     */
    public static void saveString(Context context, String key, String value) {
        //Log.i(TAG, "saveString: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().putString(key, value).commit();

    }

    /** 需要自己 commit 并判断是否落盘时使用。 */
    public static SharedPreferences preferences(Context context) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp;
    }

    /** 多个键值一次 commit 写入，读方不会看到只写了一半的组合。参数按 key, value, key, value… 排列。 */
    public static void saveStrings(Context context, String... keyValues) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        SharedPreferences.Editor editor = sp.edit();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            editor.putString(keyValues[i], keyValues[i + 1]);
        }
        editor.commit();
    }

    public static void clear(Context context) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().clear().commit();
    }

    /**
     * 保存long型
     *
     * @param context
     * @param key
     * @param value
     */
    public static void saveLong(Context context, String key, long value) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().putLong(key, value).commit();
    }

    /**
     * 保存int型
     *
     * @param context
     * @param key
     * @param value
     */
    // saveInt / saveIntChecked / saveIntsChecked share the class lock: the remote batch write (RPC thread) takes its
    // snapshot, commits and — on failure — rolls back as one unit, so a local save on the UI thread (基础设置页) can
    // never land in between and be undone by the rollback (Codex review, PR #17).
    public static synchronized void saveInt(Context context, String key, int value) {
        //Log.i(TAG, "saveInt: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().putInt(key, value).commit();
    }

    /**
     * 保存float型
     *
     * @param context
     * @param key
     * @param value
     */
    public static void saveFloat(Context context, String key, float value) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        sp.edit().putFloat(key, value).commit();
    }

    /**
     * 获取字符值
     *
     * @param context
     * @param key
     * @param defValue
     * @return
     */
    public static String getString(Context context, String key, String defValue) {
        //Log.i(TAG, "getString: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.getString(key, defValue);
    }

    /**
     * 获取int值
     *
     * @param context
     * @param key
     * @param defValue
     * @return
     */
    /** 同 {@link #saveInt}，但返回 commit() 是否成功写盘（读回不能证明落盘：内存缓存先于磁盘更新）。 */
    public static synchronized boolean saveIntChecked(Context context, String key, int value) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.edit().putInt(key, value).commit();
    }

    /**
     * 一次写入多个 int，<b>全有或全无</b>：所有键放进同一个 Editor、只 commit 一次（SharedPreferences 整个文件经备份文件
     * 原子替换），所以磁盘上要么全是新值，要么全是旧值（远程整机设置 #9：此前逐项 commit，中途失败会留下一半新值）。
     * <p>
     * commit 失败时，SharedPreferences 的内存缓存<b>已经</b>是新值（它先改内存再写盘），这里把内存改回旧值，
     * 让本进程读到的与磁盘一致；恢复本身也写不进去时，下次进程启动从磁盘读到的仍是旧值。
     * 与 {@link #saveInt}/{@link #saveIntChecked} 共用类锁，快照—提交—回滚之间不会插入本地保存。
     *
     * @return 是否已写盘
     */
    public static synchronized boolean saveIntsChecked(Context context, java.util.Map<String, Integer> values) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        java.util.Map<String, Integer> before = new java.util.HashMap<>();
        java.util.Set<String> absent = new java.util.HashSet<>();
        for (String key : values.keySet()) {
            if (sp.contains(key)) {
                before.put(key, sp.getInt(key, 0));
            } else {
                absent.add(key);
            }
        }
        SharedPreferences.Editor editor = sp.edit();
        for (java.util.Map.Entry<String, Integer> e : values.entrySet()) {
            editor.putInt(e.getKey(), e.getValue());
        }
        if (editor.commit()) {
            return true;
        }
        SharedPreferences.Editor restore = sp.edit();
        for (java.util.Map.Entry<String, Integer> e : before.entrySet()) {
            restore.putInt(e.getKey(), e.getValue());
        }
        for (String key : absent) {
            restore.remove(key);
        }
        restore.commit();
        return false;
    }

    public static int getInt(Context context, String key, int defValue) {
        //Log.i(TAG, "getInt: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.getInt(key, defValue);
    }

    /**
     * 获取long值
     *
     * @param context
     * @param key
     * @param defValue
     * @return
     */
    public static long getLong(Context context, String key, long defValue) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.getLong(key, defValue);
    }

    /**
     * 获取float值
     *
     * @param context
     * @param key
     * @param defValue
     * @return
     */
    public static float getFloat(Context context, String key, float defValue) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.getFloat(key, defValue);
    }

    /**
     * 获取布尔值
     *
     * @param context
     * @param key
     * @param defValue
     * @return
     */
    public static boolean getBoolean(Context context, String key,
                                     boolean defValue) {
        //Log.i(TAG, "getBoolean: "+key);
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        return sp.getBoolean(key, defValue);
    }

    /**
     * 将对象进行base64编码后保存到SharePref中
     *
     * @param context
     * @param key
     * @param object
     */
    public static void saveObj(Context context, String key, Object object) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(baos);
            oos.writeObject(object);
            // 将对象的转为base64码
            String objBase64 = new String(Base64.encodeBase64(baos
                    .toByteArray()));

            sp.edit().putString(key, objBase64).commit();
            oos.close();
        } catch (IOException e) {
            Log.e(TAG, "saveObj 执行失败", e);
        }
    }

    /**
     * 将SharePref中经过base64编码的对象读取出来
     *
     * @param context
     * @param key
     * @return
     */
    public static Object getObj(Context context, String key) {
        if (sp == null)
            sp = context.getSharedPreferences(SP_NAME, 0);
        String objBase64 = sp.getString(key, null);
        if (TextUtils.isEmpty(objBase64))
            return null;

        // 对Base64格式的字符串进行解码
        byte[] base64Bytes = Base64.decodeBase64(objBase64.getBytes());
        ByteArrayInputStream bais = new ByteArrayInputStream(base64Bytes);

        ObjectInputStream ois;
        Object obj = null;
        try {
            ois = new ObjectInputStream(bais);
            obj = (Object) ois.readObject();
            ois.close();
        } catch (Exception e) {
            Log.e(TAG, "getObj 执行失败", e);
        }
        return obj;
    }


}
