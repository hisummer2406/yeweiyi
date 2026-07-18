package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC 油罐报警采集
 */
public class HightOliterIn {
	public int uCount;
	public CapacityData pCapacityData;
	public double fTotalHeight;
	public double fWaterHeight;
	public double fOilTemp;
	public double fOilTemp1;
	public double fOilTemp2;
	public double fOilTemp3;
	public double fOilTemp4;
	public double fOilTemp5;

	public class CapacityData {
		public int uOilCanNo;
		public String strVersion;
		public int uCapacitySize;
		public Capacitytable pCapacityTableData[];
	}

	public class Capacitytable {
		public int uHigh;
		public double fLiter;
	}
}
