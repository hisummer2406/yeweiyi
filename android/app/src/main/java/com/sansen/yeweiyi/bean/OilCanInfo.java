package com.sansen.yeweiyi.bean;


import java.io.Serializable;

public class OilCanInfo implements Serializable {
	
	private int id;
	private String oilType;
	private double oilLevel;
	private double oilVolume;
	private double oilweight;
	private double addibleOil;
	private double waterLevel;
	private double waterVolume;
	private double temperature;
	private String connectState;
	private String probeType;
	private int probeAddress;
	private String alarmState;
	private int oilRatio;
	private int waterRatio;
	private int oilColor;
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public String getOilType() {
		return oilType;
	}
	public void setOilType(String oilType) {
		this.oilType = oilType;
	}
	public double getOilLevel() {
		return oilLevel;
	}
	public void setOilLevel(double oilLevel) {
		this.oilLevel = oilLevel;
	}
	public double getOilVolume() {
		return oilVolume;
	}
	public void setOilVolume(double oilVolume) {
		this.oilVolume = oilVolume;
	}
	public double getOilweight() {
		return oilweight;
	}
	public void setOilweight(double oilweight) {
		this.oilweight = oilweight;
	}
	public double getAddibleOil() {
		return addibleOil;
	}
	public void setAddibleOil(double addibleOil) {
		this.addibleOil = addibleOil;
	}
	public double getWaterLevel() {
		return waterLevel;
	}
	public void setWaterLevel(double waterLevel) {
		this.waterLevel = waterLevel;
	}
	public double getWaterVolume() {
		return waterVolume;
	}
	public void setWaterVolume(double waterVolume) {
		this.waterVolume = waterVolume;
	}
	public double getTemperature() {
		return temperature;
	}
	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}
	public String getConnectState() {
		return connectState;
	}
	public void setConnectState(String connectState) {
		this.connectState = connectState;
	}
	public String getProbeType() {
		return probeType;
	}
	public void setProbeType(String probeType) {
		this.probeType = probeType;
	}
	public int getProbeAddress() {
		return probeAddress;
	}
	public void setProbeAddress(int probeAddress) {
		this.probeAddress = probeAddress;
	}
	public String getAlarmState() {
		return alarmState;
	}
	public void setAlarmState(String alarmState) {
		this.alarmState = alarmState;
	}
	public int getOilRatio() {
		return oilRatio;
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
	public int getOilColor() {
		return oilColor;
	}  
	public void setOilColor(int oilColor) {
		this.oilColor = oilColor;
	}
	
	
}
