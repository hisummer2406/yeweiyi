package com.sansen.yeweiyi.bean;


import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.SystemUtils;

public class OilMessage {
    public static final int HEIGHT_OIL_ALARM = 1;
    public static final int HHEIGHT_OIL_ALARM = 2;

    public static final int LOW_OIL_ALARM = 3;
    public static final int LLOW_OIL_ALARM = 4;

    public static final int HEIGHT_WATER = 5;
    public static final int HEIGHT_TEMPER = 6;
    public static final int LOW_TEMPER = 7;
    public static final int OUT_OIL = 8;
    public static final int IN_OIL = 9;


    public static final int PRO_ERR_CONN = 10;//油罐连接失败

    public static final int NET_ERR_CONN = 11;

    public static final int RELAY_DISCONNECT = 12;

    public static final int OIl_EXPLORATION = 13;

    public static final int OUT_OILing = 14;

    public static final int IN_OILing = 15;
    private int id;

    private String d2d;
    //消息Id
    private String msg_id;
    //加油站名字
    private String gasName;
    //消息类型
    private int type;
    //时间
    private String date;
    //内容
    private double value;
    //油罐ID
    private int oilCanId;
    //当前消息重复显示了多少次
    private int size;
    private String jieZhi;

    public String getJieZhi() {
        return jieZhi;
    }

    public void setJieZhi(String jieZhi) {
        this.jieZhi = jieZhi;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getGasName() {
        return gasName;
    }

    public void setGasName(String gasName) {
        this.gasName = gasName;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double content) {
        value = content;
    }

    public int getOilCanId() {
        return oilCanId;
    }

    public void setOilCanId(int oilCanId) {
        this.oilCanId = oilCanId;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public boolean equals(Object o) {
        OilMessage oilMessage = (OilMessage) o;
        if (oilMessage.getType() == type && oilMessage.getOilCanId() == oilCanId) {
            return true;
        } else {
            return false;
        }
    }

    public String getMsg_id() {
        return msg_id;
    }

    public void setMsg_id(String msg_id) {
        this.msg_id = msg_id;
    }

    @Override
    public String toString() {

        return getState(oilCanId, type, value);
    }

    /**
     * 各状态对应的提示文案资源。文案本身（含罐号占位符 %1$d 的位置/语序）由
     * values / values-en 各自的资源串决定，这里不再按语言分支拼字符串——
     * 否则每加一种语言就要在这里多加一个 else if。
     */
    private String getState(int oilid, int state, double value) {
        switch (state) {
            case HEIGHT_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilHeight_alarm, oilid);
            case HHEIGHT_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilHHeight_alarm, oilid);
            case LOW_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oillow_alarm, oilid);
            case LLOW_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilllow_alarm, oilid);
            case HEIGHT_WATER:
                return SystemUtils.getRsStr(R.string.waterheight_alarm, oilid);
            case HEIGHT_TEMPER:
                return SystemUtils.getRsStr(R.string.tempheight_alarm, oilid);
            case LOW_TEMPER:
                return SystemUtils.getRsStr(R.string.templow_alarm, oilid);
            case IN_OIL:
                return SystemUtils.getRsStr(R.string.inoil, oilid);
            case OUT_OIL:
                return SystemUtils.getRsStr(R.string.outoil, oilid);
            case IN_OILing:
                return SystemUtils.getRsStr(R.string.inoiling, oilid);
            case OUT_OILing:
                return SystemUtils.getRsStr(R.string.outoiling, oilid);
            case PRO_ERR_CONN:
                return SystemUtils.getRsStr(R.string.oil_connect_error, oilid);
            case OIl_EXPLORATION:
                return SystemUtils.getRsStr(R.string.tantou_height, oilid);
            case NET_ERR_CONN:
                return SystemUtils.getRsStr(R.string.net_connect_err);
            case RELAY_DISCONNECT:
                return SystemUtils.getRsStr(R.string.fyf_connect_error);
            default:
                return null;
        }
    }

    public String getD2d() {
        return d2d;
    }

    public void setD2d(String d2d) {
        this.d2d = d2d;
    }

}
