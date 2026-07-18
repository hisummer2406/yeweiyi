package com.sansen.yeweiyi.utils;

import android.content.Context;
import android.support.annotation.UiThread;
import android.widget.Toast;

import com.sansen.yeweiyi.application.MyApplication;

import rxjava.RxScheduler;
import rxjava.intf.UITask;

/**
 * Created by Administrator on 2019-05-07.
 */
public class ToastUtils {
    private static final String TAG = "ToastUtil";
    private static Toast toast;

    private Context context;


    private ToastUtils() {
    }

    public static void init() {
        if (toast == null) {
            toast = Toast.makeText(MyApplication.getInstance(), "", Toast.LENGTH_SHORT);
        }
    }


    public static void showToastShort(String msg) {
        showToast(msg, Toast.LENGTH_SHORT);

    }

    public static void showToastLong(String msg) {
        showToast(msg, Toast.LENGTH_LONG);
    }

    public static void showToastShort(int resId) {
        showToast(rsToString(resId), Toast.LENGTH_SHORT);
    }

    @UiThread
    public static void showToast(final String msg, final int length) {
        RxScheduler.doOnUiThread(new UITask<Object>() {
                                     @Override
                                     public void doOnUIThread() {
                                       //  Log.i(TAG, "showToast: "+Thread.currentThread().getName());
                                         Toast.makeText(MyApplication.getInstance(), msg, length).show();
                                     }
                                 }
        );

    }

    public static void showToastLong(int resId) {
        showToast(rsToString(resId), Toast.LENGTH_LONG);
    }

    public static String rsToString(int resId) {
        return MyApplication.getInstance().getString(resId);
    }
}
