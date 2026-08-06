package com.sansen.yeweiyi.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

//检测网络状态
//在清单文件里面增加
//<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>

public class NetworkState {
	/**
	 * 当前的网络连接类型是否为wifi
	 * 
	 * @param mContext
	 * @return
	 */
	public static boolean isWifi(Context mContext) {
		ConnectivityManager connectivityManager = (ConnectivityManager) mContext
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetInfo = connectivityManager.getActiveNetworkInfo();
		if (activeNetInfo != null
				&& activeNetInfo.getType() == ConnectivityManager.TYPE_WIFI) {
			return true;
		}
		return false;
	}

	/**
	 * 判断WiFi和移动流量是否已连接：
	 * 
	 * @param context
	 * @return
	 */
	public static boolean checkNetworkConnection(Context context) {
		final ConnectivityManager connMgr = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);

		final android.net.NetworkInfo wifi = connMgr
				.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		final android.net.NetworkInfo mobile = connMgr
				.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

		if (wifi.isAvailable() || mobile.isAvailable()) // getState()方法是查询是否连接了数据网络
			return true;
		else
			return false;
	}
	/**
	 * 判断移动网络连接是否正常
	 * @param context
	 * @return
	 */
	public static boolean isMobileConnected(Context context) {
		if (context != null) {
			ConnectivityManager mConnectivityManager = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo mMobileNetworkInfo = mConnectivityManager
					.getNetworkInfo(ConnectivityManager.TYPE_MOBILE); // 获取移动网络信息
			if (mMobileNetworkInfo != null) {
				return mMobileNetworkInfo.isAvailable(); // getState()方法是查询是否连接了数据网络
			}
		}
		return false;
	}
}
