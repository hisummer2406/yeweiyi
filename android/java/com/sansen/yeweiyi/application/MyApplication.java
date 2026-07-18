package com.sansen.yeweiyi.application;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.multidex.MultiDex;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.sansen.yeweiyi.base.BaseActivity;
import com.sansen.yeweiyi.bean.AlarmState;
import com.sansen.yeweiyi.bean.LevelmeterInfo;
import com.sansen.yeweiyi.crashAppLog.CrashApphandler;
import com.sansen.yeweiyi.db.Authorization;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;
import com.sansen.yeweiyi.logic.Config;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SaveOilManager;
import com.sansen.yeweiyi.manager.SerialPortManager;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import baidu.main.BaiduSpeechManager;
import log.LogCatHelper;

public class MyApplication extends Application {

	private static final String TAG = "MyApplication";
	private static MyApplication instance;
	public static Map<Integer, AlarmState> alarmStates;

	// 当前用户登录信息
	private User user;
	// 液位仪
	private LevelmeterInfo yeWeiYi;
	public LevelmeterInfo getYeWeiYi() {
		return yeWeiYi;
	}

	public void setYeWeiYi(LevelmeterInfo yeWeiYi) {
		this.yeWeiYi = yeWeiYi;
	}

	// 当前用户的权限信息
	private Authorization authorization;
	// 当前的语言
	private Locale curLocale;
	// 系统的语言
	private Locale systemLocale;
	// 图片加载工具
	private ImageLoader imageLoader;

	// 在Application中存储全局的管理器 提供get方法

	// 数据库对象
	private DaoHelp mDaoHelp;
	// 油罐数据管理器
	private OilcanDataManager mOilcanDataManager;
	// 串口管理器
	private SerialPortManager mSerialPortManager;
	// 数据库管理器
	private DBDataManager mDBManager;
	// 存油记录管理器
	private SaveOilManager mSaveOilManager;


	public static MyApplication getInstance() {

		return instance;
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(base);
		MultiDex.install(this);
	}

	@RequiresApi(api = Build.VERSION_CODES.M)
	@Override
	public void onCreate() {
		super.onCreate();

		instance = this;
//
// LeakCanary.install(instance);

		//languageWork();
	//	LogCatHelper.getInstance(this,null).start();
		//初始化异常捕捉
		CrashApphandler.getInstance().init(this.getApplicationContext());
		LogCatHelper.getInstance(this.getApplicationContext(),null).start();
		CRCheck.getInstance().init_all_tab();

		BaiduSpeechManager.getInstance(this);
		//RMC
		serialport.manager.SerialPortManager.getInstance();

		mDaoHelp.getInstance(instance);

		mDBManager = DBDataManager.getInstance();

		yeWeiYi = (LevelmeterInfo) mDBManager.findFirst(LevelmeterInfo.class);
		alarmStates = new HashMap<Integer, AlarmState>();

		mOilcanDataManager = OilcanDataManager.getInstance(instance);
		// 存油管理
		mSaveOilManager = SaveOilManager.getInstance(instance);

		mSerialPortManager = SerialPortManager.getInstance(instance);

		initImageLoader();
//		Log.i(TAG, "onCreate:语言 "+SharePrefUtil.getString(this,SharePrefUtil.LANGUAGE,"zh"));
		Locale locale = new Locale(SharePrefUtil.getString(this,SharePrefUtil.LANGUAGE,"zh"));
		setLocale(locale);
		BaseActivity.settingLanguage(locale);
	}


	public Authorization getAuthorization() {
		if (authorization == null) {
			authorization = new Authorization();
		}
		return authorization;
	}

	public void setAuthorization(Authorization authorization) {
		this.authorization = authorization;
	}

	public ImageLoader getImageLoader() {

		return imageLoader;
	}

	public User getUser() {
		return user;
	}



	public void setUser(User user) {
		this.user = user;
	}

	public SaveOilManager getmSaveOilManager() {
		return mSaveOilManager;
	}

	public void setmSaveOilManager(SaveOilManager mSaveOilManager) {
		this.mSaveOilManager = mSaveOilManager;
	}

	public DBDataManager getmDBManager() {
		return mDBManager;
	}

	public void setmDBManager(DBDataManager mDBManager) {
		this.mDBManager = mDBManager;
	}

	public SerialPortManager getmSerialPortManager() {
		return mSerialPortManager;
	}

	public OilcanDataManager getmOilcanDataManager() {
		return mOilcanDataManager;
	}

	public void setmOilcanDataManager(OilcanDataManager mOilcanDataManager) {
		this.mOilcanDataManager = mOilcanDataManager;

	}

	private void initImageLoader() {

		DisplayImageOptions options = Config.getDisplayImageOptions(this);
		ImageLoaderConfiguration imageLoader = Config.getImageLoader(this,
				options);
		ImageLoader.getInstance().init(imageLoader);
		this.imageLoader = ImageLoader.getInstance();
	}
	public void setLocale(Locale locale) {
		this.curLocale = locale;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	//	languageWork();
	}

	private void languageWork() {
		Locale locale = LanguageUtil.getLocale(this);
		LanguageUtil.updateLocale(this, locale);
	}
}
