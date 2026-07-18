package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.Table;

@Table(name = "vctable")
public class VCTable {
	@Id
	private int pk_oilCanId;

	@Column(column = "temparray")
	private String temparray = "";

	@Column(column = "densityarray")
	private String densityarray = "";

	@Column(column = "gmt_create")
	private String gmt_create;

	@Column(column = "gmt_modified")
	private String gmt_modified;

	public int getPk_oilCanId() {
		return pk_oilCanId;
	}

	public void setPk_oilCanId(int pk_oilCanId) {
		this.pk_oilCanId = pk_oilCanId;
	}

	public String getTemparray() {
		return temparray;
	}

	public void setTemparray(String temparray) {
		this.temparray = temparray;
	}

	public String getDensityarray() {
		return densityarray;
	}

	public void setDensityarray(String densityarray) {
		this.densityarray = densityarray;
	}

	public int[] getTemp() {
		return temp;
	}

	public void setTemp(int[] height) {
		this.temp = temp;
	}

	private int temp[] = new int[30];
	private double density[] = new double[30];

	public VCTable() {
		super();
		// TODO Auto-generated constructor stub
	}


	public void ArrayToString() {
		if (temp != null && density != null) {
			StringBuffer tempBuffer = new StringBuffer();
			StringBuffer densityBuffer = new StringBuffer();
			for (int i = 0; i < temp.length; i++) {
				tempBuffer.append(temp[i]);
				tempBuffer.append(" ");
				densityBuffer.append(density[i]);
				densityBuffer.append(" ");
			}
			temparray = tempBuffer.toString();
			densityarray = densityBuffer.toString();
		}
	}

	public void StringToArray() {
		if (temparray != null && densityarray != null) {
			String hightStr[] = temparray.split(" ");
			String volumStr[] = densityarray.split(" ");
			int[] tempHeight = new int[hightStr.length];
			double[] tempVolum = new double[volumStr.length];
			for (int i = 0; i < tempHeight.length; i++) {
				if (hightStr[i] != "") {
					tempHeight[i] = Integer.parseInt(hightStr[i]);
					tempVolum[i] = Double.parseDouble(volumStr[i]);
				}
			}

//			if (tempHeight.length == 30) {
				temp = tempHeight;
				density = tempVolum;
//			}

		}

	}

	public String getGmt_create() {
		return gmt_create;
	}

	public void setGmt_create(String gmt_create) {
		this.gmt_create = gmt_create;
	}

	public String getGmt_modified() {
		return gmt_modified;
	}

	public void setGmt_modified(String gmt_modified) {
		this.gmt_modified = gmt_modified;
	}

	@Override
	public String toString() {

		StringBuffer buffer = new StringBuffer();
		if (temp != null) {
			for (int i = 0; i < temp.length; i++) {
				buffer.append(" " + (i + 1) + "  ");
				buffer.append("温度：" + temp[i] + "  ");
				buffer.append("密度：" + density[i]);
				buffer.append("\n");
			}
		}

		return buffer.toString();
	}

	public VCTable(int oilCanId, int[] temp, double[] density) {
		super();
		this.pk_oilCanId = oilCanId;
		this.temp = temp;
		this.density = density;
	}

	public String[] getParams() {
		String[] params = { "oilCanId", "temp", "density" };
		return params;
	}
}