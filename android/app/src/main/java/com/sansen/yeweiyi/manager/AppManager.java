package com.sansen.yeweiyi.manager;

import android.util.Log;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import java.util.Stack;

public class AppManager {

	private static Stack<Activity> activityStack;
	private static AppManager instance;

	private AppManager() {
	}

	/**
	 * 单一实例
	 */
	public static AppManager getAppManager() {
		if (instance == null) {
			instance = new AppManager();
		}
		return instance;
	}

	/**
	 * 添加Activity到堆
	 */
	public void addActivity(Activity activity) {
		if (activityStack == null) {
			activityStack =	 new Stack<Activity>();
		}
		activityStack.add(activity);
	}

	/**
	 * 获取当前Activity（堆栈中压入的）
	 */
	public Activity currentActivity() {
		Activity activity = activityStack.lastElement();
		return activity;
	}

	/**
	 * 结束当前Activity（堆栈中压入的）
	 */
	public void finishActivity() {
		Activity activity = activityStack.lastElement();
		finishActivity(activity);
	}

	/**
	 * 结束指定的Activity
	 */
	public void finishActivity(Activity activity) {
		if (activity != null) {
			activityStack.remove(activity);
			activity.finish();
			activity = null;
		}
	}

	/**
	 * 结束指定类名的Activity
	 */
	public void finishActivity(Class<?> cls) {
		for (Activity activity : activityStack) {
			if (activity.getClass().equals(cls)) {
				finishActivity(activity);
			}
		}
	}

	/**
	 * 结束Activity
	 */
	public void finishAllActivity() {
		for (int i = 0, size = activityStack.size(); i < size; i++) {
			if (null != activityStack.get(i)) {
				activityStack.get(i).finish();
			}
		}
		activityStack.clear();
	}

	/**
	 * 退出应用程序
	 */
	public void AppExit(Context context) {
		try {
			finishAllActivity();
			ActivityManager activityMgr = (ActivityManager) context
					.getSystemService(Context.ACTIVITY_SERVICE);
			activityMgr.restartPackage(context.getPackageName());
			System.exit(0);
			android.os.Process.killProcess(android.os.Process.myPid());
		} catch (Exception e) {
		    Log.e("AppManager", "AppExit 执行失败", e);
		}
	}

	/**
	 * 整体重启应用（用于语言切换等必须让所有 Resources/Configuration 从零生效的场景）。
	 *
	 * 不能用 Activity.recreate()：它只是销毁重建同一个 Activity，会触发系统的
	 * onSaveInstanceState → FragmentManager 状态恢复流程，对本 app 里已经打开过一次、
	 * 但只有带参构造函数没有无参构造函数的 Fragment（如 OilCanFragment，见其
	 * @SuppressLint("ValidFragment") 标注）用反射调用无参构造函数时会抛
	 * NoSuchMethodException，直接把整个 Activity 崩掉。
	 *
	 * 这里改成拉起一个全新的启动 Intent、再 finish() 掉当前 Activity：新启动的
	 * MainActivity.onCreate() 拿到的 savedInstanceState 一定是 null，不会走
	 * FragmentManager 的反射恢复逻辑，从根上避免了这类 Fragment 缺无参构造函数的问题被触发。
	 */
	public void restartApp(Context context) {
		try {
			Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				context.startActivity(intent);
				// 如果当前 context 是 Activity，关闭当前界面并加上过渡动画
				if (context instanceof  Activity) {
					// 平滑淡出淡入动画
//					(Activity)context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
					((Activity)context).finish();
				}
			}
		} catch (Exception e) {
			Log.e("AppManager", "restartApp 执行失败", e);
		}
	}
}