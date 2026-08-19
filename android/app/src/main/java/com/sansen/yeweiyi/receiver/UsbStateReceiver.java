package com.sansen.yeweiyi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.SystemUtils;

public class UsbStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
          
        if(action.equals(Intent.ACTION_MEDIA_EJECT)){
            SystemUtils.showRsToast(R.string.u_unload);
        }else if(action.equals(Intent.ACTION_MEDIA_MOUNTED)){
        	SystemUtils.showRsToast(R.string.u_load);
        }  
    }  
}  
