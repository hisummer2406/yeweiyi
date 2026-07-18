package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC
 *	整点库存采集
 */
public class TimeStockIn {
	public int uCount;
	public TimeStockData[] pTimeStockData;

	public class TimeStockData {
		public int uOilCanNo;
		public String strDateTime;
		public int uReqCount;
	}		
}
