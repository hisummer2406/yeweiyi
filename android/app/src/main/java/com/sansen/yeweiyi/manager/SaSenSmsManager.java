
package com.sansen.yeweiyi.manager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SmsManager;

import com.sansen.yeweiyi.receiver.SmsDeliveryStatusReceiver;
import com.sansen.yeweiyi.receiver.SmsStatusReceiver;

import baidu.control.InitConfig;

/**
 * Created by PC on 2018-05-02.
 */
public class SaSenSmsManager {
    Context ct;
    private static SaSenSmsManager ourInstance;

    public static SaSenSmsManager getInstance(Context ct) {
        if (ourInstance == null) {
            ourInstance = new SaSenSmsManager(ct);
        }
        return ourInstance;
    }

    private SaSenSmsManager(Context ct) {
        this.ct = ct;
        initSendSms();
    }

    // 对短信发送状态监听的广播接收器
    private SmsStatusReceiver mSmsStatusReceiver;
    //对短信接受状态监听的广播接收器
    private SmsDeliveryStatusReceiver mSmsDeliveryStatusReceiver;

    final String SMS_SEND_ACTIOIN = "SMS_SEND";
    final String SMS_DELIVERED_ACTION = "SMS_DELIVERED";

    // 调用系统短信接口发送短信初始化
    public void initSendSms() {
        // 注册短信发送成功后的广播接收器Action
        mSmsStatusReceiver = new SmsStatusReceiver();
        ct.registerReceiver(mSmsStatusReceiver, new IntentFilter(SMS_SEND_ACTIOIN));
        mSmsDeliveryStatusReceiver = new SmsDeliveryStatusReceiver();
        ct.registerReceiver(mSmsDeliveryStatusReceiver, new IntentFilter(
                SMS_DELIVERED_ACTION));

    }

    // 发送短信
    public void sendSMS(String smsPhone, String smsContent){
        //这个意图包装了对短信发送状态回调的处理逻辑
        PendingIntent sentIntent = PendingIntent.getBroadcast(
                ct, 1, new Intent(SMS_SEND_ACTIOIN), 0);
        //这个意图包装了对短信接受状态回调的处理逻辑
        PendingIntent deliveryIntent = PendingIntent.getBroadcast(
                ct, 2, new Intent(SMS_DELIVERED_ACTION),
                0);
        SmsManager manager = SmsManager.getDefault();
        manager.sendTextMessage(smsPhone, null,
                smsContent, sentIntent,
                deliveryIntent);
    }
}
