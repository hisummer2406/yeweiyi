package com.sansen.yeweiyi.bean;

public class Oil {
	private int oilId;
	private String oilHeight;
	private String waterHeight;
	private String oilVolum;
	private String oilWeigh;

	public Oil() {
		super();

	}

	public Oil(int oilId, String oilHeight, String waterHeight,
			   String oilVolum, String oilWeigh) {
		super();
		this.oilId = oilId;
		this.oilHeight = oilHeight;
		this.waterHeight = waterHeight;
		this.oilVolum = oilVolum;
		this.oilWeigh = oilWeigh;
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public String getOilHeight() {
		return oilHeight;
	}

	public void setOilHeight(String oilHeight) {
		this.oilHeight = oilHeight;
	}

	public String getWaterHeight() {
		return waterHeight;
	}

	public void setWaterHeight(String waterHeight) {
		this.waterHeight = waterHeight;
	}

	public String getOilVolum() {
		return oilVolum;
	}

	public void setOilVolum(String oilVolum) {
		this.oilVolum = oilVolum;
	}

	public String getOilWeigh() {
		return oilWeigh;
	}

	public void setOilWeigh(String oilWeigh) {
		this.oilWeigh = oilWeigh;
	}

}
