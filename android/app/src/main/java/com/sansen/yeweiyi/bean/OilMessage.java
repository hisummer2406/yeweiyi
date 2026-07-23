package com.sansen.yeweiyi.bean;


import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.utils.SharePrefUtil;
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

    private String getState(int oilid, int state, double value) {
        String language = SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.LANGUAGE, "zh");
        //Log.i("语言", language);
        String data = null;
        String oilIdEn = getEnglishNum(oilid);
        if (state == HEIGHT_OIL_ALARM) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.oilHeight_alarm);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.oilHeight_alarm);
            }else if ("zh".equals(language)){
                data =SystemUtils.getRsStr(R.string.oilHeight_alarm)+" " + oilid  ;
            }

        } else if (state == HHEIGHT_OIL_ALARM) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.oilHHeight_alarm);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.oilHHeight_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.oilHHeight_alarm)+" " + oilid  ;
            }
        } else if (state == LOW_OIL_ALARM) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.oillow_alarm);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.oillow_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.oillow_alarm)+" " + oilid  ;
            }
        } else if (state == LLOW_OIL_ALARM) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.oilllow_alarm);
            }  else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.oilllow_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.oilllow_alarm)+" " + oilid  ;
            }
        } else if (state == HEIGHT_WATER) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.waterheight_alarm);
            }else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.waterheight_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.waterheight_alarm)+" " + oilid  ;
            }
        } else if (state == HEIGHT_TEMPER) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.tempheight_alarm);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.tempheight_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.tempheight_alarm)+" " + oilid  ;
            }
        } else if (state == LOW_TEMPER) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.templow_alarm);
            }else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.templow_alarm);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.templow_alarm)+" " + oilid  ;
            }
        } else if (state == IN_OIL) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.inoil);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.inoil);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.inoil)+" " + oilid  ;
            }
        } else if (state == OUT_OIL) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.outoil);
            }else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.outoil);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.outoil)+" " + oilid  ;
            }
        } else if (state == IN_OILing) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.inoiling);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.inoiling);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.inoiling)+" " + oilid  ;
            }
        } else if (state == OUT_OILing) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.outoiling);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.outoiling);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.outoiling)+" " + oilid  ;
            }
        } else if (state == PRO_ERR_CONN) {
            if ("en".equals(language)) {
                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.oil_connect_error);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.oil_connect_error);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.oil_connect_error)+" " + oilid  ;
            }
        } else if (state == OIl_EXPLORATION) {
            if ("en".equals(language)) {

                data = "The " + oilIdEn + " " + SystemUtils.getRsStr(R.string.tantou_height);
            } else if ("zh".equals(language)){
                data = oilid + " " + SystemUtils.getRsStr(R.string.tantou_height);
            }else if ("th".equals(language)){
                data =SystemUtils.getRsStr(R.string.tantou_height)+" " + oilid  ;
            }
        } else if (state == NET_ERR_CONN) {
            data = SystemUtils.getRsStr(R.string.net_connect_err);
        } else if (state == RELAY_DISCONNECT) {
            data = SystemUtils.getRsStr(R.string.fyf_connect_error);
        }
        return data;
    }

    private String getEnglishNum(int oilid) {
        switch (oilid) {
            case 1:
                return "first";
            case 2:
                return "second";
            case 3:
                return "third";
            case 4:
                return "fourth";
            case 5:
                return "fifth";
            case 6:
                return "sixth";
            case 7:
                return "seventh";
            case 8:
                return "eighth";
            case 9:
                return "ninth";
            case 10:
                return "tenth";
            case 11:
                return "eleventh";
            case 12:
                return "twelfth";
            case 13:
                return "thirteenth";
            case 14:
                return "fourteenth";
            case 15:
                return "fifteenth";
            case 16:
                return "sixteenth";
        }
        return null;
    }

    public String getD2d() {
        return d2d;
    }

    public void setD2d(String d2d) {
        this.d2d = d2d;
    }

}
