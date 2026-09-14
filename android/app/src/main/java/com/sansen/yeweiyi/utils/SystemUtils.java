package com.sansen.yeweiyi.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SystemUtils {

	public static boolean isFirst(Context ct) {

		int currentVersion =getAppVersionCode(ct);

		int lastVersion = SharePrefUtil.getInt(ct,SharePrefUtil.VERSION, 0);
		if (currentVersion > lastVersion) {
			// 如果当前版本大于上次版本，该版本属于第一次启动
			return true;
		} else {
			return false;
		}
	}

	public static boolean isFirstNoVersion(Context ct){
		if (SharePrefUtil.getBoolean(ct,SharePrefUtil.ISFIRST,true)){
			SharePrefUtil.saveBoolean(ct,SharePrefUtil.ISFIRST,false);
			return true;
		}
		return false;
	}

	public static void noFirst(Context ct) {
		int currentVersion =SystemUtils.getAppVersionCode(ct);
		// 将当前版本写入preference中，则下次启动的时候，据此判断，不再为首次启动
		SharePrefUtil.saveInt(ct, SharePrefUtil.VERSION, currentVersion);
	}

	public static void showMemory() {
		// 应用程序最大可用内存
		int maxMemory = ((int) Runtime.getRuntime().maxMemory()) / 1024 / 1024;
		// 应用程序已获得内存
		long totalMemory = ((int) Runtime.getRuntime().totalMemory()) / 1024 / 1024;
		// 应用程序已获得内存中未使用内存
		long freeMemory = ((int) Runtime.getRuntime().freeMemory()) / 1024 / 1024;
		System.out.println("---> maxMemory=" + maxMemory + "M,totalMemory="
				+ totalMemory + "M,freeMemory=" + freeMemory + "M");
	}

	/**
	 * 判断当前应用是否是中文
	 * 
	 * @param ct
	 * @return
	 */
	public static boolean isZh(Context ct) {
		Locale locale = ct.getResources().getConfiguration().locale;
		String language = locale.getLanguage();
		if (language.endsWith("zh"))
			return true;
		else
			return false;
	}

	/**
	 * 获取外置SD卡路径
	 * 
	 * @return 应该就一条记录或空
	 */
	public static List<String> getExtSDCardPath() {
		List<String> lResult = new ArrayList<String>();
		try {
			Runtime rt = Runtime.getRuntime();
			Process proc = rt.exec("mount");
			InputStream is = proc.getInputStream();
			InputStreamReader isr = new InputStreamReader(is);
			BufferedReader br = new BufferedReader(isr);
			String line;
			while ((line = br.readLine()) != null) {
				if (line.contains("extSdCard")) {
					String[] arr = line.split(" ");
					String path = arr[1];
					File file = new File(path);
					if (file.isDirectory()) {
						lResult.add(path);
					}
				}
			}
			isr.close();
		} catch (Exception e) {
		    Log.e("SystemUtils", "getExtSDCardPath 执行失败", e);
		}
		return lResult;
	}

	public static String getTotalMemory(Context ct) {
		String str1 = "/proc/meminfo";// 系统内存信息文件
		String str2;
		String[] arrayOfString;
		long initial_memory = 0;

		try {
			FileReader localFileReader = new FileReader(str1);
			BufferedReader localBufferedReader = new BufferedReader(
					localFileReader, 8192);
			str2 = localBufferedReader.readLine();// 读取meminfo第一行，系统总内存大小

			arrayOfString = str2.split("\\s+");

			initial_memory = Integer.valueOf(arrayOfString[1]).intValue() * 1024;// 获得系统总内存，单位是KB，乘以1024转换为Byte
			localBufferedReader.close();

		} catch (IOException e) {
		    Log.e("SystemUtils", "getTotalMemory 执行失败", e);
		}
		return Formatter.formatFileSize(ct, initial_memory);// Byte转换为KB或者MB，内存大小规格化
	}

	public static String getAvailMemory(Context ct) {// 获取android当前可用内存大小

		ActivityManager am = (ActivityManager) ct
				.getSystemService(Context.ACTIVITY_SERVICE);
		MemoryInfo mi = new MemoryInfo();
		am.getMemoryInfo(mi);
		// mi.availMem; 当前系统的可用内存

		return Formatter.formatFileSize(ct, mi.availMem);// 将获取的内存大小规格化
	}

	public static long getFirstCreateTime(Context ct) {
		PackageManager packageManager = ct.getPackageManager();

		PackageInfo packageInfo = null;
		try {
			packageInfo = packageManager.getPackageInfo(ct.getPackageName(), 0);
		} catch (NameNotFoundException e) {
		    Log.e("SystemUtils", "getFirstCreateTime 执行失败", e);
		}
		return packageInfo.firstInstallTime;// 应用第一次安装的时间
	}

	/**
	 * MyApplication.attachBaseContext() 里对 Configuration 的包装，实测在部分冷启动路径下
	 * 没有真正反映到 MyApplication.getInstance().getResources() 上（跟 Activity 那边不一样，
	 * 原因还没查清楚）——所以这里不再假设它已经生效，每次都用持久化的语言偏好重新包一层，
	 * 拿到的 Resources 保证是当前语言的，不依赖 attachBaseContext 那次性包装是否"粘住"。
	 */
	private static Resources getLocalizedResources() {
		Context ctx = LanguageUtil.wrap(MyApplication.getInstance());
		return ctx.getResources();
	}

	public static String getRsStr(int id) {
		return getLocalizedResources().getString(id);
	}

	/**
	 * 带格式化参数的资源字符串获取。语言相关的措辞/语序差异应该体现在 values / values-en 的
	 * 格式串本身（如 "%1$d号储罐..." vs "Tank %1$d..."），不要在调用方按语言分支拼字符串。
	 */
	public static String getRsStr(int id, Object... args) {
		return getLocalizedResources().getString(id, args);
	}

	public static Toast toast;

	public static void showRsToast(int id) {
		Message msg =Message.obtain();
		msg.obj=getRsStr(id);
		handler.sendMessage(msg);
	}
	
	public static void showRsToast(String info) {
		Message msg = Message.obtain();
		msg.obj=info;
		handler.sendMessage(msg);
		
	}

	public static String[] getStringsById(int id) {
		return getLocalizedResources().getStringArray(id);
	}

	/**
	 * 用户在基础设置里选的单位偏好是以中文存储的（"毫米"/"米"/"升"/"立方"/"千克"/"吨"，
	 * 见 Constans.YeWeiUnit/RongJiUnit/ZhiLiangUnit），拼进界面显示前要按当前语言转换一下，
	 * 不要把存储值原样拼进去。存储值本身、以及跟它比较的判断逻辑不用变——单位偏好跟界面语言
	 * 是两回事，内部一直用中文存储/比较是自洽的，只有"给用户看的这一步"需要本地化。
	 * OilCanFragment、MinOilCanView 共用这一份，不要各自再写一遍 switch。
	 */
	/**
	 * @param zhUnit 可能是 UnitPref 的规范值（"MM"/"M"/"L"/"CUBIC"/"KG"/"TON"），也可能是历史遗留的
	 *               中文原文，或是曾经被 bug 误存的英文——三种都要认得，见 UnitPref 类注释。
	 */
	public static String localizedUnit(String zhUnit) {
		switch (zhUnit) {
			case "毫米":
			case "MM":
			case "mm":
				return getRsStr(R.string.mm);
			case "米":
			case "M":
			case "m":
				return getRsStr(R.string.unit_m);
			case "升":
			case "L":
				return getRsStr(R.string.unit_l);
			case "立方":
			case "CUBIC":
			case "m³":
			case "m3":
				return getRsStr(R.string.unit_cubic);
			case "千克":
			case "KG":
			case "kg":
				return getRsStr(R.string.unit_kg);
			case "吨":
			case "TON":
			case "t":
				return getRsStr(R.string.unit_ton);
			default:
				return zhUnit;
		}
	}
	
	

	/**
	 * 退出应用程序
	 */
	public static void AppExit(Context context) {
		try {

			ActivityManager activityMgr = (ActivityManager) context
					.getSystemService(Context.ACTIVITY_SERVICE);
			activityMgr.restartPackage(context.getPackageName());
			System.exit(0);
			android.os.Process.killProcess(android.os.Process.myPid());
		} catch (Exception e) {
		    Log.e("SystemUtils", "AppExit 执行失败", e);
		}
	}

	/**
	 * 获取缓存路径
	 */
	public static File getDiskCacheDir(Context context, String uniqueName) {
		String cachePath;
		if (Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState())
				|| !Environment.isExternalStorageRemovable()) {
			cachePath = context.getExternalCacheDir().getPath();
		} else {
			cachePath = context.getCacheDir().getPath();
		}
		return new File(cachePath + File.separator + uniqueName);
	}

	/**
	 * 得到版本 号
	 * 
	 * @return
	 */
	public static int getAppVersionCode(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(
					context.getPackageName(), 0);
			return info.versionCode;
		} catch (NameNotFoundException e) {
		    Log.e("SystemUtils", "getAppVersionCode 执行失败", e);
		}
		return 1;
	}

	/**
	 * 得到版本 号
	 *
	 * @return
	 */
	public static String getAppVersionName(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(
					context.getPackageName(), 0);
			return info.versionName;
		} catch (NameNotFoundException e) {
		    Log.e("SystemUtils", "getAppVersionName 执行失败", e);
		}
		return "1";
	}

	private static final String MOUNTS_FILE = "/proc/mounts";

	/**
	 * 判断U盘是否挂载上，用于读取android的挂载路径来判断
	 * 
	 * @param path
	 * @return
	 */
	public static boolean isMounted(String path) {

		boolean blnRet = false;
		String strLine = null;
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(MOUNTS_FILE));

			while ((strLine = reader.readLine()) != null) {
				if (strLine.contains(path)) {
					blnRet = true;

					break;
				}
			}
		} catch (Exception e) {
			//e.printStackTrace();
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
				    Log.e("SystemUtils", "isMounted 执行失败", e);
				}
				reader = null;
			}
		}
		return blnRet;
	}
	static Handler handler = new Handler(){
		public void handleMessage(android.os.Message msg) {
			String info = (String) msg.obj;
			if (toast == null) {
				toast = Toast.makeText(MyApplication.getInstance(), info,
						Toast.LENGTH_SHORT);
			} else {
				toast.setText(info);
			}
			toast.show();
		};
	};
	public static boolean isUsbExit() {
		// 存储到U盘
		String path = "/storage/usbhost1";
		if (isMounted(path)) {
			return true;
		}
		//SystemUtils.showRsToast(R.string.datamanager_chek_out);
		return false;
	}
	
	public static String getCpuID() {
		int[] data = new int[4];
		StringBuffer boardID = new StringBuffer();
		for (int i = 0; i < 3; i++) {
			boardID.append(data[i]);
		}
		return boardID.toString();
	}
	public static void getScreenInfo(Activity at){
		getResolution(at);
		getDensity(at);

	}
	public static void getResolution(Activity at){
		WindowManager windowManager = at.getWindowManager();
		Display display = windowManager.getDefaultDisplay();
		int screenWidth = screenWidth = display.getWidth();
		int screenHeight = screenHeight = display.getHeight();
		Log.i("SystemInfo", "手机分辨率"+screenHeight+"  "+screenWidth);

	}
	/**
	 * 得到设备密度
	 * @param ct
	 * @return
	 */
	public static void getDensity(Activity ct){
		DisplayMetrics dm = new DisplayMetrics();
		ct.getWindowManager().getDefaultDisplay().getMetrics(dm);
		Log.i("SystemInfo","手机密度"+dm.density);
	}

	public static String getWifiMac(Context ctx) {
		WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
		WifiInfo info = wifi.getConnectionInfo();
		String str = info.getMacAddress();
		if (str == null) str = "";
		return str;
	}


	public static boolean hideNavigation(Context ct){
		boolean ishide;
		try
		{
			String command;
			command = "LD_LIBRARY_PATH=/vendor/lib:/system/lib service call activity 42 s16 com.android.systemui";
			Process proc = Runtime.getRuntime().exec(new String[] { "su",
					"-c", command });
			proc.waitFor();
			ishide = true;
		}
		catch(Exception ex)
		{
			Toast.makeText(ct, ex.getMessage(),
					Toast.LENGTH_LONG).show();
			ishide = false;
		}
		return ishide;
	}

	/*显示虚拟按键*/
	public static boolean showNavigation(Context ct){
		boolean isshow;
		try
		{
			String command;
			command = "LD_LIBRARY_PATH=/vendor/lib:/system/lib am startservice -n com.android.systemui/.SystemUIService";
			Process proc = Runtime.getRuntime().exec(new String[] { "su",
					"-c", command });
			proc.waitFor();
			isshow = true;
		}
		catch (Exception e)
		{
			isshow = false;
			//e.printStackTrace();
		}
		return isshow;
	}
	/**
	 * 获得系统亮度
	 */
	public static int getSystemBrightness(Context ct) {
		int systemBrightness = 0;
		try {
			systemBrightness = Settings.System.getInt(ct.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
		} catch (Settings.SettingNotFoundException e) {
		    Log.e("SystemUtils", "getSystemBrightness 执行失败", e);
		}
		return systemBrightness;
	}


	public static void saveBrightness(ContentResolver resolver, int brightness) {
//		//改变系统的亮度值
//		//这里需要权限android.permission.WRITE_SETTINGS
//		//设置为手动调节模式
//		//TODO 暂时关闭
//		Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
//				Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
//		//保存到系统中
//		Uri uri = android.provider.Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
//		android.provider.Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
//		resolver.notifyChange(uri, null);
	}


	/**
	 * 唤醒屏幕
     */
	public static void LockScreen( Context ct) {
		try {
			Process su;
			DataOutputStream os;
			su = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(su.getOutputStream());
			os.writeBytes("echo on > /sys/power/state");
			os.writeBytes("exit\n");
			os.flush();
		} catch (Exception e) {
		    Log.e("SystemUtils", "LockScreen 执行失败", e);
		}
	}

	/**
	 * 熄灭屏幕
	 */
	public static void unLockScreen( Context ct) {
		try {
			Process su;
			DataOutputStream os;
			su = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(su.getOutputStream());
			os.writeBytes("echo mem > /sys/power/state");
			os.writeBytes("exit\n");
			os.flush();
		} catch (Exception e) {
		    Log.e("SystemUtils", "unLockScreen 执行失败", e);
		}
	}

	/**
	 * 隐藏虚拟按键，并且全屏
	 */
	public static void hideBottomUIMenu(Activity ac) {
		//隐藏虚拟按键，并且全屏
		if (Build.VERSION.SDK_INT > 11 && Build.VERSION.SDK_INT < 19) { // lower api
			View v = ac.getWindow().getDecorView();
			v.setSystemUiVisibility(View.GONE);
		} else if (Build.VERSION.SDK_INT >= 19) {
			//for new api versions.
			View decorView = ac.getWindow().getDecorView();
			int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
					| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN;
			decorView.setSystemUiVisibility(uiOptions);
		}
	}

	/**
	 * 直接调用短信接口发短信
	 * @param ActivityOrSetvice    Activity
	 * @param phoneNumber
	 * @param smsContent
	 */
	public static void sendMessageBySysterm(Context ActivityOrSetvice, String phoneNumber, String smsContent) {
		if (phoneNumber == null || phoneNumber.length() < 4) {
			return;
		}
		//获取短信管理器
		android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
		//拆分短信内容（手机短信长度限制）
		List<String> divideContents = smsManager.divideMessage(smsContent);
		for (String text : divideContents) {
			smsManager.sendTextMessage(phoneNumber, null, text, null, null);
		}
	}
	/**
	 * 判断 APP 是否安装
	 * @param context 活动对应的上下文对象
	 * @param packageName 需要检查的应用包名
	 * @return
	 */
	public static boolean isInstalled(Context context, String packageName) {
		final PackageManager packageManager = context.getPackageManager();//获取packagemanager
		List<PackageInfo> pInfo = packageManager.getInstalledPackages(0);//获取所有已安装程序的包信息
		List<String> pName = new ArrayList<>();//用于存储所有已安装程序的包名
		//从pinfo中将包名字逐一取出，压入pName list中
		if (pInfo != null) {
			for (int i = 0; i < pInfo.size(); i++) {
				String pn = pInfo.get(i).packageName;
				pName.add(pn);
			}
		}
		return pName.contains(packageName);//判断pName中是否有目标程序的包名，有TRUE，没有FALSE
	}

}
