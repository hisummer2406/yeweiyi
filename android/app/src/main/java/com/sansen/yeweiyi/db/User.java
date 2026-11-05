package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NoAutoIncrement;
import com.lidroid.xutils.db.annotation.NotNull;
public class User {
	@Id
	@NoAutoIncrement
	private String id;
	@Column(column = "psw")
	@NotNull
	private String psw;

	/**
	 * 是否记住密码
	 * @return
	 */
	@Column(column = "remberpsd")
	private int remberPsd;
	
	

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getPsw() {
		return psw;
	}

	public void setPsw(String psw) {
		this.psw = psw;
	}

	public int getRemberPsd() {
		return remberPsd;
	}

	public void setRemberPsd(int remberPsd) {
		this.remberPsd = remberPsd;
	}



	@Override
	public String toString() {
		return "User [id=" + id + ", psw=" + psw + ", remberPsd=" + remberPsd
				+ "]";
	}

	public User() {

	}
	@Override
	public boolean equals(Object o) {
		User otherUser=(User)o;
		if (otherUser.id.equals(this.id)&&otherUser.psw.equals(this.psw)) {
			return true;
		}else {
			return false;
		}
	}
}
