package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC 
 * 容积表 下载
 */
public class SetCapacityIn {
	public int uCount;
	public OilCanData[] pCapacityData;

	public class OilCanData {
		public int uOilCanNo;
		public String strVersion;
		public int uCapacitySize;
		public Capacitytable pCapacityTableData[];
	}
	
	public class Capacitytable{
		public int uHigh;
		public double fLiter;
	}
	
}
