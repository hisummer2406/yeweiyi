package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.Table;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.SystemUtils;

@Table(name = "oiltablearray")
public class OilCanTable {
	@Id
	private int pk_oilCanId;
	
	private int pk_d2dId;

	@Column(column = "heightstrarray")
	private String heightstrarray = "";

	@Column(column = "volumstrarray")
	private String volumstrarray = "";

	@Column(column = "gmt_create")
	private String gmt_create;

	@Column(column = "gmt_modified")
	private String gmt_modified;

	private int height[] = new int[30];
	private double volum[] = new double[30];

	public int getPk_oilCanId() {
		return pk_oilCanId;
	}

	public void setPk_oilCanId(int pk_oilCanId) {
		this.pk_oilCanId = pk_oilCanId;
	}

	public int getPk_d2dId() {
		return pk_d2dId;
	}

	public void setPk_d2dId(int pk_d2dId) {
		this.pk_d2dId = pk_d2dId;
	}

	public String getHeightstrarray() {
		return heightstrarray;
	}

	public void setHeightstrarray(String heightstrarray) {
		this.heightstrarray = heightstrarray;
	}

	public String getVolumstrarray() {
		return volumstrarray;
	}

	public void setVolumstrarray(String volumstrarray) {
		this.volumstrarray = volumstrarray;
	}

	public int[] getHeight() {
		return height;
	}

	public void setHeight(int[] height) {
		this.height = height;
	}

	public double[] getVolum() {
		return volum;
	}

	public void setVolum(double[] volum) {
		this.volum = volum;
	}



	public OilCanTable() {
		super();
		// TODO Auto-generated constructor stub
	}


	public void ArrayToString() {
		if (height != null && volum != null) {
			StringBuffer heightBuffer = new StringBuffer();
			StringBuffer volumBuffer = new StringBuffer();
			for (int i = 0; i < height.length; i++) {
				heightBuffer.append(height[i]);
				heightBuffer.append(" ");
				volumBuffer.append(volum[i]);
				volumBuffer.append(" ");
			}
			heightstrarray = heightBuffer.toString();
			volumstrarray = volumBuffer.toString();
		}
	}

	public void StringToArray() {
		if (heightstrarray != null && volumstrarray != null) {
			String hightStr[] = heightstrarray.split(" ");
			String volumStr[] = volumstrarray.split(" ");
			int[] tempHeight = new int[hightStr.length];
			double[] tempVolum = new double[volumStr.length];
			for (int i = 0; i < tempHeight.length; i++) {
				if (hightStr[i] != "") {
					tempHeight[i] = Integer.parseInt(hightStr[i]);
					tempVolum[i] = Double.parseDouble(volumStr[i]);
				}
			}

//			if (tempHeight.length == 30) {
				height = tempHeight;
				volum = tempVolum;
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

	// [不可达-2026-09-21] 全仓库没有任何地方对 OilCanTable 实例调用 toString()（西语翻译覆盖率
	// 核查时发现）。里面用到的字符串资源不用优先翻。
	@Override
	public String toString() {

		StringBuffer buffer = new StringBuffer();
		if (height != null) {
			for (int i = 0; i < height.length; i++) {
				buffer.append(" " + (i + 1) + "  ");
				// [已屏蔽-2026-09-23] height/volume 已删除（toString() 不可达，见上方注释）。
				// buffer.append(SystemUtils.getRsStr(R.string.height)+ height[i] + "  ");
				// buffer.append(SystemUtils.getRsStr(R.string.volume) + volum[i]);
				buffer.append("\n");
			}
		}

		return buffer.toString();
	}

	public OilCanTable(int oilCanId, int[] height, double[] volum) {
		super();
		this.pk_oilCanId = oilCanId;
		this.height = height;
		this.volum = volum;
	}

	public String[] getParams() {
		String[] params = { "oilCanId", "height", "volum" };
		return params;
	}
}