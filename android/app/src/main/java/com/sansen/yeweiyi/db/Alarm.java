package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;


public class Alarm {

	@Id
	private int id;

	/**
	 * 时间
	 */
	@Column(column = "time")
	private String time;

	/**
	 * 信息
	 */
	@Column(column = "info")
	private String info;

	/**
	 * 罐号
	 */
	@Column(column = "guanhao")
	private String guanHao;

	/**
	 * 罐号
	 */
	@Column(column = "jiezhi")
	private String jieZhi;

	public String getGuanHao() {
		return guanHao;
	}

	public void setGuanHao(String guanHao) {
		this.guanHao = guanHao;
	}

	public String getJieZhi() {
		return jieZhi;
	}

	public void setJieZhi(String jieZhi) {
		this.jieZhi = jieZhi;
	}

	public Alarm() {
		super();
	}


	public Alarm(String time, String info, String guanHao, String jieZhi) {
		this.time = time;
		this.info = info;
		this.guanHao = guanHao;
		this.jieZhi = jieZhi;
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

	public String getInfo() {
		return info;
	}

	public void setInfo(String info) {
		this.info = info;
	}

	@Override
	public String toString() {
		return "Alarm [id=" + id + ", time=" + time
				+ ", guanHao=" + guanHao
				+ ", jieZhi=" + jieZhi
				+ ", info=" + info + "]";

	}

	// 原给已删除的反射导出提供列名；保留是因为局域网接口 /alarm（AlarmHandler）用 fastjson 序列化本类，
	// fastjson 会把这个 getter 输出成 "params" 字段，删掉会改变对外 JSON
	public String[] getParams() {
		String[] params = { "id", "time", "info","jieZhi","guanHao"};
		return params;
	}

}
