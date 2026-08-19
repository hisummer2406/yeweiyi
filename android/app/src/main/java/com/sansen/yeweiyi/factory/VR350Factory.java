package com.sansen.yeweiyi.factory;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SerialPortManager.PCPortThread;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.StringUtils;

import serialport.help.SerialPortHelper;

public class VR350Factory implements PCDataFactory {

	private static final int
			PCDATA_GETPROBEDATA = 0x06;
	private static final int BACKDATA = 0x100;
    public static final String VR350R ="VR350R";
	private Context mContext;
	private PCPortThread mReceiveThread;
	private OilCan oilCan;
	private String[] dataArrays;
	private String mOrginData;
	private OilcanDataManager mOilcanDataManager;

	private Handler handler;
	private SerialPortHelper mSerialPort;
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
					try {
						initData((String) msg.obj);
					} catch (Exception e) {
						Log.e("VR350Factory", "PC协议解析失败, 原始报文=" + msg.obj, e);
					}
					break;

				default:
					break;
				}
			}
		};
	}
	private void backOilcanData() {
		SparseArray<OilCan> mOilcanData;
		if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.TEST_DATA,false)){
			mOilcanData = mOilcanDataManager.getTestData();
		}else {
			mOilcanData = mOilcanDataManager.getData();
		}

		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer.append("01693230313030");// 命令头
		stringBuffer.append(getDate(DateUtils.getYYYMMddHHmm2()));// 时间

		for (int i=0;i<mOilcanData.size();i++) {
			OilCan oilCan = mOilcanData.valueAt(i);
			stringBuffer.append(DataUtils.str2AscHex(StringUtils.getFillUpCode(oilCan.getOilCanPos(), 2)));
			stringBuffer.append("30");//产品代码
			stringBuffer.append("30303030");// 油罐状态位
			stringBuffer.append("3037"); //后面16进制字段个数
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

		if (data.contains("69 32 30 31 30 30")) {
			backOilcanData();
		}
		else if (data.contains("08 03 03 E7")){
			backOilcanData2();
		}
	}
	private void backOilcanData2() {
		SparseArray<OilCan> mOilcanData;
		mOilcanData = mOilcanDataManager.getData();
		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer.append("0803");// 命令头
		stringBuffer.append(Integer.toHexString((mOilcanData.size())*24));
		for (int i=0;i<mOilcanData.size();i++) {
			OilCan oilCan = mOilcanData.valueAt(i);
			stringBuffer.append(DataUtils.floatToHex((float) oilCan.getOilLevel()));// Height油高
			stringBuffer.append(DataUtils.dou2AscHex((float)oilCan.getWaterLevel()));// Water水高
			stringBuffer.append(DataUtils.dou2AscHex((float)oilCan.getTemper()));// Temper温度
			stringBuffer.append(DataUtils.dou2AscHex((float)oilCan.getOilVolume()));
			stringBuffer.append("0000000000000000");
		}
		String crc= getString(CRCheck.crc16(DataUtils.HexString2Bytes(stringBuffer.toString())));
		stringBuffer.append(crc.substring(2));
		stringBuffer.append(crc.substring(0, 2));
		Log.e("TAG",stringBuffer.toString());
		mSerialPort.sendData(DataUtils.hexString2Bytes(stringBuffer.toString()));
	}
	public String getString(String data) {
		while (data.length() < 4) {
			data = 0 + data;
		}
		return data;
	}


	public void transportData(String data) {
		Message msg = Message.obtain();
		msg.obj = data;
		msg.what = BACKDATA;
		handler.sendMessage(msg);

	}
}
