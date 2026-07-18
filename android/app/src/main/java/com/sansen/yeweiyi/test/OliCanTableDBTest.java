package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.db.OilCanTable;

import java.util.List;

public class OliCanTableDBTest extends AndroidTestCase {
	private OilCanTable olican;
	private DbUtils db;

	//
	public void save() {
		try {
			if (olican != null) {
				System.out.println(olican.toString());
				db.saveBindingId(olican);
				System.out.println("写入成功");
			}

		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void findAll() {

		try {
			List<OilCanTable> findAll = db.findAll(OilCanTable.class);

			for (OilCanTable oliCanTable : findAll) {

				System.out.println(oliCanTable.toString());
			}

		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

}
