package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NotNull;
public class ProbeToOil {

	/**
	 * xutlis必须重写无参构造方法
	 */
	public ProbeToOil() {
		super();
		// TODO Auto-generated constructor stub
	}

	/**
	 * 油罐的id 不可重复
	 */
	@Id
	private int pk_oilCanId; // 主键唯一
	
	private int pk_d2dId;

	/**
	 * 探针的id 可以重复
	 */
	@Column(column = "probecanid")
	@NotNull
	private String probeId = "00";
	
	
	@Column(column = "enable")
	@NotNull
	private int enable = 0;
	/**
	 * 探针的类型 决定了数据发送数据解析的格式
	 */
	@Column(column = "probetype")
	int probeType;

	@Column(column = "gmt_create")
	private String gmt_create;

	@Column(column = "gmt_modified")
	private String gmt_modified;

	public String[] getParams() {
		String[] params = { "pk_d2dId","pk_oilCanId","gmt_create","gmt_modified", "probeId", "probeType", "enable" };
		return params;
	}

	public int getEnable() {
		return enable;
	}

	public void setEnable(int enable) {
		this.enable = enable;
	}


	public int getPk_oilCanId() {
		return pk_oilCanId;
	}

	public void setPk_oilCanId(int pk_oilCanId) {
		this.pk_oilCanId = pk_oilCanId;
	}

	public int getPk_d2dId() {
		return pk_d2dId;
	}

	public void setPk_d2dId(int pk_d2dId) {
		this.pk_d2dId = pk_d2dId;
	}

	public String getProbeId() {
		return probeId;
	}

	public void setProbeId(String probeId) {
		this.probeId = probeId;
	}

	public int getProbeType() {
		return probeType;
	}


	public void setProbeType(int probeType) {
		this.probeType = probeType;
	}

	public String getGmt_create() {
		return gmt_create;
	}

	public void setGmt_create(String gmt_create) {
		this.gmt_create = gmt_create;
	}

	public String getGmt_modified() {
		return gmt_modified;
	}

	public void setGmt_modified(String gmt_modified) {
		this.gmt_modified = gmt_modified;
	}

	@Override
	public String toString() {
		return "ProbeToOil{" +
				", pk_d2dId=" + pk_d2dId +
				", probeId='" + probeId + '\'' +
				", enable=" + enable +
				", probeType=" + probeType +
				", gmt_create='" + gmt_create + '\'' +
				", gmt_modified='" + gmt_modified + '\'' +
				'}';
	}
}
