package com.sansen.yeweiyi.bean;

import java.io.Serializable;

public class OilWork implements Serializable {
	/**
	 * 油罐ID
	 */
	private int oilId;

	/**
	 * 接班时高度
	 */
	private double beginHeight;
	/**
	 * 交班时高度
	 */
	private double endHeight;

	/**
	 * 接班时容量
	 */
	private double beginVolume;

	/**
	 * 交班时容量
	 */
	private double endVolume;
	/**
	 * 售出油的体积
	 */
	private double shellVolume;
	/**
	 * 售出油的重量
	 */
	private double shellWeight;

	public OilWork() {
		super();
		// TODO Auto-generated constructor stub
	}

	public OilWork(int oilId, double beginHeight, double endHeight, double beginVolume, double endVolume, double shellVolume, double shellWeight) {
		this.oilId = oilId;
		this.beginHeight = beginHeight;
		this.endHeight = endHeight;
		this.beginVolume = beginVolume;
		this.endVolume = endVolume;
		this.shellVolume = shellVolume;
		this.shellWeight = shellWeight;
	}

	public double getBeginVolume() {
		return beginVolume;
	}

	public void setBeginVolume(double beginVolume) {
		this.beginVolume = beginVolume;
	}

	public double getEndVolume() {
		return endVolume;
	}

	public void setEndVolume(double endVolume) {
		this.endVolume = endVolume;
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public double getBeginHeight() {
		return beginHeight;
	}

	public void setBeginHeight(double beginHeight) {
		this.beginHeight = beginHeight;
	}

	public double getEndHeight() {
		return endHeight;
	}

	public void setEndHeight(double endHeight) {
		this.endHeight = endHeight;
	}

	public double getShellVolume() {
		return shellVolume;
	}

	public void setShellVolume(double shellVolume) {
		this.shellVolume = shellVolume;
	}

	public double getShellWeight() {
		return shellWeight;
	}

	public void setShellWeight(double shellWeight) {
		this.shellWeight = shellWeight;
	}

	@Override
	public String toString() {
		return "OilWork [oilId=" + oilId + ", beginHeight=" + beginHeight
				+ ", beginVolume=" + beginVolume+ ", endVolume=" + endVolume
				+ ", endHeight=" + endHeight + ", shellVolume=" + shellVolume
				+ ", shellWeight=" + shellWeight + "]";
	}

	

}
