package com.sansen.yeweiyi.bean;

import com.lidroid.xutils.db.annotation.Table;

@Table(name = "oilwork")
public class SideleaInfo {
	/**
	 * 油罐ID
	 */
	private int oilId;

	/**
	 * 测漏信息
	 */
	private String sideleaInfo;

	public SideleaInfo() {
		super();
		// TODO Auto-generated constructor stub
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public String getSideleaInfo() {
		return sideleaInfo;
	}

	public void setSideleaInfo(String sideleaInfo) {
		this.sideleaInfo = sideleaInfo;
	}

	public SideleaInfo(int oilId, String sideleaInfo) {
		super();
		this.oilId = oilId;
		this.sideleaInfo = sideleaInfo;
	}

	@Override
	public String toString() {
		return "SideleaInfo [oilId=" + oilId + ", sideleaInfo=" + sideleaInfo
				+ "]";
	}

}
