package com.sansen.yeweiyi.structbean;


public class ProbeCanIn {
	public int uCount;
	public ProbeCanData[] pProbeCanData;

	public class ProbeCanData {
		public String strDeviceModel; // Not used
		public String strProbeNo;
		public int uProbePort; // Not used
		public int uOilCanNo;
		public String strOilType;
		public String strOilNo;
		public String strOilName;
	}
}
