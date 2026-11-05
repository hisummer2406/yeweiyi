package com.sansen.yeweiyi.logic;

import android.util.Log;
import android.os.Handler;
import android.util.SparseArray;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.Oil;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SerialPortManager;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.repository.ShiftRepository;
import com.sansen.yeweiyi.repository.ShiftRepositoryImpl;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.io.UnsupportedEncodingException;
import java.util.List;

import serialport.help.SerialPortHelper;

// [不可达-2026-09-21] 唯一构造点是 MainActivity.onActivityResult() 的 requestCode==1000 分支，
// 全仓库没有任何地方以这个请求码发起调用（西语翻译覆盖率核查时发现）。这个类里 println() 用到的
// 约 18 个字符串资源目前不会被用户看到，加新语言不用优先翻它们。
// [已更正-2026-09-23] 这条注释只覆盖"这个类自己 println() 用到的字符串"——R.string.oil_no /
// R.string.inoil_water / R.string.oil_Communication_interrupt 这几个虽然也在这个类里出现过，
// 但同时还被别的活页面独立引用（oil_Communication_interrupt 被 MinOilCanView 用于首页罐图标的
// 通信中断提示，是可达的，已移出不可达清单，见那边的注释）。判定一个 key 不可达前要看它*全部*
// 引用处，不能因为在这个类里出现就默认它整体死了。
public class PrintRunable implements Runnable {
    private SerialPortHelper serialPort;
    private String TAG = "PrintRunable";
    private MyApplication myApplication;
    private DBDataManager dbDataManager;
    private OilcanDataManager oilcanDataManager;
    private ShiftRepository shiftRepository;
    private StockRepository stockRepository;
    private OilTankRepository oilTankRepository;
    private int printId;

    public PrintRunable(int printId) {
        this.printId = printId;
        this.serialPort = new SerialPortHelper(
                SerialPortManager.m_iSerialPort[0],
                SerialPortManager.baudrate[4], SerialPortManager.databits[3],
                SerialPortManager.stopbits[0], SerialPortManager.paritys[0]);
        myApplication = MyApplication.getInstance();
        dbDataManager = myApplication.getmDBManager();
        oilcanDataManager = myApplication.getmOilcanDataManager();
        shiftRepository = ShiftRepositoryImpl.getInstance();
        stockRepository = StockRepositoryImpl.getInstance();
        oilTankRepository = OilTankRepositoryImpl.getInstance();
    }

    public void run() {
        //Log.i(TAG, "run: 准备打印====");

        if (serialPort.isOpen) {
            SparseArray<OilCan> map = oilcanDataManager.getData();
            OilCan oilCan;
            int size;
            switch (printId) {
                case 0:
                    println("--------END-------");
                    for (int i = 0; i < map.size(); i++) {
                        oilCan = map.valueAt(i);
                        println(SystemUtils.getRsStr(R.string.temperature)+":" + oilCan.getTemper() + " ℃");
                        println(SystemUtils.getRsStr(R.string.water_level) + oilCan.getWaterLevel() + " mm");
                        println(SystemUtils.getRsStr(R.string.oil_level)+ oilCan.getOilLevel() + " mm");
                        println(SystemUtils.getRsStr(R.string.oiltype) + Const.getOilType(oilCan.getOilType()));
                        println(SystemUtils.getRsStr(R.string.tank_no) +":"+ oilCan.getOilCanPos());
                    }
                    println("-------TnakInfo-------");
                    break;

                case 1:
                    println("--------END-------");
                    for (int i = 0; i < map.size(); i++) {
                        oilCan = map.valueAt(i);
                        // [已屏蔽-2026-09-23] communication/connect_normal 已删除（PrintRunable 类不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.communication)
                        //         + (oilCan.getErrorTimes() == 0 ? SystemUtils.getRsStr(R.string.connect_normal) : SystemUtils.getRsStr(R.string.oil_Communication_interrupt)));
                        println( SystemUtils.getRsStr(R.string.temperature)+ oilCan.getTemper() + " ℃");
                        // [已屏蔽-2026-09-23] water_volume 已删除（不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.water_volume) + oilCan.getWaterVolume() + " L");
                        println(SystemUtils.getRsStr(R.string.water_level) + oilCan.getWaterLevel() + " mm");
                        // [已屏蔽-2026-09-23] oil_addible 已删除（不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.oil_addible) + oilCan.getSurPlus() + " L");
                        // [已屏蔽-2026-09-23] oil_volume 已删除（不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.oil_volume)+ oilCan.getOilVolume() + " L");
                        println(SystemUtils.getRsStr(R.string.oil_level) + oilCan.getOilLevel() + " mm");
                        println(SystemUtils.getRsStr(R.string.oil_type)  + Const.getOilType(oilCan.getOilType()));
                        println(SystemUtils.getRsStr(R.string.tank_no) + oilCan.getOilCanPos());
                    }
                    // [已屏蔽-2026-09-23] details 已删除（PrintRunable 类不可达，见上方类注释）。
                    // println("-------"+SystemUtils.getRsStr(R.string.details)+"-------");
                    break;

                case 2:
                    OilInOut oilInOut = stockRepository.findLastIn();

                    println("--------END-------");
                    if (null==oilInOut){
                        println("--------记录不存在-------");
                        return;
                    }
                    // [已屏蔽-2026-09-23] uload_weight/uload_volume 已删除（不可达，见上方类注释）。
                    // println(SystemUtils.getRsStr(R.string.uload_weight)+":" + oilInOut.getBeginHeigth());
                    // println(SystemUtils.getRsStr(R.string.uload_volume) +":"+ oilInOut.getBeginVolum());
                    println(SystemUtils.getRsStr(R.string.endHeight) +":" + oilInOut.getEndHeigth());
                    println(SystemUtils.getRsStr(R.string.beginHeight)+":"+ oilInOut.getBeginHeigth());
                    println(SystemUtils.getRsStr(R.string.time)+":" + oilInOut.getTime());
                    println(SystemUtils.getRsStr(R.string.tank_no)+":" + oilInOut.getOilId());
                    // [已屏蔽-2026-09-23] in_out_oil_record 已删除（不可达，见上方类注释）。
                    // println("-------"+SystemUtils.getRsStr(R.string.in_out_oil_record)+"------");
                    break;
                case 3:
                    println("--------END-------");
                    OilSaveInfo oilSaveInfo = (OilSaveInfo) dbDataManager
                            .findLast(OilSaveInfo.class);
                    if (null==oilSaveInfo){
                        println("--------记录不存在-------");
                        return;
                    }
                    String oilInfoGosn = oilSaveInfo.getOilInfoGosn();
                    List<Oil> oils = dbDataManager.stringToArray(oilInfoGosn,
                            Oil[].class);
                    for (Oil oil : oils) {
                        // [已屏蔽-2026-09-23] oil_weight/oil_volume/inoil_water/oil_no 已删除（不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.oil_weight) + oil.getOilWeigh());
                        // println(SystemUtils.getRsStr(R.string.oil_volume) + oil.getOilVolum());
                        // println(SystemUtils.getRsStr(R.string.inoil_water) + oil.getWaterHeight());
                        println(SystemUtils.getRsStr(R.string.oil_level) + oil.getOilHeight());
                        // println(SystemUtils.getRsStr(R.string.oil_no)  + oil.getOilId());
                        println(SystemUtils.getRsStr(R.string.time) + oilSaveInfo.getTime());
                    }
                    // [已屏蔽-2026-09-23] save_record 已删除（不可达，见上方类注释）。
                    // println("-------"+SystemUtils.getRsStr(R.string.save_record)+"------");
                    break;
                case 4:
                    CommitWork commitWork = shiftRepository.findLast();
                    if (null==commitWork){
                        println("--------记录不存在-------");
                        return;
                    }
                    List<OilWork> oilWorks = dbDataManager.stringToArray(
                            commitWork.getInfo(), OilWork[].class);
                    if (null==oilWorks){
                        println("--------记录不存在-------");
                        return;
                    }
                    for (OilWork oilWork : oilWorks) {
                        // [已屏蔽-2026-09-23] shell_weight/shell_volue/jiaoban_height/jieban_height 已删除（不可达，见上方类注释）。
                        // println(SystemUtils.getRsStr(R.string.shell_weight)+":" + oilWork.getShellWeight());
                        // println(SystemUtils.getRsStr(R.string.shell_volue)+":" + oilWork.getShellVolume());
                        // println(SystemUtils.getRsStr(R.string.jiaoban_height)+":" + oilWork.getEndHeight());
                        // println(SystemUtils.getRsStr(R.string.jieban_height)+":"+ oilWork.getBeginHeight());
                        println(SystemUtils.getRsStr(R.string.tank_no)+":" + oilWork.getOilId());
                    }
                    println(SystemUtils.getRsStr(R.string.time) + commitWork.getTime());
                    println("-------"+SystemUtils.getRsStr(R.string.commitwork_record)+"------");
                    break;
                case 5:
                    println("--------END-------");
                    Sideleakage sideleakage = oilTankRepository.findLastSideleakage();
                    if (null==sideleakage){
                        println("--------记录不存在-------");
                        return;
                    }
                    println(sideleakage.getInfo());
                    println(SystemUtils.getRsStr(R.string.time)+":" + sideleakage.getTime());
                    // [已屏蔽-2026-09-23] celou_record 已删除（PrintRunable 类不可达，见上方类注释）。
                    // println("-------"+SystemUtils.getRsStr(R.string.celou_record)+"------");
                    break;
            }
        } else {
            handler.sendEmptyMessage(0);
        }
    }

    Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            // [已屏蔽-2026-09-23] no_connect_print 已删除（PrintRunable 类不可达，见上方类注释）。
            // SystemUtils.showRsToast(R.string.no_connect_print);
        }

        ;
    };

    private void println(String data) {
        try {
            serialPort.sendData(data.getBytes("GBK"));
            Thread.sleep(20);
            serialPort.sendData(new byte[]{0x0A});
        } catch (InterruptedException e) {
            Log.e(TAG, "println 执行失败", e);
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "println 执行失败", e);
        }
    }
}