package com.sansen.yeweiyi.factory;

import android.content.Context;

public interface AbstractCompany {
	/**
	 * 向探针发送的请求指令
	 * 
	 * @param 命令字
	 * @param 探针ID
	 * @return 请求指令
	 */
	String sendProbeInfo(String command, String Probeaddress, Context context);

	String sendWenDuInfo(String command, String Probeaddress, Context context);

	/**
	 * 对探针发送过来的数据进行解析
	 * 
	 * @param 需要解析的数据
	 * @param 需要放置数据的对象
	 * @param 解析监听
	 * @return Probe
	 */
	boolean probeInfoPase(String data, IParseDataListener parseDataListener);


}
