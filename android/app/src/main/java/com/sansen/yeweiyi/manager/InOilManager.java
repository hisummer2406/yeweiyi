package com.sansen.yeweiyi.manager;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.listener.UpdateInOilListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;
import com.sansen.yeweiyi.utils.Singleton;

import java.util.HashMap;
import java.util.Map;

public class InOilManager {
    /**
     * 进油管理器类维护一个静态Map集合、里面存储多个进油管理器对象
     */
    private static Map<Integer, InOilManager> maps = new HashMap<Integer, InOilManager>();
    private static InOilManager inOilManager;
    DetailInoutRecord detailInoutRecord = new DetailInoutRecord();

    /**
     * 如果上一条记录跟当前记录不一样、那么开始高度就等于上一条高度值
     */
    private double[] beginIn = new double[16];
    /**
     * 累计器、用于判断是否处于进油状态。0表示非进油状态 1表示进油状态
     */
    private int inCount[] = new int[16];
    /**
     * 当前对象的罐号
     */
    private int id;
    /**
     * 当处于进油状态（count ！=0） ,但当前记录等于上一条记录是，开始计时
     */
    private long[] beginTime = new long[16];
    /**
     * 临时变量，当前的时间
     */
    private double[] curHeight = new double[16];

    /**
     * 上一条记录的高度
     */
    private double[] lastHeight = new double[16];

    /**
     * 进油管理器的外部监听器
     */
    private UpdateInOilListener inOil;
    private boolean isEnable = true;
    private static String TAG = "InOilManager";
    private static long RecordTime = 0;
    private static boolean[] isRecord = new boolean[16];

    /**
     * 外部调用方法，需要实时的传入 油罐号和当前高度，通过监听器获取
     *
     * @param updateInOil
     * @param pos
     * @param curheight
     */
    private static Map<Integer, Boolean>  current=new HashMap<>();

    public static void setOil(UpdateInOilListener updateInOil, int pos,
                              double curheight) {
        if (Singleton.getInstance().IsCreatIn||Singleton.getInstance().IsCreateOut){
            if (maps.containsKey(pos)) {
                if (!isRecord[pos] || System.currentTimeMillis() - RecordTime > 10000) {
                    if (!current.containsKey(pos)||!current.get(pos)) {
                        current.put(pos,true);
                        RecordTime = System.currentTimeMillis();
                        inOilManager = maps.get(pos);
                        if (inOilManager.getEnable()) {
                            //  Log.i(TAG, inOilManager.getEnable() + "   " + pos);
                            inOilManager.setData(curheight);
                            inOilManager.beginInOutDection();
                        }
                    }
                    else {
                        boolean allTrue = true;
                        for (Boolean value : current.values()) {
                            if (value == null || !value) {
                                allTrue = false;
                                break;
                            }
                        }
                        if (allTrue) {
                            current.clear();
                        }
                    }
                }
            } else {
                inOilManager = new InOilManager(pos, updateInOil);
                inOilManager.setData(curheight);
                inOilManager.beginInOutDection();
                //    Log.e(TAG,"不包含添加id :"+pos);
                maps.put(pos, inOilManager);
            }
        }

    }

    public InOilManager(int pos, UpdateInOilListener inOIl) {
        this.id = pos;
        this.inOil = inOIl;
    }

    public void enable(boolean isEnalbe) {
        this.isEnable = isEnalbe;
        if (this.isEnable) {
            this.lastHeight[id] = 0;
            this.inCount[id] = 0;
            this.beginTime[id] = 0;
            this.beginIn[id] = 0;
        }
    }

    public static void setEnable(int oilcanId, boolean isEnable) {
        if (maps.containsKey(oilcanId)) {
            inOilManager = maps.get(oilcanId);
            inOilManager.enable(isEnable);
        }
    }

    /**
     * 传入数据
     */
    public void setData(double curheight) {
        this.curHeight[id] = curheight;
    }

    private long[] startTime = new long[16];
    ;

    public void beginInOutDection() {
        if (curHeight[id] != 0) {
            if (lastHeight[id] == 0) {
                lastHeight[id] = curHeight[id];
            } else {
                if (curHeight[id] != lastHeight[id]) {
                    isRecord[id] = true;
                    if (inCount[id] == 0) {
                        startTime[id] = System.currentTimeMillis();
                        beginIn[id] = lastHeight[id];
                        detailInoutRecord.setOilcanId(id);
                        detailInoutRecord.setBeginHeith(beginIn[id]);
                        detailInoutRecord.setBeginVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilVolume());
                        detailInoutRecord.setBeginWater(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getWaterLevel());
                        detailInoutRecord.setBeginTcVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getSurPlus());
                        detailInoutRecord.setBeginTemp(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getTemper());
                        detailInoutRecord.setBeginTime(DateUtils.getYYYMMddHHmmss());
                        detailInoutRecord.setBeginOilWeight(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilWeight());
                        detailInoutRecord.setBeginWaterVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getWaterVolume());
                    }

                    if (beginTime[id] != 0) {
                        beginTime[id] = System.currentTimeMillis() + SharePreManager.getInOilTime() / 2;
                    }

                    lastHeight[id] = curHeight[id];
                    inCount[id]++;
                } else {
                    if (inCount[id] > 0) {
                        if (beginTime[id] == 0) {
                            beginTime[id] = System.currentTimeMillis();
                        } else {
                            if (curHeight[id] - beginIn[id] > 0) {
                                if ((System.currentTimeMillis() - startTime[id]) / 1000 < Singleton.getInstance().InTime || curHeight[id] - beginIn[id] < Singleton.getInstance().InHight) {
                                    beginTime[id] = 0;
                                    beginIn[id] = 0;
                                    inCount[id] = 0;
                                    lastHeight[id] = 0;
                                    isRecord[id] = false;
                                    return;
                                }
                            } else if (curHeight[id] - beginIn[id] < 0) {

                                if ((System.currentTimeMillis() - startTime[id]) / 1000 < Singleton.getInstance().OutTime || beginIn[id] - curHeight[id] < Singleton.getInstance().OutHight) {
                                    beginTime[id] = 0;
                                    beginIn[id] = 0;
                                    inCount[id] = 0;
                                    lastHeight[id] = 0;
                                    isRecord[id] = false;
                                    return;
                                }
                            }

                            detailInoutRecord.setOilType(Const.getOilType(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilType()));
                            detailInoutRecord.setEndHeight(curHeight[id]);
                            detailInoutRecord.setEndVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilVolume());
                            detailInoutRecord.setEndWater(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getWaterLevel());
                            detailInoutRecord.setEndWaterVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getWaterVolume());
                            detailInoutRecord.setEndTcVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getSurPlus());
                            detailInoutRecord.setEndOilWeight(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilWeight());
                            detailInoutRecord.setEndTemp(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getTemper());
                            detailInoutRecord.setEndTime(DateUtils.getYYYMMddHHmmss());
                            detailInoutRecord.setSaleVolume(DoubleUtil.sub(detailInoutRecord.getEndVolume(), detailInoutRecord.getBeginVolume()));

                            inOil.success(beginIn[id], curHeight[id], DataUtils.getLitre(
                                    beginIn[id], OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getHightArray(),
                                    OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getLitreArray()), OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getOilVolume(),OilcanDataManager.getInstance(MyApplication.getInstance()).getData(id).getDensity(), id, detailInoutRecord);
                            beginTime[id] = 0;
                            beginIn[id] = 0;
                            inCount[id] = 0;
                            lastHeight[id] = 0;

                        }


                    }
                }

            }
        }
    }

    public boolean getEnable() {
        return this.isEnable;
    }
}
