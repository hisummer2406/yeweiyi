package com.sansen.yeweiyi.bean;

/**
 * 当前液位仪的主要信息
 * @author PC
 *
 */

public class LevelmeterInfo {
	private int id;
	private String pk_cpuId;
	private int pk_d2dId;
	private double longitude;
	private double latitude;
	private boolean isPrice;
	private String address;
	private String dealer;
	private String salesman;
	private String gmt_create;
	private String gmt_modified;
	private String levelmeter_agent;
	private String ip_address;
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public String getPk_cpuId() {
		return pk_cpuId;
	}
	public void setPk_cpuId(String pk_cpuId) {
		this.pk_cpuId = pk_cpuId;
	}
	public int getPk_d2dId() {
		return pk_d2dId;
	}
	public void setPk_d2dId(int pk_d2dId) {
		this.pk_d2dId = pk_d2dId;
	}
	public double getLongitude() {
		return longitude;
	}
	public void setLongitude(double longitude) {
		this.longitude = longitude;
	}
	public double getLatitude() {
		return latitude;
	}
	public void setLatitude(double latitude) {
		this.latitude = latitude;
	}
	public boolean isPrice() {
		return isPrice;
	}
	public void setPrice(boolean isPrice) {
		this.isPrice = isPrice;
	}
	public String getAddress() {
		return address;
	}
	public void setAddress(String address) {
		this.address = address;
	}
	public String getDealer() {
		return dealer;
	}
	public void setDealer(String dealer) {
		this.dealer = dealer;
	}
	public String getSalesman() {
		return salesman;
	}
	public void setSalesman(String salesman) {
		this.salesman = salesman;
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
	public String getLevelmeter_agent() {
		return levelmeter_agent;
	}
	public void setLevelmeter_agent(String levelmeter_agent) {
		this.levelmeter_agent = levelmeter_agent;
	}
	public String getIp_address() {
		return ip_address;
	}
	public void setIp_address(String ip_address) {
		this.ip_address = ip_address;
	}
	
}
