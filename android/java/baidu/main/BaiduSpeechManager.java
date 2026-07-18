package baidu.main;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Pair;

import com.baidu.tts.chainofresponsibility.logger.LoggerProxy;
import com.baidu.tts.client.SpeechSynthesizer;
import com.baidu.tts.client.SpeechSynthesizerListener;
import com.baidu.tts.client.TtsMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import alarm.SoundManger;
import baidu.control.InitConfig;
import baidu.control.MySyntherizer;
import baidu.listener.UiMessageListener;
import baidu.util.OfflineResource;


public class  BaiduSpeechManager{

	private static BaiduSpeechManager instance;

	public static synchronized BaiduSpeechManager getInstance(Context ct){
			if (instance==null){
				instance = new BaiduSpeechManager(ct);
			}
		return instance;
	}

	// ================== 初始化参数设置开始 ==========================
	/**
	 * 发布时请替换成自己申请的appId appKey 和 secretKey。注意如果需要离线合成功能,请在您申请的应用中填写包名。
	 * 本demo的包名是com.baidu.tts.sample，定义在build.gradle中。
	 */
	protected String appId = "10915358";

	protected String appKey = "wEGH32zEMxTz8gpxBSKtgzbY";

	protected String secretKey = "G3xpL5O7wWyNXw3uXNh0b1lM31xFFk6k";

	// TtsMode.MIX; 离在线融合，在线优先； TtsMode.ONLINE 纯在线； 没有纯离线
	protected TtsMode ttsMode = TtsMode.MIX;

	// 离线发音选择，VOICE_FEMALE即为离线女声发音。
	// assets目录下bd_etts_speech_female.data为离线男声模型；bd_etts_speech_female.data为离线女声模型
	protected String offlineVoice = OfflineResource.VOICE_MALE;

	private Context ct;

	public BaiduSpeechManager(Context ct){
		this.ct=ct;
		initialTts();
	}

	// ===============初始化参数设置完毕，更多合成参数请至getParams()方法中设置 =================

	// 主控制类，所有合成控制方法从这个类开始
	protected MySyntherizer synthesizer;

	protected static String DESC = "请先看完说明。之后点击“合成并播放”按钮即可正常测试。\n"
			+ "测试离线合成功能需要首次联网。\n"
			+ "纯在线请修改代码里ttsMode为TtsMode.ONLINE， 没有纯离线。\n"
			+ "本Demo的默认参数设置为wifi情况下在线合成, 其它网络（包括4G）使用离线合成。 在线普通女声发音，离线男声发音.\n"
			+ "合成可以多次调用，SDK内部有缓存队列，会依次完成。\n\n";

	private static final String TAG = "SynthActivity";


	/**
	 * 初始化引擎，需要的参数均在InitConfig类里
	 * <p>
	 * DEMO中提供了3个SpeechSynthesizerListener的实现
	 * MessageListener 仅仅用log.i记录日志，在logcat中可以看见
	 * UiMessageListener 在MessageListener的基础上，对handler发送消息，实现UI的文字更新
	 * FileSaveListener 在UiMessageListener的基础上，使用 onSynthesizeDataArrived回调，获取音频流
	 */
	protected void initialTts() {
		LoggerProxy.printable(true); // 日志打印在logcat中
		// 设置初始化参数
		// 此处可以改为 含有您业务逻辑的SpeechSynthesizerListener的实现类
		SpeechSynthesizerListener listener = new UiMessageListener(mainHandler);

		Map<String, String> params = getParams();


		// appId appKey secretKey 网站上您申请的应用获取。注意使用离线合成功能的话，需要应用中填写您app的包名。包名在build.gradle中获取。
		InitConfig initConfig = new InitConfig(appId, appKey, secretKey, ttsMode, params, listener);

		// 如果您集成中出错，请将下面一段代码放在和demo中相同的位置，并复制InitConfig 和 AutoCheck到您的项目中
		// 上线时请删除AutoCheck的调用
//		AutoCheck.getInstance(ct).check(initConfig, new Handler() {
//			@Override
//			public void handleMessage(Message msg) {
//				if (msg.what == 100) {
//					AutoCheck autoCheck = (AutoCheck) msg.obj;
//					synchronized (autoCheck) {
//						String message = autoCheck.obtainDebugMessage();
//						//Log.i("AutoCheckMessage",message); // 可以用下面一行替代，在logcat中查看代码
//						// Log.w("AutoCheckMessage", message);
//					}
//				}
//			}
//
//		});
		synthesizer = new MySyntherizer(ct, initConfig, mainHandler); // 此处可以改为MySyntherizer 了解调用过程
	}


	/**
	 * 合成的参数，可以初始化时填写，也可以在合成前设置。
	 *
	 * @return
	 */
	protected Map<String, String> getParams() {
		Map<String, String> params = new HashMap<String, String>();
		// 以下参数均为选填
		// 设置在线发声音人： 0 普通女声（默认） 1 普通男声 2 特别男声 3 情感男声<度逍遥> 4 情感儿童声<度丫丫>
		params.put(SpeechSynthesizer.PARAM_SPEAKER, "0");
		// 设置合成	的音量，0-9 ，默认 5
		params.put(SpeechSynthesizer.PARAM_VOLUME, "5");
		// 设置合成的语速，0-9 ，默认 5
		params.put(SpeechSynthesizer.PARAM_SPEED, "6");
		// 设置合成的语调，0-9 ，默认 5
		params.put(SpeechSynthesizer.PARAM_PITCH, "5");

		params.put(SpeechSynthesizer.PARAM_MIX_MODE, SpeechSynthesizer.MIX_MODE_HIGH_SPEED_SYNTHESIZE_WIFI);
		// 该参数设置为TtsMode.MIX生效。即纯在线模式不生效。
		// MIX_MODE_DEFAULT 默认 ，wifi状态下使用在线，非wifi离线。在线状态下，请求超时6s自动转离线
		// MIX_MODE_HIGH_SPEED_SYNTHESIZE_WIFI wifi状态下使用在线，非wifi离线。在线状态下， 请求超时1.2s自动转离线
		// MIX_MODE_HIGH_SPEED_NETWORK ， 3G 4G wifi状态下使用在线，其它状态离线。在线状态下，请求超时1.2s自动转离线
		// MIX_MODE_HIGH_SPEED_SYNTHESIZE, 2G 3G 4G wifi状态下使用在线，其它状态离线。在线状态下，请求超时1.2s自动转离线

		// 离线资源文件
		OfflineResource offlineResource = createOfflineResource(offlineVoice);
		// 声学模型文件路径 (离线引擎使用), 请确认下面两个文件存在
		params.put(SpeechSynthesizer.PARAM_TTS_TEXT_MODEL_FILE, offlineResource.getTextFilename());
		params.put(SpeechSynthesizer.PARAM_TTS_SPEECH_MODEL_FILE,
				offlineResource.getModelFilename());
		return params;
	}


	protected OfflineResource createOfflineResource(String voiceType) {
		OfflineResource offlineResource = null;
		try {
			offlineResource = new OfflineResource(ct, voiceType);
		} catch (IOException e) {
			// IO 错误自行处理
			//e.printStackTrace();
			//Log.i("错误","【error】:copy files from assets failed." + e.getMessage());
		}
		return offlineResource;
	}
	public Queue<String> soundPool=new LinkedList<String>();


	/**
	 * speak 实际上是调用 synthesize后，获取音频流，然后播放。
	 * 获取音频流的方式见SaveFileActivity及FileSaveListener
	 * 需要合成的文本text的长度不能超过1024个GBK字节。
	 */
	public  void speak(String str) {
		synthesizer.speak(str);
	}
	public boolean isSpeak; //false 没有播放  true  播放
	/**
	 */
	public synchronized void addSpeakPool(String str) {
		if (soundPool.size()==0){
			if (isSpeak){
				if (!soundPool.contains(str))
					soundPool.add(str);
			}else{
				synthesizer.speak(str);
				isSpeak=true;
			}
		}else{
			if (!soundPool.contains(str))
				soundPool.add(str);
		}
	}


	/**
	 * 合成但是不播放，
	 * 音频流保存为文件的方法可以参见SaveFileActivity及FileSaveListener
	 */
	private void synthesize() {
		String text = "欢迎使用百度语音合成SDK,百度语音为你提供支持。";
		int result = synthesizer.synthesize(text);
		checkResult(result, "synthesize");
	}

	/**
	 * 批量播放
	 */
	private void batchSpeak() {
		List<Pair<String, String>> texts = new ArrayList<Pair<String, String>>();
		texts.add(new Pair<String, String>("开始批量播放，", "a0"));
		texts.add(new Pair<String, String>("123456，", "a1"));
		texts.add(new Pair<String, String>("欢迎使用百度语音，，，", "a2"));
		texts.add(new Pair<String, String>("重(chong2)量这个是多音字示例", "a3"));
		int result = synthesizer.batchSpeak(texts);
		checkResult(result, "batchSpeak");
	}


	/**
	 * 切换离线发音。注意需要添加额外的判断：引擎在合成时该方法不能调用
	 */
	public void loadModel() {
		if (offlineVoice.equals(OfflineResource.VOICE_FEMALE)) {
			offlineVoice = OfflineResource.VOICE_MALE;
		} else {
			offlineVoice = OfflineResource.VOICE_FEMALE;
		}
		OfflineResource offlineResource = createOfflineResource(offlineVoice);
		int result = synthesizer.loadModel(offlineResource.getModelFilename(), offlineResource.getTextFilename());
		checkResult(result, "loadModel");
	}

	private void checkResult(int result, String method) {
		if (result != 0) {
			//Log.i(TAG, "error code :" + result + " method:" + method + ", 错误码文档:http://yuyin.baidu.com/docs/tts/122 ");
		}
	}


	/**
	 * 暂停播放。仅调用speak后生效
	 */
	private void pause() {
		int result = synthesizer.pause();
		checkResult(result, "pause");
	}

	/**
	 * 继续播放。仅调用speak后生效，调用pause生效
	 */
	private void resume() {
		int result = synthesizer.resume();
		checkResult(result, "resume");
	}

	/*
     * 停止合成引擎。即停止播放，合成，清空内部合成队列。
     */
	public void stop() {
		int result = synthesizer.stop();
		checkResult(result, "stop");
	}


	public void release() {
		synthesizer.release();
		//Log.i(TAG, "释放资源成功");
	}


	private final Handler mainHandler = new Handler() {
		/*
         * @param msg
         */
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			if (msg.obj != null) {
				//Log.i(TAG, "handleMessage: "+msg.obj.toString());
			}
			if (msg.what==200){
				//Log.i(TAG, "handleMessage: 播放完成——池子中的数据"+soundPool.size());
				String str=soundPool.poll();
				if (str==null){
					isSpeak=false;
				}else{
					synthesizer.speak(str);
					isSpeak=true;
				}

			}
		}

	};
}
