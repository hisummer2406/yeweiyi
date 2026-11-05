package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.AlarmState;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilExploration;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.thingsboard.TelemetrySnapshotBuilder;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class OilcanDataManager {
    private static OilcanDataManager INSTANCE;
    private Context mContext;
    private MyApplication mApplication;

    // 每个探针（油罐）的配置信息。volatile + refresh()整体建新数组再切换引用（而不是
    // 在原数组上clear()+重填），保证探针轮询线程（SerialPortManager.RequestThread）
    // 任何时候读到的要么是刷新前的完整旧数据、要么是刷新后的完整新数据，不会读到
    // clear()之后、重填之前那个瞬间清空的中间状态（issue #28 代码审查发现）。
    private volatile SparseArray<OilCan> mOilCanData;// 真实的数据

    private volatile SparseArray<OilCan> testOilCanData;// 假的数据

    private List<OilExploration> oilExplor; //油气探测报警值

    private DBDataManager db;
    private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();

    private SaveOilManager saveOilManager;

    //在上传油管（探针）数据时，将自己的设备名称同时上传，用于在线版对照
    private String deviceName;

    public OilcanDataManager(Context context) {
        mContext = context;
        mApplication = MyApplication.getInstance();
        db = mApplication.getmDBManager();
        saveOilManager = mApplication.getmSaveOilManager();
        initData();
    }

    /**
     * 查找探针表，建立指定的Map集合
     */
    private void initData() {
        //Log.i("数据", "初始化中");
        getOilExploration();
        getData();
        getTestData();
        setProbeData(mOilCanData, "nomar");
        setProbeData(testOilCanData, "test");
//		Log.i("在什么线程下执行啊+++",Thread.currentThread().getName());
//
//		for(int i = 0, nsize = testOilCanData.size(); i < nsize; i++) {
//			int key = testOilCanData.keyAt(i);
//			Log.i("主键",""+i);
//
//			OilCan oilCan = testOilCanData.get(key);
//			Log.i("数据主键",oilCan+"");
//			if (oilCan!=null) {
//				OilCanMapper mapper = (OilCanMapper) db.findObjectByID(
//						OilCanMapper.class, oilCan.getOilCanPos());
//				if (!mapper.isshow()) {
//					Log.i("要删除的主键Id",mapper.getProbeId()+"");
//					testOilCanData.remove(key);
//				}
//			}else{
//				testOilCanData.remove(key);
//			}
//		}
    }

    public List<OilExploration> getOilExploration() {
        if (oilExplor == null) {
            oilExplor = oilTankRepository.findAllOilExploration();
        }
        return oilExplor;
    }

    public synchronized void updateOilExPloraton() {
        oilExplor = oilTankRepository.findAllOilExploration();
    }

    public void refresh() {
        Log.i("数据", "刷新");
        // 建两份新数组填完整再整体替换字段，不在旧数组上 clear()+重填——后者会让
        // 其它线程在重填过程中读到一个瞬间清空的数组（见字段声明处的说明）。
        SparseArray<OilCan> newOilCanData = new SparseArray<>();
        SparseArray<OilCan> newTestOilCanData = new SparseArray<>();
        setProbeData(newOilCanData, "nomar");
        setProbeData(newTestOilCanData, "test");
        mOilCanData = newOilCanData;
        testOilCanData = newTestOilCanData;
        Runnable listener = configChangedListener;
        if (listener != null) {
            listener.run();
        }
    }

    /** 罐配置重新加载（{@link #refresh()}）后回调，ThingsBoardManager 用它重发客户端属性。 */
    private volatile Runnable configChangedListener;

    public void setConfigChangedListener(Runnable listener) {
        this.configChangedListener = listener;
    }

    /**
     * 从数据库中读取数据
     */
    private void setProbeData(SparseArray<OilCan> oils, String type) {
        OilCan oilCan;
        List<OilCanSet> probeToOils = oilTankRepository.findAllOilCanSet();
        // ①查询探针对应表，建立Map中的数据
        if (probeToOils != null) {

            // 油罐的id
            List<OilCanSet> oilSets = oilTankRepository.findAllOilCanSet();
            List<OilCanTable> oilTables = oilTankRepository.findAllOilCanTable();

            for (int i = 0; i < probeToOils.size(); i++) {
                // i指的是探针对应表的id
                if (probeToOils.get(i).getEnable() == 1) {
                    int oilcanPos = probeToOils.get(i).getPk_oilCanId();
                    oilCan = new OilCan();
                    oilCan.setOilCanPos(oilcanPos);
                    setOilCan(oilCan, oilSets);
                    setOilcanTable(oilCan, oilTables);
                    oils.put(probeToOils.get(i).getPk_oilCanId(), oilCan);

                    if (!MyApplication.alarmStates.containsKey(new Integer(
                            oilcanPos))) {
                        AlarmState alarmState = new AlarmState();
                        alarmState.setOilCanPos(oilcanPos);
                        MyApplication.alarmStates.put(oilcanPos, alarmState);
                    }

                }

            }
        }
    }

    private void setOilcanTable(OilCan oilCan, List<OilCanTable> tables) {
        int[] heights;
        double[] volumes;

        OilCanTable oilCanTable = null;
        oilCanTable = tables.get(oilCan.getOilCanPos() - 1);

        oilCanTable.StringToArray();
        heights = oilCanTable.getHeight();
        volumes = oilCanTable.getVolum();
        oilCan.setHightArray(heights);
        oilCan.setLitreArray(volumes);


        heights = null;
        volumes = null;
    }

    private void setTestOilcanTable(OilCan oilCan, List<OilCanTable> tables) {
        int[] heights;
        double[] volumes;

        OilCanTable oilCanTable = null;
        OilCanMapper mapper = (OilCanMapper) db.findObjectByID(
                OilCanMapper.class, oilCan.getOilCanPos());
        oilCanTable = tables.get(mapper.getOilCanTableId() - 1);

        String[] testHighte = tables.get(mapper.getOilCanTableId() - 1).getHeightstrarray().split(" ");
        String[] highe = tables.get(mapper.getOilCansetId() - 1).getHeightstrarray().split(" ");
        String[] testVoluem = tables.get(mapper.getOilCanTableId() - 1).getVolumstrarray().split(" ");
        String[] voluem = tables.get(mapper.getOilCansetId() - 1).getVolumstrarray().split(" ");
        Double scaleHighe = (Double.valueOf(highe[highe.length - 1]) + 10) / (Double.valueOf(testHighte[testHighte.length - 1]) + 10);
        Double scaleVoluem = (Double.valueOf(voluem[highe.length - 1]) + 10) / (Double.valueOf(testVoluem[testHighte.length - 1]) + 10);


        oilCanTable.StringToArray();
        heights = oilCanTable.getHeight();
        volumes = oilCanTable.getVolum();
        oilCan.setScaleHight(scaleHighe);
        oilCan.setScaleVolume(scaleVoluem);
        oilCan.setHightArray(heights);
        oilCan.setLitreArray(volumes);
        heights = null;
        volumes = null;
    }

    private void setOilCan(OilCan oilCan, List<OilCanSet> oilSets) {

        // 油罐设置表 id即为油罐的id主键唯一


        OilCanSet oilCanSet = oilSets.get(oilCan.getOilCanPos() - 1);
        oilCan.setProbeAdr(oilCanSet.getPk_tanZhenId() + "");
        // 设置油罐的高度
        oilCan.setMaxHeight(oilCanSet.getHeight());
        // 设置油罐最大的体积
        oilCan.setMaxVolume(oilCanSet.getMaxVolume());
        // 设置高油报警
        oilCan.setHighLevelAlarm(oilCanSet.getHeightOilAlarm());
        // 设置高高油位报警
        oilCan.setHhighLevelAlarm(oilCanSet.getHheightOilAlarm());
        // 设置低油报警
        oilCan.setLowLevelAlarm(oilCanSet.getLowOilAlarm());
        // 设置低低油位报警
        oilCan.setLlowLevelAlarm(oilCanSet.getLlowOilAlarm());
        // 设置高水报警
        oilCan.setHighWaterLevelAlarm(oilCanSet.getHeightWaterAlarm());
        // 设置高温报警
        oilCan.setHighTempAlarm(oilCanSet.getHighTempAlarm());
        // 低温报警
        oilCan.setLowTempAlarm(oilCanSet.getLowTempAlarm());
        // 油位补偿
        oilCan.setLevelCorrection(oilCanSet.getOilCorrection());
//油面高度
        oilCan.setOilHeight(oilCanSet.getOilHeight());
        //油罐类型
        oilCan.setGunType(oilCanSet.getGunType());
        oilCan.setJieZhi(Const.getOilType(oilCanSet.getOilType()));
        oilCan.setEnable(oilCanSet.getEnable());
        // 水位补偿
        oilCan.setWaterLevelCorrection(oilCanSet.getWaterCorrection());
        // 温度补偿
        oilCan.setTempCorrection(oilCanSet.getTempCorrection());
        // 油品密度
        oilCan.setDensity(oilCanSet.getDensity());

        // 密度变化值
        oilCan.setDensityVar(oilCanSet.getDensVar());
        // 允许报警位
        oilCan.setAlarmByte(oilCanSet.getAlarmByte());
        // 颜色
        oilCan.setOilColor(oilCanSet.getOilColor());
        // 油品类型
        oilCan.setOilType(oilCanSet.getOilType());
    }

    private void setTestOilCan(OilCan oilCan, List<OilCanSet> oilSets) {

        // 油罐设置表 id即为油罐的id主键唯一
//		System.out.println("结果不对"+oilCan.getOilCanPos());
        OilCanMapper mapper = (OilCanMapper) db.findObjectByID(
                OilCanMapper.class, oilCan.getOilCanPos());
//		System.out.println("Mapper " + mapper);
        OilCanSet oilCanSet = oilSets.get(mapper.getOilCanTableId() - 1);

        // 设置油罐的高度
        oilCan.setMaxHeight(oilCanSet.getHeight());
        // 设置油罐最大的体积
        oilCan.setMaxVolume(oilCanSet.getMaxVolume());
        // 设置高油报警
        oilCan.setHighLevelAlarm(oilCanSet.getHeightOilAlarm());
        // 设置高高油位报警
        oilCan.setHhighLevelAlarm(oilCanSet.getHheightOilAlarm());
        // 设置低油报警
        oilCan.setLowLevelAlarm(oilCanSet.getLowOilAlarm());
        // 设置低低油位报警
        oilCan.setLlowLevelAlarm(oilCanSet.getLlowOilAlarm());
        // 设置高水报警
        oilCan.setHighWaterLevelAlarm(oilCanSet.getHeightWaterAlarm());
        // 设置高温报警
        oilCan.setHighTempAlarm(oilCanSet.getHighTempAlarm());
        // 低温报警
        oilCan.setLowTempAlarm(oilCanSet.getLowTempAlarm());
        // 油位补偿
        oilCan.setLevelCorrection(oilCanSet.getOilCorrection());
        // 水位补偿
        oilCan.setWaterLevelCorrection(oilCanSet.getWaterCorrection());
        // 温度补偿
        oilCan.setTempCorrection(oilCanSet.getTempCorrection());
        // 油品密度
        oilCan.setDensity(oilCanSet.getDensity());
//油面高度
        oilCan.setOilHeight(oilCanSet.getOilHeight());
        // 密度变化值
        oilCan.setDensityVar(oilCanSet.getDensVar());
        // 允许报警位
        oilCan.setAlarmByte(oilCanSet.getAlarmByte());
        // 颜色
        oilCan.setOilColor(oilCanSet.getOilColor());
        // 油品类型
        oilCan.setOilType(oilCanSet.getOilType());

    }

    /**
     * 得到油罐对象
     *
     * @return OilCan的Map集合
     */
    public SparseArray<OilCan> getData() {
        // 如果为空，就新建HashMap
        if (mOilCanData == null) {
            mOilCanData = new SparseArray<OilCan>();
        }

        return mOilCanData;
    }


    /**
     * 得到油罐对象
     *
     * @return OilCan的Map集合
     */
    public SparseArray<OilCan> getTestData() {
        // 如果为空，就新建HashMap
        if (testOilCanData == null) {
            testOilCanData = new SparseArray<OilCan>();
        }
        return testOilCanData;
    }

    /**
     * 更新全部Map
     *
     * @param oilCanData
     */
    public void setData(SparseArray<OilCan> oilCanData) {
        mOilCanData = oilCanData;
    }

    /**
     * 更新全部Map
     *
     * @param oilCanData
     */
    public void setTestData(SparseArray<OilCan> oilCanData) {
        testOilCanData = oilCanData;

    }

    /**
     * 获取OilDataManger的单例
     *
     * @param context
     * @return OilcanDataManager
     */
    public static OilcanDataManager getInstance(Context context) {

        if (INSTANCE == null) {

            INSTANCE = new OilcanDataManager(context);
        }
        return INSTANCE;
    }

    /**
     * 刷新数据Map值
     *
     * @param oilCan
     */
    public void refreshData(OilCan oilCan) {

        if (mOilCanData.get(oilCan.getOilCanPos()) == null) {

            mOilCanData.put(oilCan.getOilCanPos(), oilCan);

        }
    }

    /**
     * 刷新数据Map值
     *
     * @param oilCan
     */
    public void refreshTestData(OilCan oilCan) {

        if (testOilCanData.get(oilCan.getOilCanPos()) == null) {
            testOilCanData.put(oilCan.getOilCanPos(), oilCan);
        }

    }

    /**
     * 根据key查找和Map中的数据
     *
     * @param key 油罐id
     * @return OilCan
     */
    public OilCan getData(int key) {

        return mOilCanData.get(key);

    }

    /**
     * 根据key查找和Map中的数据
     *
     * @param key2 油罐id
     * @return OilCan
     */
    public OilCan getTestData(int key2) {
//		Log.i("在什么线程下执行啊+++++)))))",Thread.currentThread().getName());
        return testOilCanData.get(key2);
    }


    /** 按协议帧里的地址字节查油罐（中成/LD 应答用），地址字节 = 十进制探针ID。 */
    public OilCan getDataByProbeAddress(int addressByte) {
        // 先取局部引用：refresh() 会整体替换 mOilCanData，循环中途不能换到另一个数组上。
        SparseArray<OilCan> data = mOilCanData;
        for (int i = 0; i < data.size(); i++) {
            OilCan oilCan = data.valueAt(i);
            if (oilCan != null && oilCan.getProbeAddressByte() == addressByte) {
                return oilCan;
            }
        }
        return null;
    }

    public List<NetOilCan> getNetOilcans() {
        int key = 0;
        List<NetOilCan> netOilcans = new ArrayList<>();

        if (StringUtils.isNotEmpty(SharePreManager.getIpBean().getAddr())) {
            deviceName = SharePreManager.getIpBean().getAddr();
        } else {
            deviceName = SharePrefUtil.getString(mContext, SharePrefUtil.DEVICESECRET, "");
        }
        for (int i = 0; i < mOilCanData.size(); i++) {
            key = mOilCanData.keyAt(i);
            OilCan oilCan = mOilCanData.get(key);
            NetOilCan netOilcan = new NetOilCan();
            netOilcan.setId(oilCan.getOilCanPos());
            netOilcan.setOilLevel(oilCan.getOilLevel());
            netOilcan.setWaterLevel(oilCan.getWaterLevel());
            netOilcan.setOiltype(Const.getOilType(oilCan.getOilType()));
            netOilcan.setOilVolume(oilCan.getOilVolume());
            netOilcan.setWaterVolume(oilCan.getWaterVolume());
            netOilcan.setTemp(oilCan.getTemper());
            // 遥测 oilRatioPct：跟本机罐体图同一含义（液位÷罐高），保留一位小数。
            // waterRatioPct 暂不上报：真实模式下 waterRatio 从未被计算过（只有演示模式赋值），恒为 0。
            netOilcan.setOilRatioPct(TelemetrySnapshotBuilder.heightRatioPct(oilCan.getOilLevel(), oilCan.getMaxHeight()));
            // surPlus 是剩余可加油体积（maxVolume - oilLitre - waterLitre，单位 L）；
            // canAddOilWeight 是 surPlus * density，单位 kg，不是体积——之前这里把
            // canAddOilWeight 塞进 nullVolume，导致 ThingsBoardManager 按 nullVolumeL 发出去
            // 的其实是密度不为 1 时被压缩/放大过的重量，不是体积（PR #38 Codex 审核）。
            netOilcan.setNullVolume(oilCan.getSurPlus());
            netOilcan.setAddress(oilCan.getProbeAdr());
            // NORMAL/ALARM/FAULT，跟云端报警事件同一个来源（CloudAlarmReporter 记录的 ACTIVE 集合）
            netOilcan.setAlarmState(CloudAlarmReporter.getInstance().alarmState(oilCan.getOilCanPos()));
            netOilcan.setProbeAddress(oilCan.getProbeAdr());
            netOilcan.setOilWeight(oilCan.getOilWeight());
            netOilcan.setOilColor(oilCan.getOilColor());
            netOilcan.setConnect(oilCan.getErrorTimes() == 0);

            //每次发送一共有多少个探针
            netOilcan.setFatherAllProbe(mOilCanData.size());
            //Log.i("NETOIlCan", "getNetOilcans: "+netOilcan.isConnect());
            netOilcan.setDeviceName(deviceName);


            netOilcans.add(netOilcan);
        }
        return netOilcans;
    }
}
