package com.sansen.yeweiyi.db;


import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

//入库记录
public class StockSave {


	// 记录id
	@Id
	private int id;
	/**
	 * 油罐id
	 */
	@Column(column = "oilId")
	private int oilId;
	/**
	 * 时间
	 */
	@Column(column = "time")
	private String time;
	/**
	 *液位
	 */
	@Column(column = "height")
	private double height;

	/**
	 * 水高度
	 */
	@Column(column = "waterheigth")
	private double waterheigth;
	/**
	 * 容积
	 */
	@Column(column = "volum")
	private double volum;

	/**
	 * 质量
	 */
	@Column(column = "weight")
	private double weight;
	/**
	 * 温度
	 */
	@Column(column = "temperature")
	private double temperature;

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public double getHeight() {
		return height;
	}

	public void setHeight(double height) {
		this.height = height;
	}

	public double getWaterheigth() {
		return waterheigth;
	}

	public void setWaterheigth(double waterheigth) {
		this.waterheigth = waterheigth;
	}

	public double getVolum() {
		return volum;
	}

	public void setVolum(double volum) {
		this.volum = volum;
	}

	public double getWeight() {
		return weight;
	}

	public void setWeight(double weight) {
		this.weight = weight;
	}

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	@Override
	public String toString() {
		return "OilInOut [id=" + id + ", oilId=" + oilId + ", time=" + time
				+ ", height=" + height + ", waterheigth=" + waterheigth
				+ ", volum=" + volum + ", weight=" + weight + ", temperature=" + temperature + "]";
	}

	public String[] getParams() {
		String[] params = { "id","oilId", "time", "height", "waterheigth" ,"volum","weight","temperature"};
		return params;
	}

}
