package com.sansen.yeweiyi.bean;

public class GasExplor {
	
	public GasExplor(int oilCanId, long time, int value) {
		super();
		this.oilCanId = oilCanId;
		this.time = time;
		this.value = value;
	}
	private int oilCanId;//当前探头的ID
	
	private long time;	//当前更新的时间
	
	private int value;
	
	public int getOilCanId() {
		return oilCanId;
	}
	public void setOilCanId(int oilCanId) {
		this.oilCanId = oilCanId;
	}
	public long getTime() {
		return time;
	}
	public void setTime(int time) {
		this.time = time;
	}
	public int getValue() {
		return value;
	}
	public void setValue(int value) {
		this.value = value;
	}
}
