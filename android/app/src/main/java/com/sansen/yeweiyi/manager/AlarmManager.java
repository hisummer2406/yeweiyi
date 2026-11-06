package com.sansen.yeweiyi.manager;

import android.os.Handler;
import android.os.Looper;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.applog.AlarmLog;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.manager.ThreadManager.ThreadPoolProxy;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AlarmManager {

	private static final String TAG = "AlarmManager";


	private static AlarmManager mInstance;

	private ThreadPoolProxy shortPool;
	// 报警是3秒切换
	public static int alarmIntervalsTime = 3000;
	// 通知是5秒切换
	public static int noticesIntervalsTime = 5000;
	// 消息回调方法
	private MessageListener messageListener;
	// 报警检测
	private AlarmDetection alarmDetection;

	private BeginRunable beginRunable;
	/**
	 * 当前对象维护一个报警队列
	 */
	private Queue<OilMessage> alarmQueue;
	/**
	 * 当前对象维护一个通知队列（优先级高于报警队列）
	 */
	private Queue<OilMessage> noticesQueue;

	boolean lastOpenState = true;// 为了避免频繁调用jni
	private SoundLightManager soundLightMangager;

	public static AlarmManager getInstance() {
		if (mInstance == null) {
			mInstance = new AlarmManager();
		}
		return mInstance;
	}

	private AlarmManager() {
		// 初始化线程池
		beginRunable = new BeginRunable();
		shortPool = ThreadManager.getShortPool();
		// 初始化队列
		noticesQueue = new ConcurrentLinkedQueue<OilMessage>();
		alarmQueue = new ConcurrentLinkedQueue<OilMessage>();
		// 设置报警参数
		alarmIntervalsTime = SharePrefUtil.getInt(MyApplication.getInstance(), "alarmIntervalsTime",
				alarmIntervalsTime);

		noticesIntervalsTime = SharePrefUtil.getInt(MyApplication.getInstance(), "noticesIntervalsTime",
				noticesIntervalsTime);
		// 开始后,三秒后使用Handler执行线程
		handler.postDelayed(showAlarmRunnable, 3000);
		//soundLightMangager = SoundLightManager.getInstance();
	}

	/**
	 * 向报警消息队列中添加数据
	 */
	public void addAlarmState(OilMessage alarm) {
		// 云端报警上报的"产生"时刻：本地所有报警都经过这里。已经 ACTIVE 的不会重复上报。
		CloudAlarmReporter.getInstance().onRaised(alarm);
		AlarmLog.onAlarmRaised(alarm);
		if (!alarmQueue.contains(alarm)) {
			alarmQueue.add(alarm);
		}
	}

	/**
	 * 向通知消息队列中添加数据
	 */
	public void addNoticesState(OilMessage noticesState) {
		if (!noticesQueue.contains(noticesState)) {
			noticesQueue.add(noticesState);
		}
	}

	/**
	 * 用handler实现每隔几秒显示状态信息
	 */
	Handler handler = new Handler(Looper.getMainLooper());

	Runnable showAlarmRunnable = new Runnable() {
		@Override
		public void run() {
			// 如果通知消息里面有数据优先显示通知中的数据
			if (!noticesQueue.isEmpty()) {
				DBDataManager.getInstance().sendOilMsg(noticesQueue.peek());
				messageListener.getAlarmSate(noticesQueue.poll());
				handler.postDelayed(this, noticesIntervalsTime);
			} else {
				// 如果报警队列中
				OilMessage alarm = null;
				if (!alarmQueue.isEmpty() ) {
					alarm = alarmQueue.poll();
				}
				if (messageListener != null) {
					messageListener.getAlarmSate(alarm);
				}
				handler.postDelayed(this, alarmIntervalsTime);
			}
		}
	};

	/**
	 * 开始报警检测
	 */
	private void beginAlarmDetection() {
		alarmDetection = AlarmDetection.getInstance(mInstance);
		ThreadPoolProxy singlePool = ThreadManager.getSinglePool();
	}

	/**
	 * 设置报警监听
	 * 
	 * @param messageListener
	 */
	public void setMessageListener(MessageListener messageListener) {
		this.messageListener = messageListener;
	}

	public interface MessageListener {
		void getAlarmSate(OilMessage state);
	}

	// 需要优化，不能每次就开启一个线程
	public void reshOilCan(final OilCan oilCan) {

		if (alarmDetection != null) {

			beginRunable.setData(oilCan);

			shortPool.execute(beginRunable);
		}
	}

	class BeginRunable implements Runnable {
		private OilCan oilCan;

		public void setData(OilCan oilCan) {
			this.oilCan = oilCan;
		}

		@Override
		public void run() {
			alarmDetection.beginDection(oilCan);
		}

	}

	public void beginDetection() {
		beginAlarmDetection();
	}

	
}
