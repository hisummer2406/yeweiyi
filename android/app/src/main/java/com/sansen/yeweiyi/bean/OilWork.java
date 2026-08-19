package com.sansen.yeweiyi.bean;

import com.lidroid.xutils.db.annotation.Column;

import java.io.Serializable;

public class OilWork implements Serializable {
	/**
	 * 開始时间
	 */
	@Column(column = "time")
	private String time;
	/**
	 * 開始时间
	 */
	@Column(column = "endtime")
	private String endTime;
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
	/**
	 * 開始时间
	 */
	@Column(column = "jieZhi")
	private String jieZhi;

	public OilWork() {
		super();
		// TODO Auto-generated constructor stub
	}

	public OilWork(int oilId,String jieZhi, double beginHeight, double endHeight,String time,String endTime, double beginVolume, double endVolume, double shellVolume, double shellWeight) {
		this.oilId = oilId;
		this.jieZhi=jieZhi;
		this.beginHeight = beginHeight;
		this.endHeight = endHeight;
		this.beginVolume = beginVolume;
		this.endVolume = endVolume;
		this.shellVolume = shellVolume;
		this.shellWeight = shellWeight;
		this.time=time;
		this.endTime=time;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public String getEndTime() {
		return endTime;
	}

	public void setEndTime(String endTime) {
		this.endTime = endTime;
	}

	public String getJieZhi() {
		return jieZhi;
	}

	public void setJieZhi(String jieZhi) {
		this.jieZhi = jieZhi;
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

	public String[] getParams() {
		String[] params = { "jieZhi","oilId", "time","endTime", "beginHeight", "endHeight" ,"beginVolume","endVolume","shellVolume","shellWeight"};
		return params;
	}

	

}
