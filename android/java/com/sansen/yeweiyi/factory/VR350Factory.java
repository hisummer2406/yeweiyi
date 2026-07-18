package com.sansen.yeweiyi.factory;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SerialPortManager.PCPortThread;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.StringUtils;

import serialport.help.SerialPortHelper;

/**
 * 
 *         该工厂类用于解析探针的数据
 *
 */
public class VR350Factory implements PCDataFactory {

	private static final int
			PCDATA_GETPROBEDATA = 0x06;
	private static final int BACKDATA = 0x100;
    public static final String VR350R ="VR350R";
	private Context mContext;

	private PCPortThread mReceiveThread;

	private OilCan oilCan;

	private String[] dataArrays;

	/**
	 * 去除空格的数据 用于检验CRC
	 */
	private String mOrginData;

	private OilcanDataManager mOilcanDataManager;

	private Handler handler;
	private SerialPortHelper mSerialPort;

	/**
	 * 数据解析用到的工厂对象
	 * 
	 * @param context
	 */
	public VR350Factory(Context context, PCPortThread receiveThread) {

		mContext = context;
		mReceiveThread = receiveThread;
		mSerialPort = mReceiveThread.getSerialPort();
		mOilcanDataManager = OilcanDataManager.getInstance(mContext);
		HandlerThread handlerThread = new HandlerThread("VR350Factory");
		handlerThread.start();
		handler = new Handler(handlerThread.getLooper()) {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case BACKDATA:
				//	Log.i("后台系统", "后台协议VR350R: "+msg.obj);
					initData((String) msg.obj);
					break;

				default:
					break;
				}
			}
		};
	}

	/**
	 * 
	 * 0x06 1 Hex 命令字 有效数据长度 1 Hex 1 探针号 1 HEX 1 1～12 探针的地址 1 Hex 1 0x81~0x9F
	 * 探针的原始数据 8 Hex 8 8个原探针的数据 液位仪油高数据 3 Hex 液位仪水高数据 3 Hex 油体积 3 Hex 水体积 3 Hex
	 * 可卸油量（升） 3 Hex 液位仪温度 2 Hex 预留 6 Hex CRC校验 2 HEX
	 * 从命令字到有效数据的CRC值，不含3个0xfc,高字节在前，低字节在后 CRC校验
	 *
	 */
	private void backOilcanData() {
		SparseArray<OilCan> mOilcanData;
		if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.TEST_DATA,false)){
//			int tempHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getTestData(oilInOut.getOilId()).getMaxHeight();
//			int curHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getMaxHeight();
//			radio=tempHeight/(double)curHeight;

			mOilcanData = mOilcanDataManager.getTestData();
		}else {
			mOilcanData = mOilcanDataManager.getData();
		}

		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer.append("01693230313030");// 命令头
		stringBuffer.append(getDate(DateUtils.getYYYMMddHHmm2()));// 时间

		for (int i=0;i<mOilcanData.size();i++) {
			// 30 31 //TT
			//
			// 30 //P 油品代码 30 以上
			// 30 30 30 30 //SSSS 油罐什么状态
			// 30 37 //0X07
			//
			// 34 36 37 43 41 32 45 46 //VOLUME 油体积
			// 34 36 37 43 41 32 45 46 //TC_VOLUME 油温度补偿后 0000
			// 34 36 34 38 37 44 31 31 //Ullage 罐空
			// 34 34 38 36 42 44 34 43 //Height 油高
			// 34 32 44 46 46 43 44 39 //Water //42DFFCD9 水高
			// 34 31 44 37 43 41 41 36 //Temper 温度
			// 34 34 44 31 46 44 30 42 //水体积

			OilCan oilCan = mOilcanData.valueAt(i);
			stringBuffer.append(DataUtils.str2AscHex(StringUtils.getFillUpCode(oilCan.getOilCanPos(), 2)));// TT
																										// 油罐号
			stringBuffer.append("30");//产品代码
			stringBuffer.append("30303030");// 油罐状态位
			stringBuffer.append("3037"); //后面16进制字段个数

			/*****************************/
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getOilVolume()));// VOLUME油体积
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getOilVolume()));// TC_VOLUME油温度补偿后
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getCanAddOilWeight()));// Ullage罐空
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getOilLevel()));// Height油高
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getWaterLevel()));// Water水高
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getTemper()));// Temper温度
			stringBuffer.append(DataUtils.dou2AscHex(oilCan.getWaterVolume()));// 水体积

		}
		stringBuffer.append("2626");// &&
		stringBuffer.append(DataUtils.getCrcPC(stringBuffer.toString()));
		stringBuffer.append("03"); // 0X03 结束标记

		mSerialPort.sendData(DataUtils.hexString2Bytes(stringBuffer.toString()));
	}

	public String getDate(String date){
		StringBuffer sb =new StringBuffer();
		int i=0;
		while(i<date.length()){
			sb.append(Integer.parseInt(date.substring(i,i+1))+30);
			i++;
		}
		return sb.toString();
	}

	public void initData(String data) {

		if (data.contains("01 69 32 30 31 30 30")) {
			backOilcanData();
		}
	}

	public void transportData(String data) {
		Message msg = Message.obtain();
		msg.obj = data;
		msg.what = BACKDATA;
		handler.sendMessage(msg);

	}
}
