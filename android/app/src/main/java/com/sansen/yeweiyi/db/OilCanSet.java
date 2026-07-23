package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NotNull;

public class OilCanSet {

	/**
	 * 有罐id 1-9
	 */

	@Id
	private int pk_oilCanId;

	private int pk_d2dId;
	/**
	 * 探针id  1-16
	 */
	@Column(column = "pk_tanZhenId")
	private int pk_tanZhenId;
	/**
	 * 设置油面高度
	 */
	@Column(column = "oilHeight")
	private double oilHeight;

	/**
	 * 油罐高度
	 */
	@Column(column = "height")
	private int height;
	/**
	 * 最大体积
	 */
	@Column(column = "maxVolume")
	private int maxVolume;


	/**
	 * 高油位报警
	 */

	@Column(column = "heightOilAlarm")
	private int heightOilAlarm;
	/**
	 * 高高油位报警
	 */
	@Column(column = "hheightOilAlarm")
	private int hheightOilAlarm;
	/**
	 * 低低油位报警
	 */
	@Column(column = "lowOilAlarm")
	private int lowOilAlarm;

	@Column(column = "llowOilAlarm")
	private int llowOilAlarm;
	/**
	 * 高水位报警
	 */
	@Column(column = "heightWaterAlarm")
	private int heightWaterAlarm;

	/**
	 * 高温度报警
	 */
	@Column(column = "highTempAlarm")
	private int highTempAlarm;
	/**
	 * 低温度报警
	 */
	@Column(column = "lowTempAlarm")
	private int lowTempAlarm;
	/**
	 * 油位补偿
	 */
	@Column(column = "oilCorrection")
	private double oilCorrection;
	/**
	 * 水位补偿
	 */
	@Column(column = "waterCorrection")
	private double waterCorrection;

	/**
	 * 温度补偿
	 */
	@Column(column = "tempCorrection")
	private double tempCorrection;

	/**
	 * 密度
	 */
	@Column(column = "density")
	private double density;

	/**
	 * 密度变化量
	 */
	@Column(column = "densVar")
	private double densVar;
	/**
	 * 报警允许位
	 */
	@Column(column = "alarmByte")
	private int alarmByte;
	/**
	 * 油品类型 0~
	 */
	@Column(column = "oilType")
	private int oilType;
	/**
	 * 油品颜色
	 */
	@Column(column = "oilColor")
	private int oilColor;

	@Column(column = "gmt_create")
	private String gmt_create;

	@Column(column = "gmt_modified")
	private String gmt_modified;
	/**
	 * 传感器长度
	 */
	@Column(column = "chuanGanQiHight")
	private float chuanGanQiHight;
	/**
	 * 储罐类型 0,卧罐,1立罐
	 */
	@Column(column = "gunType")
	private int gunType;

	@Column(column = "enable")
	@NotNull
	private int enable = 0;
	public OilCanSet() {
		super();
	}

	public float getChuanGanQiHight() {
		return chuanGanQiHight;
	}

	public void setChuanGanQiHight(float chuanGanQiHight) {
		this.chuanGanQiHight = chuanGanQiHight;
	}

	public int getGunType() {
		return gunType;
	}

	public void setGunType(int gunType) {
		this.gunType = gunType;
	}

	public String[] getParams() {
		String[] params = { "gmt_modified","gmt_create","pk_d2dId","pk_tanZhenId","pk_oilCanId", "height", "maxVolume", "heightOilAlarm","hheightOilAlarm",
				"lowOilAlarm", "llowOilAlarm","heightWaterAlarm", "highTempAlarm",
				"lowTempAlarm", "oilCorrection", "waterCorrection","tempCorrection" ,"density",
				"densVar", "alarmByte", "oilType", "oilColor","oilHeight","chuanGanQiHight","gunType","enable"};
		return params;
	}

	public int getEnable() {
		return enable;
	}

	public void setEnable(int enable) {
		this.enable = enable;
	}

	public int getPk_tanZhenId() {
		return pk_tanZhenId;
	}

	public void setPk_tanZhenId(int pk_tanZhenId) {
		this.pk_tanZhenId = pk_tanZhenId;
	}

	public int getOilColor() {
		return oilColor;
	}

	public void setOilColor(int oilColor) {
		this.oilColor = oilColor;
	}

	public int getPk_oilCanId() {
		return pk_oilCanId;
	}

	public void setPk_oilCanId(int pk_oilCanId) {
		this.pk_oilCanId = pk_oilCanId;
	}

	public int getPk_d2dId() {
		return pk_d2dId;
	}

	public void setPk_d2dId(int pk_d2dId) {
		this.pk_d2dId = pk_d2dId;
	}

	public double getOilHeight() {
		return oilHeight;
	}

	public void setOilHeight(double oilHeight) {
		this.oilHeight = oilHeight;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public int getMaxVolume() {
		return maxVolume;
	}

	public void setMaxVolume(int maxVolume) {
		this.maxVolume = maxVolume;
	}

	public int getHeightOilAlarm() {
		return heightOilAlarm;
	}

	public void setHeightOilAlarm(int heightOilAlarm) {
		this.heightOilAlarm = heightOilAlarm;
	}

	public int getHheightOilAlarm() {
		return hheightOilAlarm;
	}

	public void setHheightOilAlarm(int hheightOilAlarm) {
		this.hheightOilAlarm = hheightOilAlarm;
	}

	public int getLowOilAlarm() {
		return lowOilAlarm;
	}

	public void setLowOilAlarm(int lowOilAlarm) {
		this.lowOilAlarm = lowOilAlarm;
	}

	public int getLlowOilAlarm() {
		return llowOilAlarm;
	}

	public void setLlowOilAlarm(int llowOilAlarm) {
		this.llowOilAlarm = llowOilAlarm;
	}

	public int getHeightWaterAlarm() {
		return heightWaterAlarm;
	}

	public void setHeightWaterAlarm(int heightWaterAlarm) {
		this.heightWaterAlarm = heightWaterAlarm;
	}

	public int getHighTempAlarm() {
		return highTempAlarm;
	}

	public void setHighTempAlarm(int highTempAlarm) {
		this.highTempAlarm = highTempAlarm;
	}

	public int getLowTempAlarm() {
		return lowTempAlarm;
	}

	public void setLowTempAlarm(int lowTempAlarm) {
		this.lowTempAlarm = lowTempAlarm;
	}

	public double getOilCorrection() {
		return oilCorrection;
	}

	public void setOilCorrection(double oilCorrection) {
		this.oilCorrection = oilCorrection;
	}

	public double getWaterCorrection() {
		return waterCorrection;
	}

	public void setWaterCorrection(double waterCorrection) {
		this.waterCorrection = waterCorrection;
	}

	public double getTempCorrection() {
		return tempCorrection;
	}

	public void setTempCorrection(double tempCorrection) {
		this.tempCorrection = tempCorrection;
	}

	public double getDensity() {
		return density;
	}

	public void setDensity(double density) {
		this.density = density;
	}

	public double getDensVar() {
		return densVar;
	}

	public void setDensVar(double densVar) {
		this.densVar = densVar;
	}

	public int getAlarmByte() {
		return alarmByte;
	}

	public void setAlarmByte(int alarmByte) {
		this.alarmByte = alarmByte;
	}

	public int getOilType() {
		return oilType;
	}

	public void setOilType(int oilType) {
		this.oilType = oilType;
	}

	public String getGmt_create() {
		return gmt_create;
	}

	public String getGmt_modified() {
		return gmt_modified;
	}

	public void setGmt_create(String gmt_create) {
		this.gmt_create = gmt_create;
	}

	public void setGmt_modified(String gmt_modified) {
		this.gmt_modified = gmt_modified;
	}


	@Override
	public String toString() {
		return "OilCanSet{" +
				"pk_oilCanId=" + pk_oilCanId +
				", pk_d2dId=" + pk_d2dId +
				", pk_tanZhenId=" + pk_tanZhenId +
				", height=" + height +
				", oilHeight"+oilHeight+
				", maxVolume=" + maxVolume +
				", heightOilAlarm=" + heightOilAlarm +
				", hheightOilAlarm=" + hheightOilAlarm +
				", lowOilAlarm=" + lowOilAlarm +
				", llowOilAlarm=" + llowOilAlarm +
				", heightWaterAlarm=" + heightWaterAlarm +
				", highTempAlarm=" + highTempAlarm +
				", lowTempAlarm=" + lowTempAlarm +
				", oilCorrection=" + oilCorrection +
				", waterCorrection=" + waterCorrection +
				", tempCorrection=" + tempCorrection +
				", density=" + density +
				", densVar=" + densVar +
				", alarmByte=" + alarmByte +
				", oilType=" + oilType +
				", chuanGanQiHight=" + chuanGanQiHight +
				", gunType=" + gunType +
				", enable=" + enable +
				", oilColor=" + oilColor +
				", gmt_create='" + gmt_create + '\'' +
				", gmt_modified='" + gmt_modified + '\''
				+
				'}';
	}
}
