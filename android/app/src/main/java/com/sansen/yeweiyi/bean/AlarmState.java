package com.sansen.yeweiyi.bean;


public class AlarmState {
	//0表示连接正常   1表示通讯中断
	//如果得到是0的话表示通讯一直是正常，
	//1的话，表示出现过报警，并保存过数据

	//优化，需要将Int改为boolean
	
	//需要维护一个List<AlaramState> 
	private int oilCanPos;
	private int connectError;
	private int heightOilError;
	private int lowOilError;
	private int hheightOilError;
	private int llowOilError;
	private int heightWaterError;
	private int heightTempError;
	private int lowTempError;
	
	public int getOilCanPos() {
		return oilCanPos;
	}
	public void setOilCanPos(int oilCanPos) {
		this.oilCanPos = oilCanPos;
	}
	public int getConnectError() {
		return connectError;
	}
	public void setConnectError(int connectError) {
		this.connectError = connectError;
	}
	public int getHeightOilError() {
		return heightOilError;
	}
	public void setHeightOilError(int heightOilError) {
		this.heightOilError = heightOilError;
	}
	public int getLowOilError() {
		return lowOilError;
	}
	public void setLowOilError(int lowOilError) {
		this.lowOilError = lowOilError;
	}
	public int getHeightWaterError() {
		return heightWaterError;
	}
	public void setHeightWaterError(int heightWaterError) {
		this.heightWaterError = heightWaterError;
	}
	public int getHeightTempError() {
		return heightTempError;
	}
	public void setHeightTempError(int heightTempError) {
		this.heightTempError = heightTempError;
	}
	public int getLowTempError() {
		return lowTempError;
	}
	public void setLowTempError(int lowTempError) {
		this.lowTempError = lowTempError;
	}
	public int getHheightOilError() {
		return hheightOilError;
	}
	public void setHheightOilError(int hheightOilError) {
		this.hheightOilError = hheightOilError;
	}
	public int getLlowOilError() {
		return llowOilError;
	}
	public void setLlowOilError(int llowOilError) {
		this.llowOilError = llowOilError;
	}
	
}
