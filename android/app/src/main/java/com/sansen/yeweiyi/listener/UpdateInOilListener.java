package com.sansen.yeweiyi.listener;

import com.sansen.yeweiyi.db.DetailInoutRecord;

public interface UpdateInOilListener {
	void success(double beginHeight, double EndHegiht, double beginVoulme,double endVoulme,double density,int oilCan, DetailInoutRecord record);

}
