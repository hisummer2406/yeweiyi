package com.sansen.yeweiyi.utils;

import java.util.Random;

/**
 * Created by PC on 2018-06-07.
 */

public class TokenUtil {

    public static String getToken() {
        //获得系统的时间，单位为毫秒,转换为妙
        long totalMilliSeconds = System.currentTimeMillis();
        long totalSeconds = totalMilliSeconds / 1000;
        //求出现在的秒
        long currentSecond = totalSeconds % 60;
        //求出现在的分
        long totalMinutes = totalSeconds / 60;
        //1 2 3 4 5 6 7 8 9 10
        return String.valueOf(new Random(totalMinutes/5*5 + 1292548615).nextInt((999999-100000)+100000));
    }
}
