package com.sansen.yeweiyi.structbean;

/**
 * Created by PC on 2018-07-26.
 * 探棒校正参数设置
 */

public class CorrectionIn {
	public int uCount;
	public CorrectionData[] pCorrectionData;

	public CorrectionIn(int uCount, CorrectionData[] pCorrectionData) {
		this.uCount = uCount;
		this.pCorrectionData = pCorrectionData;
	}

	public class CorrectionData {
		public CorrectionData(String strDeviceModel, String strProbeNo, String strOilType, double fOilCorrection, double fWaterCorrection, double fProbeOffset, double fTiltOffset, double fTemp1, double fProbeTemp1, double fTemp2, double fProbeTemp2, double fTemp3, double fProbeTemp3, double fTemp4, double fProbeTemp4, double fTemp5, double fProbeTemp5, double fInitDensity, double fInitHighDiff, double fCorrectionFactor) {
			this.strDeviceModel = strDeviceModel;
			this.strProbeNo = strProbeNo;
			this.strOilType = strOilType;
			this.fOilCorrection = fOilCorrection;
			this.fWaterCorrection = fWaterCorrection;
			this.fProbeOffset = fProbeOffset;
			this.fTiltOffset = fTiltOffset;
			this.fTemp1 = fTemp1;
			this.fProbeTemp1 = fProbeTemp1;
			this.fTemp2 = fTemp2;
			this.fProbeTemp2 = fProbeTemp2;
			this.fTemp3 = fTemp3;
			this.fProbeTemp3 = fProbeTemp3;
			this.fTemp4 = fTemp4;
			this.fProbeTemp4 = fProbeTemp4;
			this.fTemp5 = fTemp5;
			this.fProbeTemp5 = fProbeTemp5;
			this.fInitDensity = fInitDensity;
			this.fInitHighDiff = fInitHighDiff;
			this.fCorrectionFactor = fCorrectionFactor;
		}

		public String strDeviceModel; // Not used
		public String strProbeNo;
		public String strOilType; // Not used
		public double fOilCorrection;
		public double fWaterCorrection;
		public double fProbeOffset;// Not used
		public double fTiltOffset;// Not used
		public double fTemp1; // Not used
		public double fProbeTemp1; // Not used
		public double fTemp2; // Not used
		public double fProbeTemp2; // Not used
		public double fTemp3; // Not used
		public double fProbeTemp3; // Not used
		public double fTemp4; // Not used
		public double fProbeTemp4; // Not used
		public double fTemp5; // Not used
		public double fProbeTemp5; // Not used
		public double fInitDensity; // Not used
		public double fInitHighDiff; // Not used
		public double fCorrectionFactor; // Not used

		public String getStrDeviceModel() {
			return strDeviceModel;
		}

		public void setStrDeviceModel(String strDeviceModel) {
			this.strDeviceModel = strDeviceModel;
		}

		public String getStrProbeNo() {
			return strProbeNo;
		}

		public void setStrProbeNo(String strProbeNo) {
			this.strProbeNo = strProbeNo;
		}

		public String getStrOilType() {
			return strOilType;
		}

		public void setStrOilType(String strOilType) {
			this.strOilType = strOilType;
		}

		public double getfOilCorrection() {
			return fOilCorrection;
		}

		public void setfOilCorrection(double fOilCorrection) {
			this.fOilCorrection = fOilCorrection;
		}

		public double getfWaterCorrection() {
			return fWaterCorrection;
		}

		public void setfWaterCorrection(double fWaterCorrection) {
			this.fWaterCorrection = fWaterCorrection;
		}

		public double getfProbeOffset() {
			return fProbeOffset;
		}

		public void setfProbeOffset(double fProbeOffset) {
			this.fProbeOffset = fProbeOffset;
		}

		public double getfTiltOffset() {
			return fTiltOffset;
		}

		public void setfTiltOffset(double fTiltOffset) {
			this.fTiltOffset = fTiltOffset;
		}

		public double getfTemp1() {
			return fTemp1;
		}

		public void setfTemp1(double fTemp1) {
			this.fTemp1 = fTemp1;
		}

		public double getfProbeTemp1() {
			return fProbeTemp1;
		}

		public void setfProbeTemp1(double fProbeTemp1) {
			this.fProbeTemp1 = fProbeTemp1;
		}

		public double getfTemp2() {
			return fTemp2;
		}

		public void setfTemp2(double fTemp2) {
			this.fTemp2 = fTemp2;
		}

		public double getfProbeTemp2() {
			return fProbeTemp2;
		}

		public void setfProbeTemp2(double fProbeTemp2) {
			this.fProbeTemp2 = fProbeTemp2;
		}

		public double getfTemp3() {
			return fTemp3;
		}

		public void setfTemp3(double fTemp3) {
			this.fTemp3 = fTemp3;
		}

		public double getfProbeTemp3() {
			return fProbeTemp3;
		}

		public void setfProbeTemp3(double fProbeTemp3) {
			this.fProbeTemp3 = fProbeTemp3;
		}

		public double getfTemp4() {
			return fTemp4;
		}

		public void setfTemp4(double fTemp4) {
			this.fTemp4 = fTemp4;
		}

		public double getfProbeTemp4() {
			return fProbeTemp4;
		}

		public void setfProbeTemp4(double fProbeTemp4) {
			this.fProbeTemp4 = fProbeTemp4;
		}

		public double getfTemp5() {
			return fTemp5;
		}

		public void setfTemp5(double fTemp5) {
			this.fTemp5 = fTemp5;
		}

		public double getfProbeTemp5() {
			return fProbeTemp5;
		}

		public void setfProbeTemp5(double fProbeTemp5) {
			this.fProbeTemp5 = fProbeTemp5;
		}

		public double getfInitDensity() {
			return fInitDensity;
		}

		public void setfInitDensity(double fInitDensity) {
			this.fInitDensity = fInitDensity;
		}

		public double getfInitHighDiff() {
			return fInitHighDiff;
		}

		public void setfInitHighDiff(double fInitHighDiff) {
			this.fInitHighDiff = fInitHighDiff;
		}

		public double getfCorrectionFactor() {
			return fCorrectionFactor;
		}

		public void setfCorrectionFactor(double fCorrectionFactor) {
			this.fCorrectionFactor = fCorrectionFactor;
		}
	}

	public int getuCount() {
		return uCount;
	}

	public void setuCount(int uCount) {
		this.uCount = uCount;
	}

	public CorrectionData[] getpCorrectionData() {
		return pCorrectionData;
	}

	public void setpCorrectionData(CorrectionData[] pCorrectionData) {
		this.pCorrectionData = pCorrectionData;
	}
}
