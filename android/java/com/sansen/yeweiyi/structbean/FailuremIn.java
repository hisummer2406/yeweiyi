package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC 设备故障采集
 */
public class FailuremIn {
	public int uCount;
	public FailureData[] pFailureData;
	
	public class FailureData {
		public int uOilCanNo;
		public String strDateTime;
		public int uReqCount;
	}
}
