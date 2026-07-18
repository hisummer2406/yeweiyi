package com.sansen.yeweiyi.factory;

import android.util.Log;

import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.utils.DataUtils;

public class EllipseTable implements ICreateTable {

	@Override
	public OilCanTable craeteOilCanTalbe(double L, double R, double b,
			double c2) {

	/*
     * L: 筒体长度 /m
     * R: 筒体半径 /m  (对应Excel中的a)
     * b: 单侧封头高度 /m  (对应Excel中的b)
     * h: 液位高度 /m
     * 
     * 公式来源：Excel「椭圆油罐体积（带封头）」
     * 总体积 = 筒体体积 + 单侧封头体积
     * 若为两端封头，将b改为左右封头分别计算后求和即可
     */

		double maxH = 2 * R;          // 满罐液位 = 直径
		double step = 0.01;           // 步长0.01m = 1cm，与Excel一致
		int arraySize = (int) Math.round(maxH / step);

		int[] height = new int[arraySize];
		double[] volume = new double[arraySize];

		double h = step; // 从1cm开始，与Excel第12行A12=1对应

		for (int i = 0; i < arraySize; i++) {
			// ========== 1. 筒体体积（严格复刻Excel公式） ==========
			double argAsin = (R - h) / R;
			// asin定义域保护，防止浮点溢出NaN
			argAsin = Math.max(-1.0, Math.min(1.0, argAsin));

			double term1 = 0.5 * Math.PI * R * R;
			double term2 = R * R * Math.asin(argAsin);
			double term3 = (R - h) * Math.sqrt(h * (2 * R - h));
			double vCylinder = L * (term1 - term2 - term3);

			// ========== 2. 封头体积（严格复刻Excel公式，单侧） ==========
			double vHead = Math.PI * b * h * h
					- (Math.PI * b * h * h * h) / (3.0 * R);

			// 总体积 m³ → 升 (*1000)，Excel保留2位小数
			double vSum = (vCylinder + vHead) * 1000;

			height[i] = (int) (h * 1000);  // 液位mm
			volume[i] = DataUtils.doubleFoment(vSum);

			h += step;
		}

		OilCanTable oilCanTable = new OilCanTable();
		oilCanTable.setHeight(height);
		oilCanTable.setVolum(volume);
		return oilCanTable;

	}

	@Override
	public OilCanTable craeteOilCanTalbe(double L, double K, double G) {
		// 参数映射：
		// L = 长方体长度(m)
		// K = 长方体宽度(m)
		// G = 罐体总高度(m)
		double length = L;
		double width = K;
		double totalHeight = G;

		double startH = 0.01; // 起始液位 1cm
		double step = 0.01;   // 每档1cm

		// 修正档位计算，避免减1丢失档位，向上取整
		int count = (int) Math.ceil(totalHeight / step);
		if (count < 1) count = 1; // 防止罐高过小无数据

		int[] height = new int[count];
		double[] volume = new double[count];

		for (int i = 0; i < count; i++) {
			// 直接计算当前高度，消除浮点累加误差
			double h = startH + i * step;
			// 液位不能超过罐体总高
			double calH = Math.min(h, totalHeight);

			// 长方体容积公式 V = 长 × 宽 × 液位高度
			double volM3 = length * width * calH;
			// 立方米转升
			double volL = volM3 * 1000;

			// 保留两位小数
			volume[i] = Math.round(volL * 100) / 100.0;
			// 米转毫米存储
			height[i] = (int) (h * 1000);

			Log.e("TAG", "高度米：" + h + "，容积升：" + volume[i]);
		}

		OilCanTable oilCanTable = new OilCanTable();
		oilCanTable.setHeight(height);
		oilCanTable.setVolum(volume);
		return oilCanTable;
	}

	@Override
	public OilCanTable craeteOilCanTalbe(double diameter, double totalHeight) {
		// 参数：diameter罐体直径(m)，totalHeight罐体总高度(m)
		double r = diameter / 2.0;
		double startH = 0.01; // 起始液位1cm
		double step = 0.01;   // 步长1cm

		int count = (int) Math.ceil(totalHeight / step);
		if (count < 1) count = 1;

		int[] height = new int[count];
		double[] volume = new double[count];

		for (int i = 0; i < count; i++) {
			double h = startH + i * step;
			double calH = Math.min(h, totalHeight);

			// 立式圆柱容积公式 V = π * r² * h
			double volM3 = Math.PI * r * r * calH;
			double volL = volM3 * 1000;

			volume[i] = DataUtils.doubleFoment(volL);
			height[i] = (int) (h * 1000);

			Log.e("TAG", "高度米：" + h + "，容积升：" + volL);
		}
		OilCanTable oilCanTable = new OilCanTable();
		oilCanTable.setHeight(height);
		oilCanTable.setVolum(volume);
		return oilCanTable;
	}

}
