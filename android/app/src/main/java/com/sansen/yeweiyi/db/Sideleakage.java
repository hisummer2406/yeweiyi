package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
public class Sideleakage {
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/**
	 * id
	 */
	@Id
	private int id;

	/**
	 * 时间
	 */
	@Column(column = "time")
	private String time;
	/**
	 * 侧漏信息
	 */
	@Column(column = "gsoninfo")
	private String gsonInfo;

	public Sideleakage() {
		super();
		// TODO Auto-generated constructor stub
	}

	public Sideleakage(String time, String info) {
		super();

		this.time = time;
		this.gsonInfo = info;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public String getInfo() {
		return gsonInfo;
	}

	public void setInfo(String info) {
		this.gsonInfo = info;
	}

	@Override
	public String toString() {
		return "Sideleakage [id=" + id + ", time=" + time + ", gsonInfo="
				+ gsonInfo + "]";
	}
}
