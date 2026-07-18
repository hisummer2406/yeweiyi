package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;


public class OilSaveInfo {
	/**
	 * id
	 */
	@Id
	int id;

	/**
	 * 时间
	 */
	@Column(column = "time")
	String time;

	/**
	 * 备注信息
	 */
	@Column(column = "remark")
	String remark;

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	/**
	 * 所有存油信息记录，通过gson对oil对象解析为字符串
	 */
	@Column(column = "oilInfogosn")
	String oilInfoGosn;

	public OilSaveInfo() {
		super();
		// TODO Auto-generated constructor stub
	}

	public OilSaveInfo(String time, String remark, String oilInfoGosn) {
		this.time = time;
		this.remark = remark;
		this.oilInfoGosn = oilInfoGosn;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public String getOilInfoGosn() {
		return oilInfoGosn;
	}

	public void setOilInfoGosn(String oilInfoGosn) {
		this.oilInfoGosn = oilInfoGosn;
	}

	@Override
	public String toString() {
		return "OilSaveInfo [id=" + id + ", time=" + time + ", oilInfoGosn="
				+ oilInfoGosn + "]";
	}

}
