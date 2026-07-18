package com.sansen.yeweiyi.utils;

import android.util.Log;

import java.util.Random;

/**
 * Created by PC on 2018-09-05.
 */

public class RandomUtils {
    public static String radomVolumeStr(int begin,int end){
        int result;
        do{
            result = new Random().nextInt(end);
        }while(result<begin);

        return result<10?"0"+result:String.valueOf(result);
    }
    public static int radomVolumeInt(int begin,int end){
        int result;
        do{
            result = new Random().nextInt(end);
        }while(result<begin);
        Log.i("假数据","随机数"+result);
        return result;
    }

    public static String radomHour(String time,int begin,int end){
        int result;
        do{
            result = new Random().nextInt(end);
        }while(result<begin);

        result=Integer.valueOf(time)+result;
        if (result>=24){
            return  radomVolumeStr(0,23);
        }

        return result<10?"0"+result:String.valueOf(result);
    }


}
