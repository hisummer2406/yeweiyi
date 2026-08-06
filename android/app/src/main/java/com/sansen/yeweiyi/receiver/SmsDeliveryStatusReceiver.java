package com.sansen.yeweiyi.receiver;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 短信发送到对方后，对返回的对方接受状态的处理逻辑
 */

public class SmsDeliveryStatusReceiver  extends BroadcastReceiver {
    private static final String TAG = "SmsDeliveryStatusReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "SmsDeliveryStatusReceiver onReceive.");
        switch (getResultCode()) {
            case Activity.RESULT_OK:
                Log.i(TAG, "RESULT_OK");
                break;
            case Activity.RESULT_CANCELED:
                Log.i(TAG, "RESULT_CANCELED");
                break;
        }
    }
}
