package com.sansen.yeweiyi.logic;

import android.content.Context;

public class ArryItmeHelp {
	public static String getItemArrays(Context ct, int resource, int index) {
		return ct.getResources().getStringArray(resource)[index];
	}
}
