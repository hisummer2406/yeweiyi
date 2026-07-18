package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC 油罐报警采集
 */
public class AlarmIn {
	public int uCount;
	public AlarmData[] pAlarmData;

	public class AlarmData {
		public int uOilCanNo;
		public String strDateTime;
		public int uReqCount;
	}
}
