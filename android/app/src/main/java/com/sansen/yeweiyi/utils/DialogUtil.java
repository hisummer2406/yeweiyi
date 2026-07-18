package com.sansen.yeweiyi.utils;

import android.content.Context;
import android.content.Intent;

import com.sansen.yeweiyi.view.CustomProgressDialog;


public class DialogUtil {

	/**
	 * 创建进度对话框
	 * 
	 * @param context
	 * @param content
	 * @return
	 */
	public static void createProgressDialog(Context ct) {
		Intent intent = new Intent(ct,CustomProgressDialog.class);
		ct.startActivity(intent);

	}
	
}
