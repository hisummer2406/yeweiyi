package com.sansen.yeweiyi.utils;

import android.util.Log;

import com.sansen.yeweiyi.application.MyApplication;

public class Singleton {
    public boolean isTestStatus=false;
    public int OutHight;  //出库高度阈值
    public int OutTime;    //出库时间阈值
    public int OutVolume;//出库容量阈值

    public int InHight;//入库高度阈值
    public int InTime;//入库时间阈值
    public int InVolume;//入库容量阈值

    public boolean IsBaoJing;//报警记录导出
    public boolean IsChuKu;//出库记录导出
    public boolean IsRuKu;//入库记录导出
    public boolean IsJiaoBan;//交班记录导出
    public boolean IsKuCun;//库存记录导出
    public long StockTime = 0;
    public double[] TanZhenLang = new double[256];// 下标 = 探针地址字节（1-255）
    public int ChuanKou2;//串口2启动协议：0 中成、1 Modbus、2 4–20mA
    public int ChuanKou4;//串口4启动模式：0/1 控制，2/3 探针代理
    public boolean ChuanKou5;//串口5设置

    public boolean IsCreateAlarm;//生成报警记录
    public boolean IsDelAlarm;//删除报警记录

    public boolean IsCreateOut;//生成出库记录
    public boolean IsDelOut;      //删除出库记录

    public boolean IsCreatStock;//生成库存记录
    public boolean IsDelStock;    //删除库存记录

    public boolean IsCreatIn;//生成入库记录
    public boolean IsDelIn;    //删除入库记录

    public boolean IsCreatShift;//生成交班记录
    public boolean IsDelShift;    //删除交班记录


    private static final Singleton INSTANCE = new Singleton();

    private Singleton() {
    }

    public static Singleton getInstance() {
        return INSTANCE;
    }

    public void getSetData(String name) {
        if (name.equals(Constans.StockTime) || "".equals(name)){
            Singleton.getInstance().StockTime = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.StockTime, 0)*3600;
        }

        if (name.equals(Constans.isTestStatus) || "".equals(name)){

            Singleton.getInstance().isTestStatus = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.isTestStatus, 0) == 0 ? false : true;
            Log.e("TAG状态", Singleton.getInstance().isTestStatus+" ");
        }

        if (name.equals(Constans.OutHight) || "".equals(name))
            Singleton.getInstance().OutHight = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight, 0);
        if (name.equals(Constans.OutTime) || "".equals(name))
            Singleton.getInstance().OutTime = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutTime, 0);
        if (name.equals(Constans.InHight) || "".equals(name))
            Singleton.getInstance().InHight = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InHight, 0);
        if (name.equals(Constans.InTime) || "".equals(name))
            Singleton.getInstance().InTime = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InTime, 0);
        if (name.equals(Constans.IsBaoJing) || "".equals(name))
            Singleton.getInstance().IsBaoJing = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsBaoJing, 0) == 0 ? false : true;
        if (name.equals(Constans.IsJiaoBan) || "".equals(name))
            Singleton.getInstance().IsJiaoBan = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsJiaoBan, 0) == 0 ? false : true;
        if (name.equals(Constans.IsChuKu) || "".equals(name))
            Singleton.getInstance().IsChuKu = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsChuKu, 0) == 0 ? false : true;
        if (name.equals(Constans.IsRuKu) || "".equals(name))
            Singleton.getInstance().IsRuKu = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsRuKu, 0) == 0 ? false : true;
        if (name.equals(Constans.IsKuCun) || "".equals(name))
            Singleton.getInstance().IsKuCun = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsKuCun, 0) == 0 ? false : true;
        // 运行协议统一取启动快照，避免设置页保存后仅部分模块切换协议。
        if (name.equals(Constans.ChuanKou2) || "".equals(name)) {
            Singleton.getInstance().ChuanKou2 = com.sansen.yeweiyi.manager.ProbeProxyManager.getSourceProtocol();
        }

        if (name.equals(Constans.ChuanKou4) || "".equals(name))
            Singleton.getInstance().ChuanKou4 = com.sansen.yeweiyi.manager.ProbeProxyManager.getMode();
        if (name.equals(Constans.ChuanKou5) || "".equals(name))
            Singleton.getInstance().ChuanKou5 = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou5, 0) == 0 ? false : true;
        if (name.equals(Constans.IsCreateAlarm) || "".equals(name))
            Singleton.getInstance().IsCreateAlarm = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateAlarm, 0) == 0 ? false : true;
        if (name.equals(Constans.IsDelAlarm) || "".equals(name))
            Singleton.getInstance().IsDelAlarm = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelAlarm, 0) == 0 ? false : true;
        if (name.equals(Constans.IsCreateOut) || "".equals(name))
            Singleton.getInstance().IsCreateOut = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateOut, 0) == 0 ? false : true;
        if (name.equals(Constans.IsDelOut) || "".equals(name))
            Singleton.getInstance().IsDelOut = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelOut, 0) == 0 ? false : true;
        if (name.equals(Constans.IsCreatStock) || "".equals(name))
            Singleton.getInstance().IsCreatStock = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatStock, 0) == 0 ? false : true;
        if (name.equals(Constans.IsDelStock) || "".equals(name))
            Singleton.getInstance().IsDelStock = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelStock, 0) == 0 ? false : true;
        if (name.equals(Constans.IsCreatIn) || "".equals(name))
            Singleton.getInstance().IsCreatIn = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatIn, 0) == 0 ? false : true;
        if (name.equals(Constans.IsDelIn) || "".equals(name))
            Singleton.getInstance().IsDelIn = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelIn, 0) == 0 ? false : true;
        if (name.equals(Constans.IsCreatShift) || "".equals(name))
            Singleton.getInstance().IsCreatShift = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatShift, 0) == 0 ? false : true;
        if (name.equals(Constans.IsDelShift) || "".equals(name))
            Singleton.getInstance().IsDelShift = SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelShift, 0) == 0 ? false : true;
    }

    public boolean[] SoundLightAlarm = new boolean[17];
    public boolean[] isWaterAlarm = new boolean[17];
    public boolean[] isTemperAlarm = new boolean[17];
    public boolean[] isHHightAlarm = new boolean[17];
    public boolean[] isLowAlarms = new boolean[17];
    public boolean[] isLLLowAlarm = new boolean[17];
}
