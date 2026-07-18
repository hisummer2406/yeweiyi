package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
public class Privilege {
	
	public Privilege() {
		super();
	}
	@Id
	private int id;

	/**
	 *权限ID
	 */
	@Column(column = "privilegeid")
	private String privilegeID;

	/**
	 * 用户id
	 */
	private String PrivilegeAccessValue;
	/**
	 * 0  表示	允许
	 * 1  表示 不允许
	 */
	private int PrivilegeOperation;
	
	public String getPrivilegeID() {
		return privilegeID;
	}
	public void setPrivilegeID(String privilegeID) {
		this.privilegeID = privilegeID;
	}
	
	public String getPrivilegeAccessValue() {
		return PrivilegeAccessValue;
	}
	public void setPrivilegeAccessValue(String privilegeAccessValue) {
		PrivilegeAccessValue = privilegeAccessValue;
	}
	public int getPrivilegeOperation() {
		return PrivilegeOperation;
	}
	public void setPrivilegeOperation(int privilegeOperation) {
		PrivilegeOperation = privilegeOperation;
	}
	@Override
	public String toString() {
		return "Privilege [id=" + id + ", privilegeID=" + privilegeID
				+ ", PrivilegeAccessValue=" + PrivilegeAccessValue
				+ ", PrivilegeOperation=" + PrivilegeOperation + "]";
	}

}
