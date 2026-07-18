package com.sansen.yeweiyi.structbean;

/**
 * 
 * @author PC
 *	实时数据采集
 */
public class StockOut {
	public int uRetCount;
	public StockData[] pStockData;

	public class StockData {

		public StockData(){

		}
		//油罐编号
		public int uOilCanNo;
		//日期
		public String strDate;
		//时间
		public String strTime;
		//净油体积
		public double fOilCubage;
		//标准体积
		public double fOilStandCubage;
		//空体积
		public double fEmptyCubage;
		//油水总高
		public double fTotalHeight;
		//水高
		public double fWaterHeight;
		//平均温度
		public double fOilTemp;
		//public double fOilTemp1;
		//public double fOilTemp2;
		//public double fOilTemp3;
		//public double fOilTemp4;
		//public double fOilTemp5;
		//水体积
		public double fWaterBulk;
		//public double fApparentDensity;
		//public double fStandDensity;
	}		
}
