package com.sansen.yeweiyi.utils;

import android.os.Build;
import android.support.annotation.RequiresApi;

import java.time.LocalDate;

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
}
