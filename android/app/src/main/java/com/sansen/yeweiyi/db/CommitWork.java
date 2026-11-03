package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.Table;


@Table(name = "commitwork")
public class CommitWork {
	public CommitWork(String endTime, int workId, String time, String info) {

		this.endTime = endTime;
		this.workId = workId;
		this.time = time;
		this.info = info;
	}

	/**
	 * ID 流水号
	 */
	@Id
	private int id;
	/**
	 * 班号
	 */
	@Column(column = "workid")
	private int workId;

	public int getWorkId() {
		return workId;
	}

	public void setWorkId(int workId) {
		this.workId = workId;
	}

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
	 * 开始交班时刻的 epoch 毫秒（截到整秒，与 time 同一瞬间）。数据库版本 8 起才有，旧记录为 null。
	 * 上报 shiftId 由它生成，避免开始到结束之间改时区导致同一班算出两个标识（issue #49）。
	 */
	@Column(column = "begintimems")
	private Long beginTimeMs;

	public Long getBeginTimeMs() {
		return beginTimeMs;
	}

	public void setBeginTimeMs(Long beginTimeMs) {
		this.beginTimeMs = beginTimeMs;
	}

	/**
	 * 油罐信息Gson
	 */
	@Column(column = "oilinfogson")
	private String info;

	public CommitWork() {
		super();
	}

	@Override
	public String toString() {
		return "CommitWork [id=" + id + ", workId=" + workId + ", time=" + time+ ", endTime=" + endTime
				+  ", info=" + info + "]";
	}

	public String getEndTime() {
		return endTime;
	}

	public void setEndTime(String endTime) {
		this.endTime = endTime;
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
}
