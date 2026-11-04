package com.sansen.yeweiyi.db.help;

import android.content.Context;
import android.util.Log;

import com.lidroid.xutils.DbUtils;
import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.LevelmeterInfo;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.Authorization;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.FYF;
import com.sansen.yeweiyi.db.FyfRecord;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.OilTest;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.db.SmsStencil;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.db.TempRecord;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

public class DaoHelp {

    private static final String TAG = "DaoHelp";
    private static DbUtils db;
    private static DaoHelp instance;

    /**
     */
    public static DbUtils getInstance(Context ct) {

        if (instance == null) {
            instance = new DaoHelp(ct);
        }
        return db;
    }

    /**
     * Create the database and create tables
     */
    public DaoHelp(Context ct) {
        boolean isFirst = true;

        // 创建数据库
        createDB(ct);
        try {
            isFirst = !db.tableIsExist(User.class);
        } catch (DbException e) {
            Log.e(TAG, "DaoHelp 数据库操作失败", e);
        }
        // 创建数据表
        createTable();

        // 如果应用是第一次创建需要在表中写入基本数据
        if (isFirst) {
            initTalbeData();
        }


    }

    /**
     * Create sansen database
     */
    private void createDB(Context ct) {
//			DaoConfig config = new DaoConfig(ct);
//			// db名
//			config.setDbName("sansen");
//			// db版本
//			config.setDbVersion(1);
//			// 创建数据库
//			db = DbUtils.create(config);
        db = DbUtils.create(ct, "sansen", 8,
                new DbUtils.DbUpgradeListener() {
                    @Override
                    public void onUpgrade(DbUtils dbUtils, int oldVersion, int newVersion) {
                        Log.i("数据库版本", "旧版本" + oldVersion + "新版本" + newVersion);

                        switch (oldVersion){
                            case 1:
                                try {
                                    //为数据库表ShoppingCar添加shopId字段
                                    dbUtils.createTableIfNotExist(SmsStencil.class);
                                    dbUtils.createTableIfNotExist(OilCanMapper.class);
                                    dbUtils.createTableIfNotExist(TempRecord.class);
                                    dbUtils.createTableIfNotExist(DetailInoutRecord.class);
                                    dbUtils.createTableIfNotExist(OilTest.class);
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilCanMapper add isShow boolean");
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add involumvt double");
                                } catch (Exception e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败", e);
                                }
                                break;
                            case 2:
                                try {
                                    dbUtils.createTableIfNotExist(TempRecord.class);
                                    dbUtils.createTableIfNotExist(DetailInoutRecord.class);
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add isShow boolean");
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add involumvt double");
                                } catch (DbException e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败", e);
                                }
                                break;
                            case 3:
                                try {
                                    dbUtils.createTableIfNotExist(DetailInoutRecord.class);
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add isShow boolean");
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add involumvt double");
                                } catch (DbException e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败", e);
                                }
                                break;
                            case 4:
                                try {
                                    dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilInOut add involumvt double");
                                } catch (DbException e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败", e);
                                }
                            case 5:  // 从版本4升级到5
                                try {
                                    dbUtils.createTableIfNotExist(OilTest.class);
                                    dbUtils.execNonQuery("ALTER TABLE commitwork ADD COLUMN endtime TEXT");
                                } catch (DbException e) { }
                                // 注意：如果你希望继续执行后续升级，不要 break
                                break;
                        }

                        // 出入库记录/详情加 source 列（手动补录 vs 自动检测，见 zhongchengyeweiyi#42 待办2）。
                        // 故意放在上面这个 switch 外面、按 oldVersion < 7 无条件判断，不是 case 6 分支——
                        // DbUtils.create() 只调用一次 onUpgrade（传真实的 oldVersion/newVersion=7），
                        // switch(oldVersion) 上面每个 case 都直接 break，1-5 版本的设备一次性升到 7
                        // 时只会命中自己那个 case 就退出，永远走不到原来写在 case 6 里的这三条 ALTER；
                        // 之后插入/更新这三张表会报 "no such column: source"，且不会重试（数据库已经
                        // 盖章成版本 7）——PR #38 Codex 第五轮审核 P1。ALTER TABLE ADD COLUMN 对已经
                        // 有这列的库（oldVersion==6 走过旧 case 6，或者本次升级本身跑了不止一次）
                        // 会抛 DbException，被下面的 catch 吞掉，不影响其余设备。
                        // 每张表单独 try：原来三条共用一个 try，第一条失败（如该表还没建）后两条就不执行了。
                        // 条件放宽到 < 8：已升到 7 但当时漏加列的设备，借这次升到 8 补上；已有该列的只抛异常被吞掉。
                        if (oldVersion < 8) {
                            for (String table : new String[]{"com_sansen_yeweiyi_db_OilInOut",
                                    "com_sansen_yeweiyi_db_OutStock", "detail_inout_record"}) {
                                try {
                                    dbUtils.execNonQuery("ALTER TABLE " + table + " ADD COLUMN source TEXT");
                                } catch (DbException e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败: " + table, e);
                                }
                            }
                        }

                        // 交班起止时刻 epoch 列（issue #49）。与上面 source 列同理放在 switch 外按版本判断；
                        // 每列单独 try，已有该列（或表尚未创建）时抛的异常不影响另一列。
                        if (oldVersion < 8) {
                            for (String column : new String[]{"begintimems", "endtimems"}) {
                                try {
                                    dbUtils.execNonQuery("ALTER TABLE commitwork ADD COLUMN " + column + " INTEGER");
                                } catch (DbException e) {
                                    Log.e(TAG, "onUpgrade 数据库操作失败: " + column, e);
                                }
                            }
                        }

//                        if (oldVersion == 1 && newVersion == 2) {
//                            try {
//                                //为数据库表ShoppingCar添加shopId字段
//                                dbUtils.createTableIfNotExist(SmsStencil.class);
//                                dbUtils.createTableIfNotExist(OilCanMapper.class);
//                                dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilCanMapper add isShow boolean");
//
//                            } catch (Exception e) {
//                                //e.printStackTrace();
//                            }
//                        }
//
//                        if (oldVersion == 1 && newVersion == 3) {
//                            try {
//                                //为数据库表ShoppingCar添加shopId字段
//                                dbUtils.createTableIfNotExist(SmsStencil.class);
//                                dbUtils.createTableIfNotExist(OilCanMapper.class);
//                                dbUtils.createTableIfNotExist(TempRecord.class);
//                                dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilCanMapper add isShow boolean");
//
//                            } catch (Exception e) {
//                                //e.printStackTrace();
//                            }
//                        }
//                        if (oldVersion == 2 && newVersion == 3) {
//                            try {
//                                //为数据库表ShoppingCar添加shopId字段
//                                dbUtils.createTableIfNotExist(TempRecord.class);
//                            } catch (Exception e) {
//                                //e.printStackTrace();
//                            }
//                        }
//                        if (oldVersion == 1 && newVersion == 3) {
//                            try {
//                                dbUtils.createTableIfNotExist(SmsStencil.class);
//                                dbUtils.createTableIfNotExist(OilCanMapper.class);
//                                dbUtils.createTableIfNotExist(TempRecord.class);
//                                dbUtils.createTableIfNotExist(DetailInoutRecord.class);
//                                dbUtils.execNonQuery("alter table com_sansen_yeweiyi_db_OilCanMapper add isShow boolean");
//                            } catch (Exception e) {
//                                //e.printStackTrace();
//                            }
//                        }
//                        if (oldVersion == 2 && newVersion == 4) {
//                            try {
//
//                                dbUtils.createTableIfNotExist(TempRecord.class);
//                                dbUtils.createTableIfNotExist(DetailInoutRecord.class);
//                            } catch (DbException e) {
//                                //e.printStackTrace();
//                            }
//
//                        }
//                        if (oldVersion == 3 && newVersion == 4) {
//                            try {
//                                dbUtils.createTableIfNotExist(DetailInoutRecord.class);
//                            } catch (DbException e) {
//                                //e.printStackTrace();
//                            }
//
//                        }

                    }
                });
        db.configAllowTransaction(true);// 开启事务
        db.configDebug(false);// debug，输出sql语句
    }

    public void initTalbeData() {
        // 默认 用户 admin 密码 admin
        createAdminUser();
        // 默认 9个探针 9个油罐 9个罐表
        createOilCan(16);
        createFyf(16);
        OilExploration oilExploration;
        for (int i = 0; i < 8; i++) {
            SaveObject(new OilExploration());
        }
    }

    private void createFyf(int num) {
        for (int i = 0; i < num; i++) {
            SaveObject(new FYF());
        }
    }


    /**
     * Create tables
     */
    private void createTable() {
        try {
            db.createTableIfNotExist(LevelmeterInfo.class);
            db.createTableIfNotExist(Alarm.class);
            db.createTableIfNotExist(User.class);
            db.createTableIfNotExist(CommitWork.class);
            db.createTableIfNotExist(OilCanSet.class);
            db.createTableIfNotExist(OilInOut.class);
            db.createTableIfNotExist(OilSaveInfo.class);
            db.createTableIfNotExist(ProbeToOil.class);
            db.createTableIfNotExist(Sideleakage.class);
            db.createTableIfNotExist(OilCanTable.class);
            db.createTableIfNotExist(Authorization.class);
            db.createTableIfNotExist(OilCanMapper.class);
            db.createTableIfNotExist(OilExploration.class);
            db.createTableIfNotExist(FYF.class);
            db.createTableIfNotExist(OutStock.class);
            db.createTableIfNotExist(StockSave.class);
            db.createTableIfNotExist(FyfRecord.class);
            db.createTableIfNotExist(SmsStencil.class);
            db.createTableIfNotExist(DetailInoutRecord.class);
            db.createTableIfNotExist(OilTest.class);

        } catch (DbException e) {
            Log.e(TAG, "createTable 数据库操作失败", e);
        }
    }

    private void createAuthorization(String admin) {
        Authorization authorization = new Authorization();
        authorization.setAboutUser(1);
        authorization.setDataManager(1);
        authorization.setOilcanSet(1);
        authorization.setOilcanTable(1);
        authorization.setSystemSet(1);
        authorization.setProbeSet(1);
        authorization.setTableCreate(1);
        authorization.setDelteInOutOil(1);
        authorization.setOilTest(1);
        authorization.setUpdateCommitInfo(1);
        authorization.setUserManager(1);
        authorization.setUserId(admin);
        authorization.setOilExploration(1);
        authorization.setFyfManager(1);
        try {
            db.save(authorization);
        } catch (DbException e) {
            Log.e(TAG, "createAuthorization 数据库操作失败", e);
        }
    }

    private void createOilCan(int count) {

        for (int i = 0; i < count; i++) {
            ProbeToOil probeToOil = new ProbeToOil();
            if (i == 0) {
                probeToOil.setEnable(1);
            }
            probeToOil.setGmt_create(DateUtils.getYYYMMddHHmmss());
            SaveObject(probeToOil);
            OilCanSet oilCanSet = new OilCanSet();
            oilCanSet.setGmt_create(DateUtils.getYYYMMddHHmmss());
            SaveObject(oilCanSet);
            OilCanTable oilCanTable = new OilCanTable();
            oilCanTable.setGmt_create(DateUtils.getYYYMMddHHmmss());
            oilCanTable.ArrayToString();
            SaveObject(oilCanTable);
            OilCanMapper oilCanMapper = new OilCanMapper();
            oilCanMapper.setOilCansetId(i + 1);
            oilCanMapper.setOilCanTableId(i + 1);
            oilCanMapper.setProbeId(i + 1);
            SaveObject(oilCanMapper);
        }
    }

    private void createAdminUser() {
        User user = new User();
        user.setId("admin");
        user.setPsw("admin");

        SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(),SharePrefUtil.SYSTEMPWD,"123456");
        SaveObject(user);
        // 创建用户权限
        createAuthorization("admin");
    }

    /**
     * 创建表
     */
    private void SaveObject(Object object) {
        try {
            db.save(object);
        } catch (DbException e) {
            Log.e(TAG, "SaveObject 数据库操作失败", e);
        }
    }


}
