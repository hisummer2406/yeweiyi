package com.sansen.yeweiyi.utils;

import android.os.Build;
import android.support.annotation.RequiresApi;

import java.time.LocalDate;
import java.util.Calendar;

public class PWDTools {
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static String getSetPwd(){
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        return String.valueOf(year-(month*day)*2);
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static String getGaoJiSetPwd(){
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        return String.valueOf(year-(month*day)*3);
    }

    /** 厂家页面密码：年 − 月×日×5（设置页 ×2、高级设置 ×3）。用 Calendar，不依赖 API 26。 */
    public static String getFactoryPwd() {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        int day = now.get(Calendar.DAY_OF_MONTH);
        return String.valueOf(year - (month * day) * 5);
    }
}
