package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.Oil;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.SideleaInfo;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.manager.DBDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TestDB extends AndroidTestCase {
	private DBDataManager mInstance;
	private DbUtils dbUtils;

	private void init() {
		MyApplication application = MyApplication.getInstance();
		mInstance = application.getmDBManager();
		dbUtils = DbUtils.create(application);
	}

	/**
	 * 测试报警记录数据
	 */
	public void setAram() {
		init();
		try {
			dbUtils.deleteAll(Alarm.class);
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		// for (int i = 0; i < 100; i++) {
		// mInstance.setAramRecord("油罐"+i+"断开连接");
		// }

	}

	public void getAram() {
		init();
		List<Alarm> aramRecord = mInstance.findAllObject(Alarm.class);
		System.out.println("记录数：" + aramRecord.size());
		for (Alarm alarm : aramRecord) {
			System.out.println("记录" + alarm.toString());
		}
	}

	/**
	 * 测试存油记录信息
	 */
	public void testAram() {
		init();

		for (int i = 0; i < 100; i++) {
			mInstance.setAramRecord("油罐" + i + "断开连接",i+"","");
		}

	}

//	public void setSaveOil() {
//		init();
//		/**
//		 * 保存 ①list转换为String ②将String 保存到OilSaveInfo表中 读取 ①从数据中读取list 数据记录集合
//		 * ②从数据集合中获取每条记录中的Gson字符串 ③对Gson字符串进行解析，解析为Oil对象
//		 */
//
//		OilWork oil = new OilWork();
//		OilWork oilWork1 = new OilWork(1, "100", "200", "300", "400");
//		OilWork oilWork2 = new OilWork(2, "200", "300", "400", "500");
//
//		List<OilWork> oils = new ArrayList<OilWork>();
//		oils.add(oilWork1);
//		oils.add(oilWork2);
//		String gsonInfo = mInstance.getListToStringByGson(oils);
//
//		mInstance.saveSaveOilRecord(gsonInfo);
//
//	}

	public void getOilSaveInfo() {
		init();
		List<OilSaveInfo> list = mInstance.findAllObject(OilSaveInfo.class);
		System.out.println("OilSaveInfos  " + list.size()); // ==1
		for (OilSaveInfo oilSaveInfo : list) {
			String info = oilSaveInfo.getOilInfoGosn();
			System.out.println("oilSaveInfo  " + oilSaveInfo.toString());
			List<Oil> oilList = mInstance.getStringToListByGson(info);
			System.out.println("Oil  " + oilList.size());
			for (Oil oil : oilList) {
				System.out.println(oil.toString());
			}
		}
	}

	

	public void setSaveCommitWork() {
		init();
		/**
		 * 保存 ①list转换为String ②将String 保存到OilSaveInfo表中 读取 ①从数据中读取list 数据记录集合
		 * ②从数据集合中获取每条记录中的Gson字符串 ③对Gson字符串进行解析，解析为Oil对象
		 */

		Oil oil1 = new Oil(1, "23", "", "", "27");
		Oil oil2 = new Oil(2, "78", "", "", "98");
		List<Oil> oils = new ArrayList<Oil>();
		oils.add(oil1);
		oils.add(oil2);
		String gsonInfo = mInstance.getListToStringByGson(oils);

	

	}

	public void getCommitWorkInfo() {
		init();
		List<OilSaveInfo> list = mInstance.findAllObject(OilSaveInfo.class);
		System.out.println("OilSaveInfos  " + list.size()); // ==1
		for (OilSaveInfo oilSaveInfo : list) {
			String info = oilSaveInfo.getOilInfoGosn();
			System.out.println("oilSaveInfo  " + oilSaveInfo.toString());
			List<Oil> oilList = mInstance.getStringToListByGson(info);
			System.out.println("Oil  " + oilList.size());
			for (Oil oil : oilList) {
				System.out.println(oil.toString());
			}
		}
	}

	public void setSaveSideleakage() {
		init();
		/**
		 * 保存 ①list转换为String ②将String 保存到OilSaveInfo表中 读取 ①从数据中读取list 数据记录集合
		 * ②从数据集合中获取每条记录中的Gson字符串 ③对Gson字符串进行解析，解析为Oil对象
		 */

		SideleaInfo oil1 = new SideleaInfo(1, "该油罐发生测漏");
		SideleaInfo oil2 = new SideleaInfo(2, "该油罐也发生测漏");
		List<SideleaInfo> oils = new ArrayList<SideleaInfo>();
		oils.add(oil1);
		oils.add(oil2);
		String gsonInfo = mInstance.getListToStringByGson(oils);

		mInstance.saveSideleakageRecord(gsonInfo);

	}

	public void getSideleakageInfo() {
		init();
		List<Sideleakage> list = mInstance.findAllObject(Sideleakage.class);
		System.out.println("OilSaveInfos  " + list.size()); // ==1
		for (Sideleakage oilSaveInfo : list) {
			String info = oilSaveInfo.getInfo();
			System.out.println(info);
			System.out.println("oilSaveInfo  " + oilSaveInfo.toString());
			List<SideleaInfo> oilList = mInstance.getStringToListByGson(info);
			System.out.println("Oil  " + oilList.size());
			for (SideleaInfo oil : oilList) {
				System.out.println(oil.toString());
			}
		}
	}

	public void setOilIn() {
		init();
		try {
			dbUtils.deleteAll(OilInOut.class);
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}

		OilInOut oilIn = new OilInOut();
		oilIn.setOilId(1);
		oilIn.setBeginHeigth(100);
		oilIn.setEndHeigth(200);

		OilInOut oilIn2 = new OilInOut();
		oilIn2.setOilId(2);
		oilIn2.setBeginHeigth(200);
		oilIn2.setEndHeigth(300);

		mInstance.saveObject(oilIn);
		mInstance.saveObject(oilIn2);
	}

	public void getOilIn() {
		init();
		List<OilInOut> oilIns = mInstance.findAllObject(OilInOut.class);
		for (OilInOut oilIn : oilIns) {
			System.out.println(oilIn.toString());
		}
	}

	public void setProbeToOil() {
		init();
		ProbeToOil probeToOil = new ProbeToOil();
		probeToOil.setPk_oilCanId(1);
		probeToOil.setProbeId("25");
		probeToOil.setProbeType(1);

		try {
			dbUtils.save(probeToOil);
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}

	}

	public void SaveUser() {
		init();
		// 只有一个超级管理员账户
		User user = new User();
		user.setId("admin");
		user.setPsw("123456");
		try {
			dbUtils.save(user);
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void FindUser() {
		init();
		try {
			List<User> findAll = dbUtils.findAll(User.class);
			for (User user : findAll) {
				System.out.println(user.toString());
			}
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void findBOilInOut() {

		init();
		List<OilInOut> oilIns;
		try {
			oilIns = dbUtils.findAll(OilInOut.class);
			if (oilIns != null) {
				for (OilInOut oilIn : oilIns) {
					System.out.println(oilIn.toString());
				}
			}

		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}

	}

	public List<OilInOut> findBetweenTimeOilInOut(int oilCanID,
												  String beginTime, String endTime) {

		init();
		List<OilInOut> oilIns = null;
		try {
			oilIns = dbUtils.findAll(Selector
					.from(OilInOut.class)
					.where("time", "between",
							new String[] { beginTime, endTime })
					.and("oilId", "=", oilCanID));

		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		return oilIns;
	}

	public void DeleteAllOilIN() {
		init();

		try {
			dbUtils.dropTable(OilInOut.class);
			dbUtils.execQuery("delete from sqlite_sequence; select * from sqlite_sequence; update sqlite_sequence set seq=0 where name=oilin");
		} catch (Exception e) {
			// TODO: handle exception
		}
	}

	public void CommitWork(String userID, Map<Integer, OilCan> oilMap) {
		List<CommitWork> commitWorks = Sort(CommitWork.class, false);
		String beginHeight;
		String beginTime;
		String gsonString = commitWorks.get(0).getInfo();

	}

	/**
	 * 提交工作
	 * 
	 */
//	public void CommitWork(String userID, int workID,
//			Map<Integer, OilCan> oilMap, String beginHeight, String beginTime) {
//		// 接班的高度、交班的高度、中间数据变化量
//		String curTime = DateUtils.getYYYMMddHHmmss();
//		List<OilWork> oils = new ArrayList<OilWork>();
//		String endWorkHegith;
//		String shellVolume = null;
//
//		CommitWork commitWork = new CommitWork();
//		commitWork.setTime(curTime);
//		commitWork.setUserID(userID);
//		commitWork.setWorkId(1);
//
//		for (int i = 1; i <= oilMap.size(); i++) {
//			OilWork oil = new OilWork();
//			oil.setOilId(oilMap.get(i).getOilCanPos());
//			oil.setBeginHeight(beginHeight);
//			oil.setEndHeight(oilMap.get(i).getOilLevel() + "");
//			List<OilInOut> oilInOuts = findBetweenTimeOilInOut(1, curTime,
//					beginTime);
//			for (OilInOut oilIn : oilInOuts) {
//
//				if (Double.parseDouble(oilIn.getInVolum()) < 0) {
//					shellVolume += oilIn.getInVolum();
//				}
//			}
//			oil.setShellVolume(shellVolume);
//			oils.add(oil);
//
//		}
//
//		String gsonInfo = mInstance.getListToStringByGson(oils);
//
//		commitWork.setInfo(gsonInfo);
//
//		try {
//			dbUtils.save(commitWork);
//		} catch (DbException e) {
//			// TODO Auto-generated catch block
//			//e.printStackTrace();
//		}
//	}

	public void testSort() {
		List<OilInOut> sort = Sort(OilInOut.class, false);
		for (OilInOut oilIn : sort) {
			System.out.println(oilIn.toString());
		}
	}

	public <T> List<T> Sort(Class clazz, boolean isDesc) {
		init();
		List<T> findAll = null;
		try {
			findAll = dbUtils.findAll(Selector.from(clazz)
					.orderBy("id", isDesc));
		} catch (DbException e) {
			//e.printStackTrace();
		}

		return findAll;
	}

	public void delte() {
		init();
		try {
			dbUtils.dropTable(CommitWork.class);
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	/**
	 * 得到探针对照表中的数据
	 */
	public void getProbeData() {
		init();
		try {
			List<ProbeToOil> findAll = dbUtils.findAll(ProbeToOil.class);
			for (ProbeToOil probeToOil : findAll) {
				System.out.println(probeToOil.toString());
			}
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void DeleteProbe() throws DbException {
		init();
		dbUtils.deleteById(ProbeToOil.class, 2);
		dbUtils.deleteById(ProbeToOil.class, 3);
		List<ProbeToOil> findAll = dbUtils.findAll(ProbeToOil.class);
		for (ProbeToOil probeToOil : findAll) {
			System.out.println(probeToOil.toString());
		}

	}

	public void findOilCanSet() {
		init();
		try {
			List<OilCanSet> oilCanSets = dbUtils.findAll(OilCanSet.class);
			for (OilCanSet oilCanSet : oilCanSets) {
				System.out.println(oilCanSet.toString());
			}
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void findAllOilCanTalbeArray() {
		init();
		try {
			List<OilCanTable> oilCanSets = dbUtils.findAll(OilCanTable.class);
			for (OilCanTable oilCanSet : oilCanSets) {
				System.out.println(oilCanSet.toString());
			}
		} catch (DbException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
	}

	public void SaveOilCanTalbeArray() {
		init();
		if (dbUtils != null) {
			try {
				// OilTableArray oilTableArray =new OilTableArray();
				// double[] height = {1d,2d,3d,};
				// oilTableArray.setH eight(height);
				// double[] volum = {1.5d,2.6d,3.9d,};
				// oilTableArray.setVolum(volum);
				//
				// oilTableArray.ArrayToString();
				//
				// dbUtils.save(oilTableArray);

				List<OilCanTable> oilCanSets = dbUtils
						.findAll(OilCanTable.class);
				for (OilCanTable oilTableArray1 : oilCanSets) {
					oilTableArray1.StringToArray();
					System.out.println(oilTableArray1.toString());
				}
			} catch (DbException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
		} else {
			System.out.println("等于空啊啊啊");
		}

	}

	public void test() {
		DBDataManager getmDBManager = MyApplication.getInstance()
				.getmDBManager();
		List<User> findAllObject = getmDBManager.findAllObject(User.class);
		for (User object : findAllObject) {
			System.out.println(object.toString());
		}
	}
}