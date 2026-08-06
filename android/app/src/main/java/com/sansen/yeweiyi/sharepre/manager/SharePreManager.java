package com.sansen.yeweiyi.sharepre.manager;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.factory.VR350Factory;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import bean.IPBean;

public class SharePreManager {

    //是否打开驱动板
    private static final String RELAYSWITCH = "relaySwitch";
    //驱动板模式
    private static final String RELAYMOD = "relayMod";

    private static final String CLOSE_ALARM = "closeSoundAlarm";

    private static final String CLOSE_ALARM_TIME = "closeSoundTime";



    private static final String DEVICE_TYPE = "SanSen";
    private static final String ONEORTWO = "OneOrTow";
    //设备名
    private static final String IP_BEAN = "IpBean";
    //后台系统波特率
    private static final String Backstage = "Backstage";

    //防溢阀模式
    public static final int FYF = 1;
    //卸油泵模式
    public static final int XYB = 2;
    //混合模式  // S0串口 只能发送前泵  S2串口  只能发送防溢阀
    public static final int MIXING = 3;

    private static final String INOIL_TIME = "inOilTime";
    private static final String NET_ENABLE = "NET_ENABLE";


    public static void saveRelaySwitch(boolean status) {
        SharePrefUtil.saveBoolean(MyApplication.getInstance(), RELAYSWITCH, status);
    }

    public static boolean getRelaySwitch() {
        return SharePrefUtil.getBoolean(MyApplication.getInstance(), RELAYSWITCH, true);
    }

    public static void saveRelayMod(int mod) {
        SharePrefUtil.saveInt(MyApplication.getInstance(), RELAYMOD, mod);
    }

    public static int getRelayMod() {
        return SharePrefUtil.getInt(MyApplication.getInstance(), RELAYMOD, FYF);
    }

    public static void saveAlarm(boolean isAlarm) {
        SharePrefUtil.saveBoolean(MyApplication.getInstance(), CLOSE_ALARM, isAlarm);
    }

    public static boolean getAlarm() {
        return SharePrefUtil.getBoolean(MyApplication.getInstance(), CLOSE_ALARM, false);
    }

    public static void saveAlarmTime(long time) {
        SharePrefUtil.saveLong(MyApplication.getInstance(), CLOSE_ALARM_TIME, time);
    }

    public static long getAlarmTime() {
        return SharePrefUtil.getLong(MyApplication.getInstance(), CLOSE_ALARM_TIME, 5);
    }

    public static void saveDeviceType(String type) {
        SharePrefUtil.saveString(MyApplication.getInstance(), DEVICE_TYPE, type);
    }

    public static long getInOilTime() {
        return SharePrefUtil.getLong(MyApplication.getInstance(), INOIL_TIME, 30000);
    }

    public static void saveInOilTime(long time) {
        SharePrefUtil.saveLong(MyApplication.getInstance(), INOIL_TIME, time);
    }

    public static void saveOneOrTwo(int num) {
        SharePrefUtil.saveInt(MyApplication.getInstance(), ONEORTWO, num);
    }

    public static int getOneOrTwo() {
        return SharePrefUtil.getInt(MyApplication.getInstance(), ONEORTWO, 1);
    }

    public static IPBean getIpBean() {
        IPBean ipBean = (IPBean) SharePrefUtil.getObj(MyApplication.getInstance(), IP_BEAN);
        if (ipBean == null) {
            // ipBean = new IPBean("39.98.62.79",61613,"");

            //ipBean=new IPBean("39.98.92.177",61613,"");
            switch (SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.MQTTADDRESS, "服务器01")) {
                case "服务器01":
                    ipBean = new IPBean("39.98.62.79", 1883, "");
                    break;
                case "服务器02":
                    ipBean = new IPBean("39.98.92.177", 61613, "");
                    break;
                default:
                    ipBean = new IPBean("39.98.62.79", 1883, "");
                    break;


            }
        }
        return ipBean;

    }


    public static void saveIpBean(IPBean ipBean) {
        SharePrefUtil.saveObj(MyApplication.getInstance(), IP_BEAN, ipBean);
    }

    public static void saveBackstage(String backstage) {
        SharePrefUtil.saveString(MyApplication.getInstance(), Backstage, backstage);
    }

    public static String getBackstage() {
        return SharePrefUtil.getString(MyApplication.getInstance(), Backstage, VR350Factory.VR350R);
    }

    public static boolean getEnableNet() {
        return SharePrefUtil.getBoolean(MyApplication.getInstance(), NET_ENABLE, false);
    }

    public static void saveEnableNet(boolean isEnable) {
        SharePrefUtil.saveBoolean(MyApplication.getInstance(), NET_ENABLE, isEnable);
    }

}
