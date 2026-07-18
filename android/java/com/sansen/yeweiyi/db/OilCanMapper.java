package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

public class OilCanMapper {
	
	@Id
	private int probeId;
	@Column(column="oilCansetId")
	private int oilCansetId=1;
	@Column(column="oilCanTableId")
	private int oilCanTableId=1;

	@Column(column = "isShow")
	private boolean isshow=false;

	public boolean isshow() {
		return isshow;
	}

	public void setIsshow(boolean isshow) {
		this.isshow = isshow;
	}

	public int getProbeId() {
		return probeId;
	}
	public void setProbeId(int probeId) {
		this.probeId = probeId;
	}
	public int getOilCansetId() {
		return oilCansetId;
	}
	public void setOilCansetId(int oilCansetId) {
		this.oilCansetId = oilCansetId;
	}
	public int getOilCanTableId() {
		return oilCanTableId;
	}
	public void setOilCanTableId(int oilCanTableId) {
		this.oilCanTableId = oilCanTableId;
	}
	@Override
	public String toString() {
		return "OilCanMapper [probeId=" + probeId + ", oilCansetId="
				+ oilCansetId + ", oilCanTableId=" + oilCanTableId + "]";
	}
}
