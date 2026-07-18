package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC 
 * 预报警设置
 */
public class AlarmSettingIn {
	public int uReqCount;
	public AlarmSetterData[] pAlarmSetterData;

	public class AlarmSetterData {
		public int uOilCanNo;
		public double fLowWarning;
		public double fLowAlarm;
		public double fHighWarning;
		public double fHighAlarm;
		public double fWaterWarning;
		public double fWaterAlarm;
		public double fThiefAlarm;         // Not used
		public double fLeakAlarm;
		public double fPercolatingAlarm;   // Not used
		public double fHighTempAlarm;      // Not used
		public double fLowTempAlarm;       // Not used
	}
}
