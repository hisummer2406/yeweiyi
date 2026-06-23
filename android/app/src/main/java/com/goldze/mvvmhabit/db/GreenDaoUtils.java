package com.goldze.mvvmhabit.db;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.yourpackage.dao.DaoMaster;
import com.yourpackage.dao.DaoSession;

import org.greenrobot.greendao.AbstractDao;
import org.greenrobot.greendao.query.QueryBuilder;
import org.greenrobot.greendao.query.WhereCondition;

import java.util.List;

/**
 * GreenDAO数据库操作工具类
 * 提供单例模式管理数据库连接，封装通用CRUD操作和查询构建器
 */
public class GreenDaoUtils {

    @SuppressLint("StaticFieldLeak")
    private static volatile GreenDaoUtils sInstance;
    private DaoSession mDaoSession;
    private DaoMaster.DevOpenHelper mHelper;
    private static Context mContext;

    // 数据库名称，可根据需要修改
    private static final String DB_NAME = "my_database.db";

    private GreenDaoUtils() {
        initDatabase();
    }

    /**
     * 获取单例实例，需先调用 init 方法初始化上下文
     */
    public static GreenDaoUtils getInstance() {
        if (sInstance == null) {
            synchronized (GreenDaoUtils.class) {
                if (sInstance == null) {
                    if (mContext == null) {
                        throw new IllegalStateException("必须先调用 GreenDaoUtils.init() 进行初始化");
                    }
                    sInstance = new GreenDaoUtils();
                }
            }
        }
        return sInstance;
    }

    /**
     * 初始化方法，在 Application 中调用
     */
    public static void init(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 初始化数据库连接和 DaoSession
     */
    private void initDatabase() {
        // 创建数据库帮助类
        mHelper = new DaoMaster.DevOpenHelper(mContext, DB_NAME, null);
        // 获取可写数据库
        SQLiteDatabase db = mHelper.getWritableDatabase();
        // 创建 DaoMaster 和 DaoSession
        DaoMaster daoMaster = new DaoMaster(db);
        mDaoSession = daoMaster.newSession();
    }

    /**
     * 获取 DaoSession，用于直接操作 DAO
     */
    public DaoSession getDaoSession() {
        if (mDaoSession == null) {
            initDatabase();
        }
        return mDaoSession;
    }

    /**
     * 设置调试模式
     */
    public void setDebug(boolean flag) {
        QueryBuilder.LOG_SQL = flag;
        QueryBuilder.LOG_VALUES = flag;
    }

    /**
     * 关闭数据库连接
     */
    public synchronized void closeDatabase() {
        if (mHelper != null) {
            mHelper.close();
            mHelper = null;
        }
        if (mDaoSession != null) {
            mDaoSession.clear();
            mDaoSession = null;
        }
        sInstance = null;
    }

    // ==================== 通用 CRUD 操作 ====================

    /**
     * 插入数据
     */
    public <T> long insert(T entity) {
        return getDao(entity).insert(entity);
    }

    /**
     * 插入或替换数据
     */
    public <T> long insertOrReplace(T entity) {
        return getDao(entity).insertOrReplace(entity);
    }

    /**
     * 批量插入数据
     */
    public <T> void insertMultiple(List<T> entities) {
        if (entities == null || entities.isEmpty()) return;
        AbstractDao<T, ?> dao = getDao(entities.get(0));
        dao.insertInTx(entities);
    }

    /**
     * 删除数据
     */
    public <T> void delete(T entity) {
        getDao(entity).delete(entity);
    }

    /**
     * 根据主键删除数据
     */
    public <T> void deleteByKey(Class<T> entityClass, Object key) {
        AbstractDao<T, Object> dao = (AbstractDao<T, Object>) getDao(entityClass);
        dao.deleteByKey(key);
    }

    /**
     * 删除所有数据
     */
    public <T> void deleteAll(Class<T> entityClass) {
        getDao(entityClass).deleteAll();
    }

    /**
     * 更新数据
     */
    public <T> void update(T entity) {
        getDao(entity).update(entity);
    }

    /**
     * 查询所有数据
     */
    public <T> List<T> loadAll(Class<T> entityClass) {
        return getDao(entityClass).loadAll();
    }

    /**
     * 根据主键查询数据
     */
    public <T, K> T load(Class<T> entityClass, K key) {
        AbstractDao<T, Object> dao = (AbstractDao<T, Object>) getDao(entityClass);
        return dao.load(key);
    }

    // ==================== 查询构建器辅助方法 ====================

    /**
     * 获取查询构建器
     */
    public <T> QueryBuilder<T> getQueryBuilder(Class<T> entityClass) {
        return getDao(entityClass).queryBuilder();
    }

    /**
     * 构建并执行基础查询
     */
    public <T> List<T> query(Class<T> entityClass, WhereCondition cond, WhereCondition... condMore) {
        QueryBuilder<T> qb = getQueryBuilder(entityClass);
        if (cond != null) {
            qb.where(cond, condMore);
        }
        return qb.list();
    }

    /**
     * 带排序的查询
     */
    public <T> List<T> queryWithOrder(Class<T> entityClass,
                                      org.greenrobot.greendao.Property orderProperty,
                                      WhereCondition cond,
                                      WhereCondition... condMore) {
        QueryBuilder<T> qb = getQueryBuilder(entityClass);
        if (cond != null) {
            qb.where(cond, condMore);
        }
        if (orderProperty != null) {
            qb.orderAsc(orderProperty);
        }
        return qb.list();
    }

    /**
     * 分页查询
     */
    public <T> List<T> queryWithPagination(Class<T> entityClass, int offset, int limit) {
        return getQueryBuilder(entityClass)
                .offset(offset)
                .limit(limit)
                .list();
    }

    /**
     * 执行自定义 SQL 查询
     */
    public <T> List<T> queryRaw(Class<T> entityClass, String where, String... selectionArg) {
        return getDao(entityClass).queryRaw(where, selectionArg);
    }

    // ==================== 事务操作 ====================

    /**
     * 运行数据库事务
     */
    public void runInTx(Runnable runnable) {
        mDaoSession.runInTx(runnable);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据实体获取 DAO
     */
    @SuppressWarnings("unchecked")
    private <T> AbstractDao<T, ?> getDao(T entity) {
        return (AbstractDao<T, ?>) mDaoSession.getDao(entity.getClass());
    }

    /**
     * 根据实体类获取 DAO
     */
    @SuppressWarnings("unchecked")
    private <T> AbstractDao<T, ?> getDao(Class<T> entityClass) {
        return (AbstractDao<T, ?>) mDaoSession.getDao(entityClass);
    }
}