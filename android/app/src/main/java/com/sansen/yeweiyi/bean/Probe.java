package com.sansen.yeweiyi.bean;

public class Probe {
	/**
	 * 探针id
	 */
	String id;
	/**
	 * 同步头
	 */
	String head;
	/**
	 * 命令字
	 */
	String command;
	/**
	 * 油位
	 */
	double oilLevel;
	/**
	 * 水位
	 */
	double waterLevel;

	/**
	 * 温度数据
	 */
	String temperature;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getHead() {
		return head;
	}

	public void setHead(String head) {
		this.head = head;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public Double getOilLevel() {
		return oilLevel;
	}

	public void setOilLevel(Double d) {
		this.oilLevel = d;
	}

	public double getWaterLevel() {
		return waterLevel;
	}

	public void setWaterLevel(Double waterLevel) {
		this.waterLevel = waterLevel;
	}

	public String getTemperature() {
		return temperature;
	}

	public void setTemperature(String temperature) {
		this.temperature = temperature;
	}

	@Override
	public String toString() {
		return "Probe [id=" + id + ", head=" + head + ", command=" + command
				+ ", oilLevel=" + oilLevel + ", waterLevel=" + waterLevel
				+ ", temperature=" + temperature + "]";
	}

}
