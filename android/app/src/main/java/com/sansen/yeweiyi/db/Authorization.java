package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NoAutoIncrement;


public class Authorization {
	@Id
	@NoAutoIncrement
	@Column(column="user_id")
	private String userId;

	public int getOilTest() {
		return oilTest;
	}

	public void setOilTest(int oilTest) {
		this.oilTest = oilTest;
	}

	/**
	 * 权限管理
	 */
	@Column(column="oil_test")
	private int oilTest;

	/**
	 * 油罐设置
	 */
	@Column(column="oilcan_set")
	private int oilcanSet;
	/**
	 * 罐表设置
	 */
	@Column(column="oilcan_table")
	private int oilcanTable;
	/**
	 * 探针设置
	 */
	@Column(column="probe_set")
	private int probeSet;
	/**
	 * 罐表生成
	 */
	@Column(column="table_create")
	private int tableCreate;
	
	/**
	 * 油气探测
	 */
	@Column(column="oil_exploration")
	private int oilExploration;
	
	/**
	 * 用户管理
	 */
	@Column(column="user_manager")
	private int userManager;
	
	/**
	 * 数据管理
	 */
	@Column(column="data_manager")
	private int dataManager;
	/**
	 * 系统设置
	 */
	@Column(column="systemset_manager")
	private int systemSet;

	/**
	 * 防溢阀管理
	 */
	@Column(column="fyf_manager")
	private int fyfManager;
	
	/**
	 *  删除进出油记录
	 * @param opinionSubmission
	 */
	@Column(column="delte_inout")
	private int delteInOutOil;
	
	/**
	 *  更新交班记录
	 * @param opinionSubmission
	 */
	@Column(column="update_commit")
	private int updateCommitInfo;
	
	
	public int getDelteInOutOil() {
		return delteInOutOil;
	}


	public void setDelteInOutOil(int delteInOutOil) {
		this.delteInOutOil = delteInOutOil;
	}
	

	public int getSystemSet() {
		return systemSet;
	}


	public void setSystemSet(int systemSet) {
		this.systemSet = systemSet;
	}


	/**
	 * 关于我们
	 */
	@Column(column="about_user")
	private int aboutUser;
	public String getUserId() {
		return userId;
	}
	public void setUserId(String userId) {
		this.userId = userId;
	}

	public int getOilcanSet() {
		return oilcanSet;
	}
	public void setOilcanSet(int oilcanSet) {
		this.oilcanSet = oilcanSet;
	}
	public int getOilcanTable() {
		return oilcanTable;
	}
	
	public int getUpdateCommitInfo() {
		return updateCommitInfo;
	}


	public void setUpdateCommitInfo(int updateCommitInfo) {
		this.updateCommitInfo = updateCommitInfo;
	}


	public void setOilcanTable(int oilcanTable) {
		this.oilcanTable = oilcanTable;
	}
	public int getProbeSet() {
		return probeSet;
	}
	public void setProbeSet(int probeSet) {
		this.probeSet = probeSet;
	}
	public int getTableCreate() {
		return tableCreate;
	}
	public void setTableCreate(int tableCreate) {
		this.tableCreate = tableCreate;
	}
	public int getDataManager() {
		return dataManager;
	}
	public void setDataManager(int dataManager) {
		this.dataManager = dataManager;
	}
	
	public int getAboutUser() {
		return aboutUser;
	}
	public void setAboutUser(int aboutUser) {
		this.aboutUser = aboutUser;
	}


	public int getUserManager() {
		return userManager;
	}


	public void setUserManager(int userManager) {
		this.userManager = userManager;
	}


	public int getOilExploration() {
		return oilExploration;
	}


	public void setOilExploration(int oilExploration) {
		this.oilExploration = oilExploration;
	}

	public int getFyfManager() {
		return fyfManager;
	}

	public void setFyfManager(int fyfManager) {
		this.fyfManager = fyfManager;
	}
}
