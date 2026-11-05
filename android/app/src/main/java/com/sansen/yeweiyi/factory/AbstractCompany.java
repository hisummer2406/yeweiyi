package com.sansen.yeweiyi.factory;

import android.content.Context;

public interface AbstractCompany {
	String sendProbeInfo(String command, String Probeaddress, Context context);

	String sendWenDuInfo(String command, String Probeaddress, Context context);
	String sendChangDuInfo(String command, String Probeaddress, Context context);
	boolean probeInfoPase(String data, IParseDataListener parseDataListener);


}
