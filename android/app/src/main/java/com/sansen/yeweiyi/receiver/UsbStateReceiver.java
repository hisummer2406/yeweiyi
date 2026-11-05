package com.sansen.yeweiyi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.SystemUtils;

// [不可达-2026-09-21] 只在 AndroidManifest.xml 里静态注册（<receiver> 声明），全仓库没有任何地方
// 用 registerReceiver() 动态注册它（西语翻译覆盖率核查时用户复核指出，之前判定"可达"是错的）。
// ACTION_MEDIA_MOUNTED/ACTION_MEDIA_EJECT 从 Android 7.0（API 24）起被系统明确列入"不再投递给
// manifest 静态注册的隐式广播"名单——这是文档化的硬限制，不是"看设备 ROM"的软限制。本项目
// targetSdkVersion 27，早过这条线，这个 Receiver 在任何真机上基本收不到广播。u_load/u_unload
// 不用优先翻。要修的话得改成 registerReceiver() 在运行时动态注册。
public class UsbStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
          
        // [已屏蔽-2026-09-23] u_load/u_unload 已删除（这个 Receiver 不可达，见上方注释）。
        if(action.equals(Intent.ACTION_MEDIA_EJECT)){
            // SystemUtils.showRsToast(R.string.u_unload);
        }else if(action.equals(Intent.ACTION_MEDIA_MOUNTED)){
        	// SystemUtils.showRsToast(R.string.u_load);
        }
    }  
}  
