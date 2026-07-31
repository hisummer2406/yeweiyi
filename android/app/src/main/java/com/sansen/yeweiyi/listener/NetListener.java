package com.sansen.yeweiyi.listener;


import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import com.sansen.yeweiyi.utils.NetUtils;

public class NetListener {
    private Context mContext;
    private NetBroadcastReceiver mNetBroadcastReceiver;
    private NetEvevt netEvevt ;
    private String TAG ="NetListener";
    public NetListener(Context context) {
        mContext = context;
        mNetBroadcastReceiver = new NetBroadcastReceiver();
    }

    /**
     * screen状态广播接收者
     */
    private class NetBroadcastReceiver extends BroadcastReceiver {
        private String action = null;

        @Override  
        public void onReceive(Context context, Intent intent) {  
            // TODO Auto-generated method stub  
            // 如果相等的话就说明网络状态发生了变化  
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {  
                int netWorkState = NetUtils.getNetWorkState(context);  
                // 接口回调传过去状态的类型  
                netEvevt.onNetChange(netWorkState);  
            }  
        }  
      
    }

    /**
     * 开始监听screen状态
     * 
     */
    public void begin(NetEvevt netEvevt) {
    	this.netEvevt = netEvevt;
        try {
            registerListener();
        }catch (Exception e){
            Log.e(TAG, "begin 执行失败", e);
        }
    }


    /**
     * 停止screen状态监听
     */
    public void unregisterListener() {
        try {
            mContext.unregisterReceiver(mNetBroadcastReceiver);
        }catch (Exception e){
            Log.e(TAG, "unregisterListener 执行失败", e);
        }
    }

    /**
     * 启动screen状态广播接收器
     */
    private void registerListener() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        mContext.registerReceiver(mNetBroadcastReceiver, filter);
    }

    // 自定义接口  
    public interface NetEvevt {  
        public void onNetChange(int netMobile);  
    }  
}