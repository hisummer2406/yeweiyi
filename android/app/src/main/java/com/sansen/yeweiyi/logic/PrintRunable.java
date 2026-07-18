package com.sansen.yeweiyi.logic;

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
import com.sansen.yeweiyi.utils.SystemUtils;

import java.io.UnsupportedEncodingException;
import java.util.List;

import serialport.help.SerialPortHelper;

public class PrintRunable implements Runnable {
    private SerialPortHelper serialPort;
    private String TAG = "PrintRunable";
    private MyApplication myApplication;
    private DBDataManager dbDataManager;
    private OilcanDataManager oilcanDataManager;
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
                        println(SystemUtils.getRsStr(R.string.communication)
                                + (oilCan.getErrorTimes() == 0 ? SystemUtils.getRsStr(R.string.connect_normal) : SystemUtils.getRsStr(R.string.oil_Communication_interrupt)));
                        println( SystemUtils.getRsStr(R.string.temperature)+ oilCan.getTemper() + " ℃");
                        println(SystemUtils.getRsStr(R.string.water_volume) + oilCan.getWaterVolume() + " L");
                        println(SystemUtils.getRsStr(R.string.water_level) + oilCan.getWaterLevel() + " mm");
                        println(SystemUtils.getRsStr(R.string.oil_addible) + oilCan.getSurPlus() + " L");
                        println(SystemUtils.getRsStr(R.string.oil_volume)+ oilCan.getOilVolume() + " L");
                        println(SystemUtils.getRsStr(R.string.oil_level) + oilCan.getOilLevel() + " mm");
                        println(SystemUtils.getRsStr(R.string.oil_type)  + Const.getOilType(oilCan.getOilType()));
                        println(SystemUtils.getRsStr(R.string.tank_no) + oilCan.getOilCanPos());
                    }
                    println("-------"+SystemUtils.getRsStr(R.string.details)+"-------");
                    break;

                case 2:
                    OilInOut oilInOut = (OilInOut) dbDataManager
                            .findLast(OilInOut.class);

                    println("--------END-------");
                    if (null==oilInOut){
                        println("--------记录不存在-------");
                        return;
                    }
                    println(SystemUtils.getRsStr(R.string.uload_weight)+":" + oilInOut.getBeginHeigth());
                    println(SystemUtils.getRsStr(R.string.uload_volume) +":"+ oilInOut.getBeginVolum());
                    println(SystemUtils.getRsStr(R.string.endHeight) +":" + oilInOut.getEndHeigth());
                    println(SystemUtils.getRsStr(R.string.beginHeight)+":"+ oilInOut.getBeginHeigth());
                    println(SystemUtils.getRsStr(R.string.time)+":" + oilInOut.getTime());
                    println(SystemUtils.getRsStr(R.string.tank_no)+":" + oilInOut.getOilId());
                    println("-------"+SystemUtils.getRsStr(R.string.in_out_oil_record)+"------");
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
                        println(SystemUtils.getRsStr(R.string.oil_weight) + oil.getOilWeigh());
                        println(SystemUtils.getRsStr(R.string.oil_volume) + oil.getOilVolum());
                        println(SystemUtils.getRsStr(R.string.inoil_water) + oil.getWaterHeight());
                        println(SystemUtils.getRsStr(R.string.oil_level) + oil.getOilHeight());
                        println(SystemUtils.getRsStr(R.string.oil_no)  + oil.getOilId());
                        println(SystemUtils.getRsStr(R.string.time) + oilSaveInfo.getTime());
                    }
                    println("-------"+SystemUtils.getRsStr(R.string.save_record)+"------");
                    break;
                case 4:
                    CommitWork commitWork = (CommitWork) dbDataManager
                            .findLast(CommitWork.class);
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
                        println(SystemUtils.getRsStr(R.string.shell_weight)+":" + oilWork.getShellWeight());
                        println(SystemUtils.getRsStr(R.string.shell_volue)+":" + oilWork.getShellVolume());
                        println(SystemUtils.getRsStr(R.string.jiaoban_height)+":" + oilWork.getEndHeight());
                        println(SystemUtils.getRsStr(R.string.jieban_height)+":"+ oilWork.getBeginHeight());
                        println(SystemUtils.getRsStr(R.string.tank_no)+":" + oilWork.getOilId());
                    }
                    println(SystemUtils.getRsStr(R.string.time) + commitWork.getTime());
                    println("-------"+SystemUtils.getRsStr(R.string.commitwork_record)+"------");
                    break;
                case 5:
                    println("--------END-------");
                    Sideleakage sideleakage = (Sideleakage) dbDataManager
                            .findLast(Sideleakage.class);
                    if (null==sideleakage){
                        println("--------记录不存在-------");
                        return;
                    }
                    println(sideleakage.getInfo());
                    println(SystemUtils.getRsStr(R.string.time)+":" + sideleakage.getTime());
                    println("-------"+SystemUtils.getRsStr(R.string.celou_record)+"------");
                    break;
            }
        } else {
            handler.sendEmptyMessage(0);
        }
    }

    Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            SystemUtils.showRsToast(R.string.no_connect_print);
        }

        ;
    };

    private void println(String data) {
        try {
            serialPort.sendData(data.getBytes("GBK"));
            Thread.sleep(20);
            serialPort.sendData(new byte[]{0x0A});
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            //e.printStackTrace();
        }
    }
}