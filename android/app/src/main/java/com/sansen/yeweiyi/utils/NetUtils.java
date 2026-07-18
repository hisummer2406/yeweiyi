package com.sansen.yeweiyi.utils;


import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 
 * @author cj 判断网络工具类
 * 
 */
public class NetUtils {
	/**
	 * 没有连接网络
	 */
	private static final int NETWORK_NONE = -1;
	/**
	 * 移动网络
	 */
	private static final int NETWORK_MOBILE = 0;
	/**
	 * 无线网络
	 */
	private static final int NETWORK_WIFI = 1;

	public static int getNetWorkState(Context context) {
		// 得到连接管理器对象
		ConnectivityManager connectivityManager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);

		NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
		if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {

			if (activeNetworkInfo.getType() == (ConnectivityManager.TYPE_WIFI)) {
				return NETWORK_WIFI;
			} else if (activeNetworkInfo.getType() == (ConnectivityManager.TYPE_MOBILE)) {
				return NETWORK_MOBILE;
			}
		} else {
			return NETWORK_NONE;
		}
		return NETWORK_NONE;
	}
	/** 
     * 判断有无网络 。 
     *  
     * @return true 有网, false 没有网络. 
     */  
    public static boolean isNetConnect(int netMobile) {  
        if (netMobile == 1) {  
            return true;  
        } else if (netMobile == 0) {  
            return true;  
        } else if (netMobile == -1) {  
            return false;  
  
        }  
        return false;  
    }


	/**
	 * Ipv4 address check.
	 */
	private static final Pattern IPV4_PATTERN = Pattern.compile("^(" +
			"([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
			"([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$");

	/**
	 * Check if valid IPV4 address.
	 *
	 * @param input the address string to check for validity.
	 * @return True if the input parameter is a valid IPv4 address.
	 */
	public static boolean isIPv4Address(String input) {
		return IPV4_PATTERN.matcher(input).matches();
	}

	/**
	 * Get local Ip address.
	 */
	public static InetAddress getLocalIPAddress() {
		Enumeration<NetworkInterface> enumeration = null;
		try {
			enumeration = NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e) {
			//e.printStackTrace();
		}
		if (enumeration != null) {
			while (enumeration.hasMoreElements()) {
				NetworkInterface nif = enumeration.nextElement();
				Enumeration<InetAddress> inetAddresses = nif.getInetAddresses();
				if (inetAddresses != null)
					while (inetAddresses.hasMoreElements()) {
						InetAddress inetAddress = inetAddresses.nextElement();
						if (!inetAddress.isLoopbackAddress() && isIPv4Address(inetAddress.getHostAddress())) {
							return inetAddress;
						}
					}
			}
		}
		return null;
	}

	/**
	 * 获取Mac地址
	 */

		public static String getMacAddr() {
			try {
				List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
				for (NetworkInterface nif : all) {
					if (!nif.getName().equalsIgnoreCase("wlan0")) continue;

					byte[] macBytes = nif.getHardwareAddress();
					if (macBytes == null) {
						return "";
					}

					StringBuilder res1 = new StringBuilder();
					for (byte b : macBytes) {
						res1.append(String.format("%02X:",b));
					}

					if (res1.length() > 0) {
						res1.deleteCharAt(res1.length() - 1);
					}
					return res1.toString();
				}
			} catch (Exception ex) {
			}
			return "02:00:00:00:00:00";
		}

}
 
