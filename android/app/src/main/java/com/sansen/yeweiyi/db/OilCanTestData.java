package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.Table;


@Table(name = "testData")
public class OilCanTestData {
	
	@Id
	private int oilCanId;
	
	@Column(column="oilCanTableId")
	private int oilCanTableId;
	
	@Column(column="oilCansetId")
	private int oilCansetId;
	
	@Column(column="oilLevel")
	private double oilLevel;
	
	@Column(column="waterLevel")
	private double waterLevel;
	
	public int getOilCanId() {
		return oilCanId;
	}
	public void setOilCanId(int oilCanId) {
		this.oilCanId = oilCanId;
	}
	public double getOilLevel() {
		return oilLevel;
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
}