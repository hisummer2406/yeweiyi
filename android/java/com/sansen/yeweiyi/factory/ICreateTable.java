package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.db.OilCanTable;

public interface ICreateTable {

	OilCanTable craeteOilCanTalbe(double L, double R, double c1, double c2);
	OilCanTable craeteOilCanTalbe(double L,double K,double G);
	OilCanTable craeteOilCanTalbe(double L,double K);
}
