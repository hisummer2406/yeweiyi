package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.os.Handler;
import android.util.SparseArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.db.sqlite.Selector;
import com.lidroid.xutils.db.sqlite.WhereBuilder;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.Authorization;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.db.TempRecord;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;

import org.apache.poi.ss.formula.functions.T;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class DBDataManager {

    private static DBDataManager mInstance;

    private DbUtils db;
    private static GsonBuilder builder;
    private MyApplication myApplication;
    private static final String TAG = "DBDataManager";

    /**
     * Construction method init Data
     */
    private DBDataManager(Context ct) {
        db = DaoHelp.getInstance(ct);
        builder = new GsonBuilder();
        myApplication = MyApplication.getInstance();

    }

    /**
     * Singleton
     */
    public static DBDataManager getInstance() {
        if (mInstance == null) {
            mInstance = new DBDataManager(MyApplication.getInstance());
        }
        return mInstance;
    }

    /**
     * Save object to database
     */
    public void saveObject(final Object object) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.save(object);

                    } catch (DbException e) {
                        // TODO Auto-generated catch block
                        //e.printStackTrace();
                    }
                }
            });
        }

    }

    /**
     * FindAll
     *
     * @param clazz
     * @return List<T>
     */
    public <T> List<T> findAllObject(Class clazz) {
        List<T> object = null;
        try {
            object = db.findAll(clazz);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return object;
    }


    /**
     * FindAll
     *
     * @param clazz
     * @return List<T>
     */
    public <T> List<T> findTestObject(Class clazz) {
        List<T> object = null;
        try {
            object = db.findAll(WhereBuilder.b("id", ">", "17"));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return object;
    }

    /**
     * Find object by id
     *
     * @param clazz
     * @param id
     * @return dao
     */
    public Object findObjectByID(Class clazz, int id) {
        Object daoObject = null;
        try {

            daoObject = db.findById(clazz, id);

        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return daoObject;
    }

    public Object findFirst(Class clazz) {
        Object daoObject = null;
        try {
            daoObject = db.findFirst(clazz);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return daoObject;
    }

    /**
     * Update an ojbect in the database
     */
    public void UpdateObject(final Object object) {
        if (object != null) {
            ThreadManager.getShortPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        db.update(object);
                        //Log.i(TAG, "run: 线程执行完成");
                    } catch (DbException e) {
                        //e.printStackTrace();
                    }
                }
            });
        }
    }


    /**
     * Read OilTable
     *
     * @param oilCan
     */
    public void setOilTableToOilCan(OilCan oilCan) {
        int[] heights;
        double[] volumes;
        try {
            List<OilCanTable> findAll = db.findAll(Selector
                    .from(OilCanTable.class));
            int size = findAll.size();
            OilCanTable oilCanTable;
            for (int i = 0; i < size; i++) {
                oilCanTable = findAll.get(i);
                if (oilCanTable != null
                        && oilCan.getOilCanPos() == oilCanTable.getPk_oilCanId()) {
                    oilCanTable.StringToArray();

                    heights = oilCanTable.getHeight();
                    volumes = oilCanTable.getVolum();
                    oilCan.setHightArray(heights);
                    oilCan.setLitreArray(volumes);

                }
            }
            heights = null;
            volumes = null;
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
    }

    /**
     * Read ProbeToOil
     */
    public List<ProbeToOil> findAllProbeToOil() {
        List<ProbeToOil> probes = null;
        try {
            probes = db.findAll(ProbeToOil.class);

        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return probes;
    }

    /**
     * DeletTable
     *
     * @param clazz
     */
    public void DeleteTable(final Class clazz) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.dropTable(clazz);
                } catch (DbException e) {
                    // TODO Auto-generated catch block
                    //e.printStackTrace();
                }
            }
        });
    }

    /**
     * Save AramRecord
     *
     * @param AramInfo
     */

    public void setAramRecord(final String AramInfo,String guanHao,String jieZhi) {

        if (Singleton.getInstance().IsCreateAlarm){
            Alarm alarm = new Alarm(DateUtils.getYYYMMddHHmmss(), AramInfo,guanHao,jieZhi);
            saveObject(alarm);
            //db.save(alarm);

        }

    }

    /**
     * Save SaveOilRecord
     *
     * @param oilSaveInfo
     */
    public void saveSaveOilRecord(String oilSaveInfo, String remark) {
        OilSaveInfo info = new OilSaveInfo(DateUtils.getYYYMMddHHmmss(),
                remark, oilSaveInfo);
//            db.save(info);
        saveObject(info);
    }
    public void saveSaveOilRecord(StockSave stockSave) {

//            db.save(info);
        saveObject(stockSave);
    }

    /**
     * Save CommitWorkRecord
     *
     * @param workId
     * @param userId
     * @param oilsSaveInfo
     */
    public void saveCommitWorkRecord(int workId, String userId,
                                     String oilsSaveInfo) {
        CommitWork info = new CommitWork(userId, workId,
                DateUtils.getYYYMMddHHmmss(), oilsSaveInfo);
//      db.save(info);
        saveObject(info);
    }

    /**
     * Save SideleakageRecord
     *
     * @param oilsSaveInfo
     */
    public void saveSideleakageRecord(String oilsSaveInfo) {
        Sideleakage info = new Sideleakage(DateUtils.getYYYMMddHHmmss(),
                oilsSaveInfo);

        saveObject(info);
//      db.save(info);
    }

    public static <T> List<T> stringToArray(String s, Class<T[]> clazz) {
        T[] arr = new Gson().fromJson(s, clazz);

        return Arrays.asList(arr);
    }

    /**
     * String to List by Gson
     *
     * @param <T>
     * @param gsonInfo
     * @return List<Oil>
     */
    public <T> List<T> getStringToListByGson(String gsonInfo) {

        return builder.create().fromJson(gsonInfo, new TypeToken<List<T>>() {
        }.getType());
    }

    /**
     * List to String by Gson
     *
     * @return oilGsonString
     */
    public static <T> String getListToStringByGson(List<T> object) {

        return builder.create().toJson(object);
    }

    public static <T> String parseObjectWithGson(Object object, Class<T> type) {
        Gson gson = new Gson();
        String result = gson.toJson(object, type);
        return result;
    }

    /**
     * get Last AlarmRecords
     * <p>
     * ①是否重连过 ② 当前油罐需要有个标记位 如果连接过了将标志位设置1 断开连接了设置0
     * <p>
     * 判断保存时，先判断标志位如果为1的话
     *
     * @param info
     * @return
     */
    public List<Alarm> getLastAlarm(String info) {
        List<Alarm> alarms = null;
        try {
            Const.isAlarm = 1;
            if (db.tableIsExist(Alarm.class)) {
                alarms = db.findAll((Selector.from(Alarm.class)
                        .where(WhereBuilder.b("time", "=",
                                DateUtils.getYYYMMddHHmmss()).and("info",
                                "=", info))));
            }
            Const.isAlarm = 0;
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return alarms;
    }

    /**
     * findRecord by time
     *
     * @param beginTime
     * @param endTime
     * @return List
     */
    public List<OilInOut> findBetweenTimeOilInOut(int oilCanID,
                                                  String beginTime, String endTime) {

        List<OilInOut> oilIns = null;
        try {
            oilIns = db.findAll(Selector
                    .from(OilInOut.class)
                    .where("time", "between",
                            new String[]{beginTime, endTime})
                    .and("oilId", "=", oilCanID));

        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        if (oilIns == null) {
            return new ArrayList<OilInOut>();
        }
        return oilIns;
    }

    /**
     * findRecord by time
     *
     * @return List
     */
    public List<TempRecord> findTemper(int oilCanID) {

        List<TempRecord> records = null;
        try {
            records = db.findAll(Selector
                    .from(TempRecord.class)
                    .where("tanId", "=", oilCanID));

        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }

        return records;
    }

    /**
     * CommitWork
     */
    public void CommitWork(SparseArray<OilCan> oilMap, String startTime) {
        CommitWork(0, startTime, oilMap);
    }
    /**
     * CommitWork
     * <p>
     * v b
     */
    public void CommitWork(int workID,String startTime, SparseArray<OilCan> oilMap) {
        // 接班的高度、交班的高度、中间数据变化量
        String curTime = DateUtils.getYYYMMddHHmmss();
        List<OilWork> oils = new ArrayList<OilWork>();

        CommitWork commitWork = new CommitWork();

        String gsonInfo = null;
        /**
         * CommitWork最近一条记录，需要获取开始时间跟开始高度
         */
        List<CommitWork> commitWorks = Sort(CommitWork.class, true);


        if (commitWorks != null && commitWorks.size() != 0) {
            gsonInfo = commitWorks.get(0).getInfo();
            commitWork = commitWorks.get(0);
        } else {
            ToastUtils.showToast("未开始交接",5);
         return;
        }
        commitWork.setEndTime(curTime);


        List<OilWork> oilWorks = stringToArray(gsonInfo, OilWork[].class);
        // 循环得到所有的油罐
        int key = 0;
        for (int index = 0; index < oilMap.size(); index++) {
            key = oilMap.keyAt(index);
            double shellVolume = 0.0;
            double lastEndHeight = 0;
           // String lastEndTime = "";
            OilWork oilWork;
            if (oilWorks.size() > key - 1) {
                oilWork = oilWorks.get(key - 1);

              //  lastEndTime = commitWorks.get(0).getTime();
            } else {
                oilWork = new OilWork();
                oilWork.setOilId(key);
             //   lastEndTime = DateUtils.getYYYMMddHHmmss();
            }

            // 上一次开始的时间、高度
            if (oilWorks != null) {
                lastEndHeight = oilWork.getEndHeight();
            }
            OilWork oilWork2 = new OilWork();
            oilWork2=oilWork;
            oilWork2.setOilId(oilMap.get(key).getOilCanPos());
            // 设置结束的高度
          //  oilWork2.setBeginHeight(lastEndHeight);
            oilWork2.setEndHeight(oilMap.get(key).getOilLevel());
            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(oilWork2.getOilId());
            double CurOilVolume = DataUtils.getLitre(oilWork2.getEndHeight(),
                    oilCan.getHightArray(), oilCan.getLitreArray());
            oilWork2.setEndVolume(CurOilVolume);
            oilWork2.setShellVolume(DoubleUtil.double2(-CurOilVolume +oilWork2.getBeginVolume()));
            oilWork2.setShellWeight(oilWork2.getShellVolume() * oilCan.getDensity());
            oils.add(oilWork2);
        }

        String gson = getListToStringByGson(oils);
        commitWork.setInfo(gson);
        UpdateObject(commitWork);
      //  saveObject(commitWork);
//            db.save(commitWork);
    }
    public void CommitWork(SparseArray<OilCan> oilMap) {
        CommitWorkStart(0, oilMap);
    }
    /**
     * CommitWork
     * <p>
     * v b
     */
    public void CommitWorkStart(int workID,  SparseArray<OilCan> oilMap) {
        // 接班的高度、交班的高度、中间数据变化量
        String curTime = DateUtils.getYYYMMddHHmmss();
        List<OilWork> oils = new ArrayList<OilWork>();

        CommitWork commitWork = new CommitWork();
        commitWork.setWorkId(workID);
        String gsonInfo = null;
        commitWork = new CommitWork();


        commitWork.setTime(curTime);
        commitWork
                .setInfo("[{\"shellVolume\":0.0,\"shellWeight\":0.0,\"beginHeight\":0.0,\"endHeight\":0.0,\"oilId\":0}]");
        gsonInfo = commitWork.getInfo();

        // 循环得到所有的油罐
        int key = 0;
        for (int index = 0; index < oilMap.size(); index++) {
            key = oilMap.keyAt(index);
            double shellVolume = 0.0;

            OilWork oilWork;
            OilWork oilWork2 = new OilWork();
            oilWork2.setOilId(oilMap.get(key).getOilCanPos());
            oilWork2.setBeginHeight(oilMap.get(key).getOilLevel());
            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(oilWork2.getOilId());
            double CurOilVolume = DataUtils.getLitre(oilWork2.getBeginHeight(),
                    oilCan.getHightArray(), oilCan.getLitreArray());
            oilWork2.setBeginVolume(CurOilVolume);
            oils.add(oilWork2);
        }

        String gson = getListToStringByGson(oils);
        commitWork.setInfo(gson);
        saveObject(commitWork);
    }

//    /**
//     * CommitWork
//     * <p>
//     * v b
//     */
//    public void CommitWork(int workID,String startTime, SparseArray<OilCan> oilMap) {
//        // 接班的高度、交班的高度、中间数据变化量
//        String curTime = DateUtils.getYYYMMddHHmmss();
//        List<OilWork> oils = new ArrayList<OilWork>();
//
//        CommitWork commitWork = new CommitWork();
//        commitWork.setTime(startTime);
//        commitWork.setEndTime(curTime);
//        commitWork.setWorkId(workID);
//        String gsonInfo = null;
//        /**
//         * CommitWork最近一条记录，需要获取开始时间跟开始高度
//         */
//        List<CommitWork> commitWorks = Sort(CommitWork.class, true);
//
//
//        if (commitWorks != null && commitWorks.size() != 0) {
//            gsonInfo = commitWorks.get(0).getInfo();
//        } else {
//            commitWork = new CommitWork();
//            commitWork.setEndTime(DateUtils.getTimeString(SystemUtils
//                    .getFirstCreateTime(myApplication)));
//
//            commitWork.setTime(startTime);
//            commitWork
//                    .setInfo("[{\"shellVolume\":0.0,\"shellWeight\":0.0,\"beginHeight\":0.0,\"endHeight\":0.0,\"oilId\":0}]");
//            commitWorks.add(commitWork);
//            gsonInfo = commitWork.getInfo();
//        }
//
//        List<OilWork> oilWorks = stringToArray(gsonInfo, OilWork[].class);
//
//        // 循环得到所有的油罐
//        int key = 0;
//        for (int index = 0; index < oilMap.size(); index++) {
//            key = oilMap.keyAt(index);
//            double shellVolume = 0.0;
//            double lastEndHeight = 0;
//            String lastEndTime = "";
//            OilWork oilWork;
//            if (oilWorks.size() > key - 1) {
//                oilWork = oilWorks.get(key - 1);
//
//                lastEndTime = commitWorks.get(0).getTime();
//            } else {
//                oilWork = new OilWork();
//                oilWork.setOilId(key);
//                lastEndTime = DateUtils.getYYYMMddHHmmss();
//            }
//
//            // 上一次开始的时间、高度
//            if (oilWorks != null) {
//                lastEndHeight = oilWork.getEndHeight();
//            }
//
//            OilWork oilWork2 = new OilWork();
//            oilWork2.setOilId(oilMap.get(key).getOilCanPos());
//            // 设置结束的高度
//            oilWork2.setBeginHeight(lastEndHeight);
//            oilWork2.setEndHeight(oilMap.get(key).getOilLevel());
//            OilCan oilCan = OilcanDataManager.getInstance(myApplication).getData(oilWork2.getOilId());
//            double lastEndOilVolume = DataUtils.getLitre(oilWork2.getBeginHeight(),
//                    oilCan.getHightArray(), oilCan.getLitreArray());
//            double CurOilVolume = DataUtils.getLitre(oilWork2.getEndHeight(),
//                    oilCan.getHightArray(), oilCan.getLitreArray());
//            oilWork2.setShellVolume(DoubleUtil.double2(Math.abs(CurOilVolume - lastEndHeight)));
//            oilWork2.setShellWeight(oilWork2.getShellVolume() * oilCan.getDensity());
//            oils.add(oilWork2);
//        }
//
//        String gson = getListToStringByGson(oils);
//        commitWork.setInfo(gson);
//        saveObject(commitWork);
////            db.save(commitWork);
//    }

    /**
     * Data sorting
     *
     * @param clazz
     * @param isDesc
     * @return List
     */
    public <T> List<T> Sort(Class clazz, boolean isDesc) {

        List<T> findAll = new ArrayList<T>();
        try {
            findAll = db.findAll(Selector.from(clazz).orderBy("id", isDesc));
        } catch (DbException e) {
            //e.printStackTrace();
        }
        if (findAll == null)
            findAll = new ArrayList<T>();

        return findAll;
    }

    public <T> List<T> Sort(int pos, Class clazz, boolean isDesc) {

        List<T> findAll = new ArrayList<T>();
        try {
            findAll = db.findAll(Selector.from(clazz).where("oilId", "=", pos).orderBy("id", isDesc));
        } catch (DbException e) {
            //e.printStackTrace();
        }
        if (findAll == null)
            findAll = new ArrayList<T>();
        return findAll;
    }

    public List<OilInOut> findInOilByPos(int pos) {
        List<OilInOut> oilInOut = null;
        try {
            oilInOut = db.findAll(Selector.from(OilInOut.class).where("oilId", "=", pos));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        if (oilInOut == null)
            oilInOut = new ArrayList<OilInOut>();
        return oilInOut;
    }

    /**
     * The current user exists
     *
     * @return true find. false no find
     */
    public boolean isUserExist(User login) {

        List<User> findAll = null;

        try {

            findAll = db.findAll(Selector.from(User.class)
                    .where("id", "=", login.getId())
                    .and("psw", "=", login.getPsw()));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }

        if (findAll == null || findAll.size() == 0) {
            return false;
        } else {
            return true;
        }

    }

    /**
     * The current object exists
     *
     * @param id
     * @return ture yes false no
     */
    public boolean chekIsExists(Class clazz, String id) {
        List<Object> findAll = null;
        try {
            findAll = db.findAll(Selector.from(clazz).and("id", "==", id));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        if (findAll == null | findAll.size() == 0) {
            return false;
        } else {
            return true;
        }

    }

    /**
     * Check whether the user data is empty
     *
     * @return true not empty. false is empty
     */
    public boolean checkObjectNotNull(Class clazz) {
        List<User> findAll = null;
        try {

            findAll = db.findAll(clazz);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        if (findAll == null | findAll.size() == 0) {
            return false;
        } else {
            return true;
        }

    }

    public void SaveDB(int oilCanPos, String info,String guanHao,String jieZhi) {
        String alarmInfo = oilCanPos + info;

        List<Alarm> alarms = getLastAlarm(alarmInfo);
        if (alarms == null || alarms.size() == 0) {

            // 如果里面试空数据，直接保存
            setAramRecord(alarmInfo,guanHao,jieZhi);
        }

    }

    public Authorization findAuthorization(String id) {
        try {

            return db.findById(Authorization.class, id);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return null;
    }

    /**
     * FindAll
     *
     * @param clazz
     * @return List<T>
     */
    public <T> List<T> findAllObject(Class clazz, String column, String hex) {
        List<T> object = null;
        try {

            object = db.findAll(Selector.from(clazz).where(column, "=", hex));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return object;
    }

    public void removeObject(final User user) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.delete(user);
                } catch (DbException e) {
                    // TODO Auto-generated catch block
                    //e.printStackTrace();
                }
            }
        });
    }

    public List<User> findUser(Class<User> clazz) {

        try {
            return db.findAll(clazz);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return null;
    }

    public User findAdminUser(Class clazz) {

        try {
            return (User) db.findById(clazz, "admin");
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return null;
    }

    public void SaveOilCanTable(String path) {

        ExportExcel<OilCanTable> exportExcel = new ExportExcel<OilCanTable>();
        String headers[] = new String[31];
        headers[0] = SystemUtils.getRsStr(R.string.tan_no);
        for (int i = 1; i < headers.length; i++) {
            headers[i] = i + "";
        }
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<OilCanTable> dataset = db.findAllObject(OilCanTable.class);

        // 存储到U盘
        // String path = "/storage/usbhost1";
        File file = new File(path);

        if (!file.exists())
            file.mkdir();
        OutputStream out = null;
        try {
            file = new File(path + "/" + "OilCan_set" + ".xls");
            out = new FileOutputStream(file);
            exportExcel.exportOilCanTable(SystemUtils.getRsStr(R.string.tan_table_data), headers, dataset,
                    new OilCanTable().getParams(), out, "");


        } catch (Exception e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        try {
            out.close();
            handler.sendEmptyMessageDelayed(0, 3000);
        } catch (IOException e) {
            SystemUtils.showRsToast(R.string.io_no_close);

            //e.printStackTrace();
        }
        double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
    }

    Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            SystemUtils.showRsToast(R.string.data_save_success);
        }

        ;
    };

    public void saveAlarm(int oilCanPos, OilMessage oilMessage) {
        sendOilMsg(oilMessage);
        if (oilMessage.getType()==OilMessage.PRO_ERR_CONN){
            return;
        }
        //如果里面是空数据，直接保存
        setAramRecord(oilMessage.toString(),oilMessage.getOilCanId()+"",oilMessage.getJieZhi());
    }

    public void sendOilMsg(final OilMessage msg) {

    }
      /*
    删除所有入库记录
     */

    public void delteAllInOilRecord(final Class<?> entityType) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.deleteAll(entityType);
                } catch (DbException e) {
                    // TODO Auto-generated catch block
                    //e.printStackTrace();
                }
            }
        });

    }

    /*
    单个删除入库记录
     */

    public void delteRecord(final Object object) {
        ThreadManager.getShortPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    db.delete(object);
                } catch (DbException e) {
                    // TODO Auto-generated catch block
                    //e.printStackTrace();
                }
            }
        });

    }

    /**
     * 罐表复制 将记录a中的数据，复制到记录b中的数据 如果罐表2有数据那么就直接覆盖
     */
    public void OilCanTableCopy(int startId, int endId) {

        try {

            OilCanTable oilCanTable = db.findById(OilCanTable.class, startId);

            oilCanTable.setPk_oilCanId(endId);
            if (db.findById(OilCanTable.class, endId) == null) {
                saveObject(oilCanTable);
//                db.save(oilCanTable);
            } else {
                UpdateObject(oilCanTable);
//                db.update(oilCanTable);
            }
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }

    }


    public String ResolveInt(int value, int length) {
        String result = value + "";

        result = Integer.toHexString(Integer.parseInt(result));

        while (result.length() < length) {
            result = "0" + result;
        }

        return result.toUpperCase();
    }

    public HashMap<String, Object> findObject(Class clazz, String id, String beginTime, String endTime, int limit, int offset, boolean order) {
        HashMap<String, Object> maps = new HashMap<String, Object>();
        List<T> alarms = new ArrayList<>();
        long count = 0;

        try {
            if (id != null) {
                alarms = db.findAll(Selector.from(clazz).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(clazz).where("oilId", "=", id).and("time", "between",
                        new String[]{beginTime, endTime}));

            } else {
                alarms = db.findAll(Selector.from(clazz).where("time", "between",
                        new String[]{beginTime, endTime}).limit(limit).offset(offset).orderBy("id", order));
                count = db.count(Selector.from(clazz).where("time", "between",
                        new String[]{beginTime, endTime}));
            }
        } catch (DbException e) {
            //e.printStackTrace();
        }
        maps.put("list", alarms);
        maps.put("count", count);

        return maps;
    }

    public Object findLast(Class clazz) {
        Object daoObject = null;
        try {
            daoObject = db.findFirst(Selector.from(clazz).orderBy("id", true));
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return daoObject;
    }

    public <T> List<T> findLimitRecord(Class clazz, int limit, int offset, boolean desc, String beginTime, String endTime) {
        List<T> alarms = null;
        try {
            alarms = findLimt(clazz, limit, offset, "id", desc, beginTime, endTime);
        } catch (DbException e) {
            //e.printStackTrace();
        }
        if (alarms != null) {
            return alarms;
        }
        return new ArrayList<>();
    }

    //优化过后的
    public <T> List<T> findLimt(Class clazz, int limit, int offset, String columnName, boolean desc, String beginTime, String endTime) throws DbException {
        List<T> obects = db.findAll(Selector.from(clazz).where("time", "between", new String[]{beginTime, endTime}).limit(limit).offset(offset * limit).orderBy(columnName, desc));
        if (obects != null) {
            return obects;
        }
        return new ArrayList<>();
    }

    public <T> List<T> findLimit(Class clazz, int limit, int offset, boolean des) {
        List<T> temps = null;
        try {
            temps = db.findAll(Selector.from(clazz).limit(limit).offset(offset * limit).orderBy("time", des));
        } catch (DbException e) {
            //e.printStackTrace();
        }
        if (temps != null) {
            return temps;
        }
        return new ArrayList<>();
    }

    public <T> List<T> findLimit(Class clazz, int oilid, int limit, int offset, boolean des) {
        List<T> objects = null;
        try {
            objects = db.findAll(Selector.from(clazz).where("oilId", "=", oilid).limit(limit).offset(offset * limit).orderBy("time", true));
        } catch (DbException e) {
            //e.printStackTrace();
        }
        if (objects != null) {
            return objects;
        }
        return new ArrayList<>();
    }
}
