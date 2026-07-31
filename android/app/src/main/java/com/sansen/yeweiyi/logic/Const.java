package com.sansen.yeweiyi.logic;

import android.app.Activity;
import android.os.Environment;

import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.CustomProgressDialog;
import java.io.File;
/**
 * 专门存放常量
 * 
 */
public class Const {
	public static boolean isRun = true;
	public static final String SD_CARD = Environment.getExternalStorageDirectory() + File.separator;
	public static final String NEW_APK_PATH = SD_CARD+"sansen_new.apk";
	public static int isAlarm = 0;// 是否关闭报警
	public static boolean isOpenAlarm;
	public static boolean isOpen = true;
	public static boolean isUpdate = false;// 是否需要重新读取油罐信息
	public static int recordNum = 20;// 每次加载的item个数
	public static CustomProgressDialog ProgressDialog;// 用于暂存Progress的索引Dialog
	public static Object tempList;

	public static long lastDownTime; //上一次屏幕按下时间
	public static int sleepTime;//分钟
	public static int lightVolume;//屏幕亮度
	public static boolean autoLight;//自动亮度

	public static long  customOilcan=0;
	public static boolean isInOut=false; //当前 进出油状态

	public static void setIsOpen(boolean isOpen, Activity main) {

		Const.isOpen = isOpen;
	}

	private static String getStringOil(int motoroil) {

		return SystemUtils.getRsStr(motoroil);
	}

	// 油品类型选择
	public static String getOilType(int typId) {
		try {
			return DataUtils.getOilType()[typId];
		}catch (Exception e){
		}
		return "No Set";
	}



}
