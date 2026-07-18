package com.sansen.yeweiyi.structbean;

import java.util.Arrays;

/**
 * Created by PC on 2018-07-26.
 */

public class StructData {

     public static final int ATG_OPE_GETSTOCK     = 1;
     public static final int ATG_OPE_GETTIMESTOCK = 2;
      public static final int ATG_OPE_GETOILIN     = 3;
      public static final int ATG_OPE_GETALARM     = 4;
      public static final int ATG_OPE_GETFAILURE   = 5;
      public static final int ATG_OPE_CHECKTIME    = 6;
      public static final int ATG_OPE_ALARMSETTER  = 7;
      public static final int ATG_OPE_GETCAPACITYTABLE = 9;
      public static final int ATG_OPE_SETCAPACITYTABLE = 10;
      public static final int ATG_OPE_STARTLIQUID   = 11;
      public static final int ATG_OPE_STOPLIQUID    = 12;
      public static final int ATG_OPE_LIQUIDREPORT  = 13;
      public static final int ATG_OPE_GETDEVICEINFO = 14;
      public static final int ATG_OPE_HIGHTOLITER   = 15;
      public static final int ATG_OPE_SETCORRECTION = 16;
      public static final int ATG_OPE_SETPROBE      = 17;
      public static final int ATG_OPE_GETPOWERRECORD = 18;

    public int commd;
    public String json;
    public byte [] random;
    public byte[]data;

    public int getCommd() {
        return commd;
    }

    public void setCommd(int commd) {
        this.commd = commd;
    }

    public String getJson() {
        return new String(this.data);
    }

    public void setJson(String json) {
        this.json = json;
    }

    public byte[] getRandom() {
        return random;
    }

    public void setRandom(byte[] random) {
        this.random = random;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "StructData{" +
                "commd=" + commd +
                ", json='" + json + '\'' +
                ", random=" + Arrays.toString(random) +
                '}';
    }
}
