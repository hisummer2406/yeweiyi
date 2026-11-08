package com.sansen.yeweiyi.factory;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.applog.ExternalLog;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SerialPortManager.PCPortThread;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;

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
						ExternalLog.onPcParseFailed(msg.obj, e);
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
			return;
		}
		ZhongChengModbusSlave.Request request =
				ZhongChengModbusSlave.findRequest(DataUtils.hexString2Bytes(data.replace(" ", "")));
		if (request != null) {
			backOilcanData2(request);
		}
	}

	/** 文档寄存器表列到 12 号罐；实际配置的罐号更大时按实际扩展 */
	private static final int MODBUS_DOC_TANK_COUNT = 12;

	/** 对外 Modbus（《中成液位modbus协议》）：按请求的起始地址和寄存器数量应答 */
	private void backOilcanData2(ZhongChengModbusSlave.Request request) {
		byte[] response = buildModbusResponse(mOilcanDataManager, request);
		mSerialPort.sendData(response);
		ExternalLog.onSerialRequest(request, response);
	}

	/** 串口 5 与 Modbus TCP（{@link ModbusTcpServer}）共用的应答组帧，保证两条通道数据口径一致 */
	public static byte[] buildModbusResponse(OilcanDataManager oilcanDataManager,
			ZhongChengModbusSlave.Request request) {
		SparseArray<OilCan> mOilcanData = oilcanDataManager.getData();
		Map<Integer, float[]> tanks = new HashMap<>();
		int maxTank = MODBUS_DOC_TANK_COUNT;
		for (int i = 0; i < mOilcanData.size(); i++) {
			OilCan oilCan = mOilcanData.valueAt(i);
			int pos = oilCan.getOilCanPos();
			maxTank = Math.max(maxTank, pos);
			// 油体积按"油水体积 − 水体积"算。但探针解析（ZhongCheng/LD/LD2Company）从不算 waterVolume，
			// 唯一会算的 MinOilCanView 只改演示数据（getTestData，另一份对象），这里读的是 getData()，
			// 所以水体积恒为 0、油体积 = 油水体积（2026-10-18 真机确认）
			double totalVolume = oilCan.getOilVolume();
			double waterVolume = oilCan.getWaterVolume();
			tanks.put(pos, new float[]{
					// 高度按毫米发：文档举例是米，但现场客户 PLC 按毫米配置（2026-09-27 确认）
					(float) oilCan.getOilLevel(),            // 油高 mm
					(float) oilCan.getWaterLevel(),          // 水高 mm
					(float) oilCan.getTemper(),              // 温度 ℃
					(float) totalVolume,                     // 油水体积 L
					(float) Math.max(0, totalVolume - waterVolume), // 油体积 L
					(float) waterVolume,                     // 水体积 L（目前恒为 0）
			});
		}
		return ZhongChengModbusSlave.buildResponse(request, tanks, maxTank);
	}

	public void transportData(String data) {
		Message msg = Message.obtain();
		msg.obj = data;
		msg.what = BACKDATA;
		handler.sendMessage(msg);

	}
}
