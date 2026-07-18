package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC
 *	进出油采集
 */
public class OilInIn {
	public int uCount;
	public OilInData[] pOilInData;

	public class OilInData {
		public int uOilCanNo;
		public String strDateTime;
		public int uReqCount;
	}		
}
