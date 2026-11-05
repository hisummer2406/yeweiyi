package com.sansen.yeweiyi.bean;

import com.lidroid.xutils.db.annotation.Column;

import java.io.Serializable;

/**
 * 交班记录里一个罐的数据，序列化为 {@code CommitWork.info} 的 JSON 数组元素。
 * 高度/容积/出库量用包装类型：null = 缺失（进行中的班没有结束数据、中途新增的罐没有开始数据），
 * 不再用 0.0 冒充（issue #49）。升级前存的 JSON 里这些字段是 0.0，照常解析为 0.0。
 */
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
	private Double beginHeight;
	/**
	 * 交班时高度
	 */
	private Double endHeight;

	/**
	 * 接班时容量
	 */
	private Double beginVolume;

	/**
	 * 交班时容量
	 */
	private Double endVolume;
	/**
	 * 售出油的体积
	 */
	private Double shellVolume;
	/**
	 * 售出油的重量
	 */
	private Double shellWeight;
	/**
	 * 開始时间
	 */
	@Column(column = "jieZhi")
	private String jieZhi;

	/**
	 * 开始交班时该罐的油品表下标（{@code OilCanSet.oilType}，与界面语言无关；0 未设置，1–17 内置，18–25 自定义）。
	 * 与 jieZhi 同时记录；旧记录为 null。
	 */
	private Integer oilTypeIndex;

	public Integer getOilTypeIndex() {
		return oilTypeIndex;
	}

	public void setOilTypeIndex(Integer oilTypeIndex) {
		this.oilTypeIndex = oilTypeIndex;
	}

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

	public Double getBeginVolume() {
		return beginVolume;
	}

	public void setBeginVolume(Double beginVolume) {
		this.beginVolume = beginVolume;
	}

	public Double getEndVolume() {
		return endVolume;
	}

	public void setEndVolume(Double endVolume) {
		this.endVolume = endVolume;
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public Double getBeginHeight() {
		return beginHeight;
	}

	public void setBeginHeight(Double beginHeight) {
		this.beginHeight = beginHeight;
	}

	public Double getEndHeight() {
		return endHeight;
	}

	public void setEndHeight(Double endHeight) {
		this.endHeight = endHeight;
	}

	public Double getShellVolume() {
		return shellVolume;
	}

	public void setShellVolume(Double shellVolume) {
		this.shellVolume = shellVolume;
	}

	public Double getShellWeight() {
		return shellWeight;
	}

	public void setShellWeight(Double shellWeight) {
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
