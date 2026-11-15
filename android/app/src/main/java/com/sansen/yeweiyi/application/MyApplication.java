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
import com.sansen.yeweiyi.bean.AlarmState;
import com.sansen.yeweiyi.bean.LevelmeterInfo;
import com.sansen.yeweiyi.applog.AppLog;
import com.sansen.yeweiyi.applog.ProbeLog;
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
import com.sansen.yeweiyi.manager.SocketManager;
import com.sansen.yeweiyi.manager.PumpIoManager;
import com.sansen.yeweiyi.manager.SoundManager;
import com.sansen.yeweiyi.utils.CRCheck;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
		super.attachBaseContext(LanguageUtil.wrap(base));
		MultiDex.install(this);
	}

	@RequiresApi(api = Build.VERSION_CODES.M)
	@Override
	public void onCreate() {
		super.onCreate();

		instance = this;
		// 日志体系最先初始化，后面各模块初始化时的日志才能落盘
		AppLog.init(this);
		ProbeLog.start();
//
// LeakCanary.install(instance);

		languageWork();
	//	LogCatHelper.getInstance(this,null).start();
		//初始化异常捕捉
		CrashApphandler.getInstance().init(this.getApplicationContext());
		LogCatHelper.getInstance(this.getApplicationContext(),null).start();
		CRCheck.getInstance().init_all_tab();
		// 先固定串口 2/4 的启动配置，再启动 HAL 与采集线程，避免双显端口发出控制指令。
		// Singleton 同步这份运行配置；设置页保存的新协议在完整重启 App 后生效。
		com.sansen.yeweiyi.manager.ProbeProxyManager.init(this);
		com.sansen.yeweiyi.utils.Singleton.getInstance().getSetData(com.sansen.yeweiyi.utils.Constans.ChuanKou2);
		com.sansen.yeweiyi.utils.Singleton.getInstance().getSetData(com.sansen.yeweiyi.utils.Constans.ChuanKou4);
		//RMC
		serialport.manager.SerialPortManager.getInstance();

		mDaoHelp.getInstance(instance);
		SoundManager.getInstance().init(this);
		// 开机先断开 IO2（单罐开关量输出），再按设置联动
		PumpIoManager.init(this);

		mDBManager = DBDataManager.getInstance();

		yeWeiYi = (LevelmeterInfo) mDBManager.findFirst(LevelmeterInfo.class);
		alarmStates = new HashMap<Integer, AlarmState>();

		mOilcanDataManager = OilcanDataManager.getInstance(instance);
		// 存油管理
		mSaveOilManager = SaveOilManager.getInstance(instance);

		mSerialPortManager = SerialPortManager.getInstance(instance);

		// ThingsBoard 连接跑在 BackService 里，但 BackService 只在真正发生报警/出入库事件时才
		// 被 SocketManager 的懒加载单例间接启动（见 SocketManager.initData）——安静开机（长时间
		// 没有报警也没有出入库）会导致 30 秒遥测、MQTT 连接、RPC 订阅永远不会启动。这里在
		// Application 启动时就主动建实例，确保 BackService 跟着 App 生命周期起来，不依赖第一次
		// 业务事件当触发器（PR #38 Codex 审核）。
		SocketManager.getInstance(instance);

		initImageLoader();
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
