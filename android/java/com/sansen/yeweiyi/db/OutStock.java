package com.sansen.yeweiyi.db;


import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

//入库记录
public class OutStock {
	// 记录id
	@Id
	private int id;
	/**
	 * 油罐id
	 */
	@Column(column = "oilId")
	private int oilId;
	/**
	 * 时间
	 */
	@Column(column = "time")
	private String time;
	/**
	 * 开始高度
	 */
	@Column(column = "beginheigth")
	private double beginHeigth;

	/**
	 * 结束高度
	 */
	@Column(column = "endheigth")
	private double endHeigth;
	/**
	 * 开始体积
	 */
	@Column(column = "begivolum")
	private double beginVolum;

	/**
	 * 结束体积
	 */
	@Column(column = "endvolum")
	private double endVolum;


	/**
	 * 进油 开始重量
	 */
	@Column(column = "beginweight")
	private double beginWeight;
	/**
	 * 进油 开始重量
	 */
	@Column(column = "endweight")
	private double endWeight;
	/**
	 * 是否发送到网络
	 */
	@Column(column = "isnet")
	private boolean isNet;

	private DetailInoutRecord record;

	private String msg_id;


	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getOilId() {
		return oilId;
	}

	public void setOilId(int oilId) {
		this.oilId = oilId;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public double getBeginHeigth() {
		return beginHeigth;
	}

	public void setBeginHeigth(double beginHeigth) {
		this.beginHeigth = beginHeigth;
	}

	public double getEndHeigth() {
		return endHeigth;
	}

	public void setEndHeigth(double endHeigth) {
		this.endHeigth = endHeigth;
	}

	public double getBeginVolum() {
		return beginVolum;
	}

	public void setBeginVolum(double beginVolum) {
		this.beginVolum = beginVolum;
	}

	public double getEndVolum() {
		return endVolum;
	}

	public void setEndVolum(double endVolum) {
		this.endVolum = endVolum;
	}

	public double getBeginWeight() {
		return beginWeight;
	}

	public void setBeginWeight(double beginWeight) {
		this.beginWeight = beginWeight;
	}

	public double getEndWeight() {
		return endWeight;
	}

	public void setEndWeight(double endWeight) {
		this.endWeight = endWeight;
	}

	public boolean isNet() {
		return isNet;
	}

	public void setNet(boolean net) {
		isNet = net;
	}

	public String getMsg_id() {
		return msg_id;
	}

	public void setMsg_id(String msg_id) {
		this.msg_id = msg_id;
	}

	@Override
	public String toString() {
		return "OilInOut [id=" + id + ", oilId=" + oilId + ", time=" + time
				+ ", beginHeigth=" + beginHeigth + ", endHeigth=" + endHeigth
				+ ", beginVolum=" + beginVolum + ", beginWeight=" + beginWeight + ", endWeight=" + endWeight+ ", endVolum="  + endVolum + "]";
	}

	public String[] getParams() {
		String[] params = { "isNet","record","msg_id","id","oilId", "time", "beginHeigth", "endHeigth" ,"beginVolum","beginWeight","endWeight","endVolum"};
		return params;
	}

	public DetailInoutRecord getRecord() {
		return record;
	}

	public void setRecord(DetailInoutRecord record) {
		this.record = record;
	}
}
