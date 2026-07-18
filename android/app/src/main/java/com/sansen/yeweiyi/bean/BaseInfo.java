package com.sansen.yeweiyi.bean;

public class BaseInfo {
	private String companyaddress;
	private String telephonenumber;
	private String androidphone;
	private String weChatPaht;
	
	public String getWeChatPaht() {
		return weChatPaht;
	}

	public void setWeChatPaht(String weChatPaht) {
		this.weChatPaht = weChatPaht;
	}

	public String getAndroidphone() {
		return androidphone;
	}

	public void setAndroidphone(String androidphone) {
		this.androidphone = androidphone;
	}

	public String getCompanyaddress() {
		return companyaddress;
	}

	public void setCompanyaddress(String companyaddress) {
		this.companyaddress = companyaddress;
	}

	public String getTelephonenumber() {
		return telephonenumber;
	}

	public void setTelephonenumber(String telephonenumber) {
		this.telephonenumber = telephonenumber;
	}

	@Override
	public String toString() {
		return "BaseInfo [companyaddress=" + companyaddress
				+ ", telephonenumber=" + telephonenumber + ", androidphone="
				+ androidphone + "]";
	}

}
