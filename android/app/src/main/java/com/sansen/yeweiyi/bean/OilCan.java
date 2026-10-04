package com.sansen.yeweiyi.bean;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.Arrays;


public class OilCan {

	private double scaleHight=1;

	private double scaleVolume;

	private double scaleWeight;

	public double getScaleWeight() {
		return scaleWeight;
	}

	public void setScaleWeight(double scaleWeight) {
		this.scaleWeight = scaleWeight;
	}

	public double getScaleHight() {
		return scaleHight;
	}

	public int getInOilTime() {
		return inOilTime;
	}

	public void setScaleHight(double scaleHight) {
		this.scaleHight = scaleHight;
	}

	public double getScaleVolume() {
		return scaleVolume;
	}

	public void setScaleVolume(double scaleVolume) {
		this.scaleVolume = scaleVolume;
	}

	/**
	 * 罐表设置里的高 途径：罐表
	 */
	private int[] hightArray = { 0 };
	/**
	 * 罐表设置里的体积 途径：罐表
	 */
	private double[] litreArray = { 0 };
	/**
	 * 最大高度 5位整数 mm 途径：油罐设置表
	 */
	private int maxHeight = 0;
	/**
	 * 最大体积 8位 L 途径：油罐设置表
	 */
	private int maxVolume = 0;
	/**
	 * 密度 3位小数 0.xxx 途径：油罐设置表
	 */
	private double density = 0;
	/**
	 * 密度变化值
	 */
	private double densityVar = 0;
	public int enable=0;

	private boolean isUnload;
	private boolean isOutload;

	public boolean isOutload() {
		return isOutload;
	}

	public void setOutload(boolean outload) {
		isOutload = outload;
	}

	public boolean isUnload() {
		return isUnload;
	}

	public void setUnload(boolean unload) {
		isUnload = unload;
	}

	public int getEnable() {
		return enable;
	}

	public void setEnable(int enable) {
		this.enable = enable;
	}

	/**
	 * 报警信息
	 * @return
     */
	private String stateInfo;

	public String getStateInfo() {
		return stateInfo;
	}

	public void setStateInfo(int oilCanPos,int stateInfo) {
		// 罐号前缀的语序（"3xxx" vs "No.3xxx"）由 oilcan_pos_prefix_format 资源决定，
		// 不在这里按语言分支拼字符串。
		String text = SystemUtils.getRsStr(stateInfo);
		this.stateInfo = oilCanPos == 0 ? text
				: SystemUtils.getRsStr(R.string.oilcan_pos_prefix_format, oilCanPos, text);
	}

	public double getDensityVar() {
		return densityVar;
	}


	/**
	 * 可添油重量
	 */
	private double canAddOilWeight = 0;

	/**
	 * 高油位报警 5位整数 mm 途径：油罐设置表
	 */
	private int highLevelAlarm = 0;


	/**
	 * 低油位报警 5位整数 mm 途径：油罐设置表
	 */
	private int lowLevelAlarm = 0;
	
	private double testOilLevel;
	
	private double testWaterLevel;

	private double mapperRadio;
	private String jieZhi;

	public String getJieZhi() {
		return jieZhi;
	}

	public void setJieZhi(String jieZhi) {
		this.jieZhi = jieZhi;
	}

	public double getTestOilLevel() {
		return testOilLevel;
	}

	public void setTestOilLevel(double testOilLevel) {
		this.testOilLevel = testOilLevel;
	}

	public double getTestWaterLevel() {
		return testWaterLevel;
	}

	public void setTestWaterLevel(double testWaterLevel) {
		this.testWaterLevel = testWaterLevel;
	}

	public int getHhighLevelAlarm() {
		return hhighLevelAlarm;
	}

	public void setHhighLevelAlarm(int hhighLevelAlarm) {
		this.hhighLevelAlarm = hhighLevelAlarm;
	}

	public int getLlowLevelAlarm() {
		return llowLevelAlarm;
	}

	public void setLlowLevelAlarm(int llowLevelAlarm) {
		this.llowLevelAlarm = llowLevelAlarm;
	}

	public double getMapperRadio() {
		return mapperRadio;
	}

	public void setMapperRadio(double mapperRadio) {
		this.mapperRadio = mapperRadio;
	}


	/**
	 * 高高油位报警
	 */
	private int hhighLevelAlarm = 0;
	/**
	 * 低油位报警
	 */
	private int llowLevelAlarm = 0;
	/**
	 * 高水位报警 5位整数 mm 途径：油罐设置表
	 */
	private int highWaterLevelAlarm = 0;
	/**
	 * 高温度报警 3位整数 途径：油罐设置表
	 */
	private int highTempAlarm = 0;
	/**
	 * 低温度报警 3位整数 途径：油罐设置表
	 */
	private int lowTempAlarm = 0;
	/**
	 * 油位补偿 3位整数 mm 途径：油罐设置表
	 */
	private double LevelCorrection = 0;
	/**
	 * 水位补偿 3位整数 mm 途径：油罐设置表
	 */
	private double waterLevelCorrection = 0;

	/**
	 * 设置油面高度
	 */
	private double oilHeight=0;

	public double getOilHeight() {
		return oilHeight;
	}

	public void setOilHeight(double oilHeight) {
		this.oilHeight = oilHeight;
	}

	/**
	 * 温度校正 2整 2位小数
	 */
	private double tempCorrection = 0;
	/**
	 * 报警状态 0无报警 1报警
	 */
	private int alarmState;

	/**
	 * 油位显示高度比例
	 * 
	 * @return
	 */
	private int oilRatio;

	/**
	 * 水位显示高度比例
	 * 
	 * @return
	 */
	private int waterRatio;
	
	/**
	 * 油气值
	 */
	private int gasExploration;
	
	private String port;
	
	public double getSurPlus() {
		return surPlus;
	}

	public void setSurPlus(double surPlus) {
		this.surPlus = surPlus;
	}

	private double surPlus;
	/**
	 * 上一次更新时间
	 * @return
	 */
	private long lastTime;

	public long getLastTime() {
		return lastTime;
	}

	public void setLastTime(long lastTime) {
		this.lastTime = lastTime;
	}

	public String getPort() {
		return port;
	}

	public void setPort(String port) {
		this.port = port;
	}

	public int getOilRatio() {
		return oilRatio;
	}
	
	public int getGasExploration() {
		return gasExploration;
	}

	public void setGasExploration(int gasExploration) {
		this.gasExploration = gasExploration;
	}

	public void setOilRatio(int oilRatio) {
		this.oilRatio = oilRatio;
	}

	public int getWaterRatio() {
		return waterRatio;
	}

	public void setWaterRatio(int waterRatio) {
		this.waterRatio = waterRatio;
	}

	public int getAlarmState() {
		return alarmState;
	}

	public void setAlarmState(int alarmState) {
		this.alarmState = alarmState;
	}

	/**
	 * 油品名称 途径：油罐设置表
	 */
	private int oilType = 0;

	/**
	 * 油品名称 途径：油罐设置表
	 */
	private String oilType2;

	/**
	 * 探针类型
	 */
	private int probeType = 0;

	public int getProbeType() {
		return Singleton.getInstance().ChuanKou2;
	}

	public void setProbeType(int probeType) {
		this.probeType = probeType;
	}

	/**
	 * 油品颜色 默认为绿色 途径：油罐设置表
	 */
	private int oilColor = 0;

	/**
	 * 油位 mm 探针获取
	 */
	private double oilLevel = 0;
	private double waterLevel = 0;

	/**
	 * 允许报警位  0不报警 1报警
	 */
	private int alarmByte = 0;
	/**
	 * 存油体积
	 */
	private double oilVolume = 0;
	/**
	 * 水体积
	 */
	private double waterVolume = 0;
	/**
	 * 存油重量
	 */
	private double oilWeight = 0;
	/**
	 * 储罐类型 0,卧罐,1立罐
	 */
	private int gunType=0;

	public int getGunType() {
		return gunType;
	}

	public void setGunType(int gunType) {
		this.gunType = gunType;
	}

	public void setStateInfo(String stateInfo) {
		this.stateInfo = stateInfo;
	}

	public double getCanAddOilWeight() {
		return canAddOilWeight;
	}

	public void setCanAddOilWeight(double canAddOilWeight) {
		this.canAddOilWeight = canAddOilWeight;
	}

	public void setDensityVar(double densityVar) {
		this.densityVar = densityVar;
	}

	public double getOilVolume() {
		return oilVolume;
	}

	public void setOilVolume(double oilVolume) {
		this.oilVolume = oilVolume;
	}

	public double getWaterVolume() {
		return waterVolume;
	}

	public void setWaterVolume(double waterVolume) {
		this.waterVolume = waterVolume;
	}

	public double getOilWeight() {
		return oilWeight;
	}

	public void setOilWeight(double oilWeight) {
		this.oilWeight = oilWeight;
	}

	/**
	 * 气温平均值
	 */

	private double temper;

	private double temper1;
	private double temper2;
	private double temper3;
	private double temper4;
	private double temper5;
	private double temper6;
	public int inOilTime=0;


	public void setInOilTime(int inOilTime) {
		this.inOilTime = inOilTime;
	}

	/**
	 * 探针的地址 途径：探针对应表
	 */
	private String probeAdr;

	/**
	 * 油罐的id 途径：探针对应表
	 */
	private int oilCanPos;

	private int errorTimes;

	public int[] getHightArray() {
		return hightArray;
	}

	public void setHightArray(int[] hightArray) {
		this.hightArray = hightArray;
	}

	public double[] getLitreArray() {
		return litreArray;
	}

	public void setLitreArray(double[] litreArray) {
		this.litreArray = litreArray;
	}

	public int getMaxHeight() {
		return maxHeight;
	}

	public void setMaxHeight(int maxHeight) {
		this.maxHeight = maxHeight;
	}

	public int getMaxVolume() {
		return maxVolume;
	}

	public void setMaxVolume(int maxVolume) {
		this.maxVolume = maxVolume;
	}

	public double getDensity() {
		return density;
	}

	public void setDensity(double density) {
		this.density = density;
	}

	public int getHighLevelAlarm() {
		return highLevelAlarm;
	}

	public void setHighLevelAlarm(int highLevelAlarm) {
		this.highLevelAlarm = highLevelAlarm;
	}

	public int getLowLevelAlarm() {
		return lowLevelAlarm;
	}

	public void setLowLevelAlarm(int lowLevelAlarm) {
		this.lowLevelAlarm = lowLevelAlarm;
	}

	public int getHighWaterLevelAlarm() {
		return highWaterLevelAlarm;
	}

	public void setHighWaterLevelAlarm(int highWaterLevelAlarm) {
		this.highWaterLevelAlarm = highWaterLevelAlarm;
	}

	public int getHighTempAlarm() {
		return highTempAlarm;
	}

	public void setHighTempAlarm(int highTempAlarm) {
		this.highTempAlarm = highTempAlarm;
	}

	public double getLevelCorrection() {
		return LevelCorrection;
	}

	public void setLevelCorrection(double levelCorrection) {
		LevelCorrection = levelCorrection;
	}

	public double getWaterLevelCorrection() {
		return waterLevelCorrection;
	}

	public void setWaterLevelCorrection(double waterLevelCorrection) {
		this.waterLevelCorrection = waterLevelCorrection;
	}

	public double getTempCorrection() {
		return tempCorrection;
	}

	public void setTempCorrection(double tempCorrection) {
		this.tempCorrection = tempCorrection;
	}

	public int getOilType() {
		return oilType;
	}

	public void setOilType(int oilType) {
		this.oilType = oilType;
	}

	public int getOilColor() {
		return oilColor;
	}

	public void setOilColor(int oilColor) {
		this.oilColor = oilColor;
	}

	public double getOilLevel() {
		return oilLevel;
	}

	public int getAlarmByte() {
		return alarmByte;
	}

	public void setAlarmByte(int alarmByte) {
		this.alarmByte = alarmByte;
	}

	public void setOilLevel(double oilLevel) {
		this.oilLevel = oilLevel;
	}

	public double getWaterLevel() {
		return waterLevel;
	}

	public void setWaterLevel(double waterLevel) {
		this.waterLevel = waterLevel;
	}

	public double getTemper() {
		return temper;
	}

	public void setTemper(double temper) {
		this.temper = temper;
	}

	public double getTemper1() {
		return temper1;
	}

	public void setTemper1(double temper1) {
		this.temper1 = temper1;
	}

	public double getTemper2() {
		return temper2;
	}

	public void setTemper2(double temper2) {
		this.temper2 = temper2;
	}

	public double getTemper3() {
		return temper3;
	}

	public void setTemper3(double temper3) {
		this.temper3 = temper3;
	}

	public double getTemper4() {
		return temper4;
	}

	public void setTemper4(double temper4) {
		this.temper4 = temper4;
	}

	public double getTemper5() {
		return temper5;
	}

	public void setTemper5(double temper5) {
		this.temper5 = temper5;
	}

	public double getTemper6() {
		return temper6;
	}

	public void setTemper6(double temper6) {
		this.temper6 = temper6;
	}

	public String getProbeAdr() {
		if (probeAdr.length()<=1){
			probeAdr="0"+probeAdr;
		}
		return probeAdr;
	}

	/**
	 * 探针ID（十进制输入）对应的协议地址字节，中成/LD 帧里的地址都是一个原始字节：
	 * ID 10 → 0x0A，而不是把 "10" 当十六进制读成 0x10。不在 1-255 内返回 -1。
	 * LD2（4-20mA）按十进制通道号匹配，仍用 {@link #getProbeAdr()}。
	 */
	public int getProbeAddressByte() {
		try {
			int address = Integer.parseInt(probeAdr.trim());
			return (address >= 1 && address <= 255) ? address : -1;
		} catch (NumberFormatException | NullPointerException e) {
			return -1;
		}
	}

	/** 协议帧里的两位大写十六进制地址（ID 10 → "0A"），地址无效返回 null。 */
	public String getProbeAddressHex() {
		int address = getProbeAddressByte();
		return address < 0 ? null : String.format("%02X", address);
	}

	public void setProbeAdr(String probeAdr) {
		this.probeAdr = probeAdr;
	}

	public int getOilCanPos() {
		return oilCanPos;
	}

	public void setOilCanPos(int oilCanPos) {
		this.oilCanPos = oilCanPos;
	}

	public int getErrorTimes() {
		return errorTimes;
	}

	public void setErrorTimes(int errorTimes) {
		this.errorTimes = errorTimes;
	}

	public OilCan() {
		super();
		// TODO Auto-generated constructor stub
	}

	public int getLowTempAlarm() {
		return lowTempAlarm;
	}

	public String getOilType2() {
		return oilType2;
	}

	public void setOilType2(String oilType2) {
		this.oilType2 = oilType2;
	}

	public void setLowTempAlarm(int lowTempAlarm) {
		this.lowTempAlarm = lowTempAlarm;
	}



	@Override
	public String toString() {
		return "OilCan [hightArray=" + Arrays.toString(hightArray)
				+ ", litreArray=" + Arrays.toString(litreArray)
				+ ", maxHeight=" + maxHeight + ", maxVolume=" + maxVolume
				+ ", density=" + density + ", densityVar=" + densityVar
				+ ", canAddOilWeight=" + canAddOilWeight + ", highLevelAlarm="
				+ highLevelAlarm + ", lowLevelAlarm=" + lowLevelAlarm
				+ ", testOilLevel=" + testOilLevel + ", testWaterLevel="
				+ testWaterLevel + ", hhighLevelAlarm=" + hhighLevelAlarm
				+ ", llowLevelAlarm=" + llowLevelAlarm
				+ ", highWaterLevelAlarm=" + highWaterLevelAlarm
				+ ", highTempAlarm=" + highTempAlarm + ", lowTempAlarm="
				+ lowTempAlarm + ", LevelCorrection=" + LevelCorrection
				+ ", waterLevelCorrection=" + waterLevelCorrection
				+ ", tempCorrection=" + tempCorrection + ", alarmState="
				+ alarmState + ", oilRatio=" + oilRatio + ", waterRatio="
				+ waterRatio + ", gasExploration=" + gasExploration + ", port="
				+ port + ", surPlus=" + surPlus + ", lastTime=" + lastTime
				+ ", oilType=" + oilType + ", probeType=" + probeType
				+ ", oilColor=" + oilColor + ", oilLevel=" + oilLevel
				+ ", waterLevel=" + waterLevel + ", alarmByte=" + alarmByte
				+ ", oilVolume=" + oilVolume + ", waterVolume=" + waterVolume
				+ ", oilWeight=" + oilWeight + ", temper=" + temper
				+ ", temper1=" + temper1 + ", temper2=" + temper2
				+ ", temper3=" + temper3 + ", temper4=" + temper4
				+ ", temper5=" + temper5 + ", temper6=" + temper6
				+ ", probeAdr=" + probeAdr + ", oilCanPos=" + oilCanPos
				+ ", errorTimes=" + errorTimes
				+ ", gunType=" + gunType
				+ ", enable=" + enable
				+ ", jieZhi=" + jieZhi
				+ ", inOilTime=" + inOilTime +"]";
	}

	
	
}
