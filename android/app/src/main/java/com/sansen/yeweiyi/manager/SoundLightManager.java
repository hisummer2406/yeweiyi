package com.sansen.yeweiyi.manager;

import android.app.XzjhSystemManager;
import android.util.Log;
import android.util.SparseArray;

import com.example.x6.gpioctl.GpioUtils;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.util.Timer;
import java.util.TimerTask;

import asynmsg.intf.IAsyData;
import asynmsg.manage.AsyManager;
import asynmsg.manage.AsyManager2;
import asynmsg.manage.ObservableManager;


public class SoundLightManager {
    private int io;
    private GpioUtils ioUtils;
    private static char group = 'L';
    private static int num = 4;
    private static int high = 1;
    private static int low = 0;

    /**
     * 当接收到消警信息的时候我们应该不在允许进行打开报警，直到关闭报警的时候再重新打开报警
     * 当报警状态为true 消警=true时，
     */

    /**
     * 驱动板报警
     * <p>
     * 如果是高油位低油位报警时，有声光的开启声光，没有声光开启驱动板报警
     * 如果是其他报警，直接开启驱动板报警
     */
    private String TAG = "SoundLightManager";

    private static volatile SoundLightManager instance;

    /**
     * 用于保存当前所有油罐的状态信息
     * key=油罐号  boolean 是否报警
     */
    // private SparseArray<Boolean> hMap = new SparseArray<Boolean>();//高油位报警
    private SparseArray<Boolean> lMap = new SparseArray<Boolean>();//低油位报警

    private SparseArray<Boolean> fyfMap = new SparseArray<Boolean>();//高油位报警

    int count = 3;

    /**
     * 报警状态
     */


    public static SoundLightManager getInstance() {
        if (instance == null) {
            synchronized (SoundLightManager.class) {
                if (instance == null) {
                    instance = new SoundLightManager();
                }
            }
        }
        return instance;
    }

    public SoundLightManager() {
//        ioUtils = GpioUtils.getGpioUtils();
//        io = ioUtils.getGpioPin(group, num);
//        ioUtils.setGpioDirection(io, 0);
//        ioUtils.gpioSetValue(io, low);
    }


    /**
     * 只要当前油高高油位报警，就会调用该方法
     *
     * @param oilCanId
     */
    public void openHeightAlarm(final int oilCanId, boolean isHHeightAlarm) {
        openL_S();
//        Log.i(TAG, "高油位打开的油罐号  " + oilCanId + "  " + fyfMap.get(oilCanId));


    }

    int modeType = 0;

    public void FYF(int oilCanId, boolean isHHeightAlarm) {
        if (fyfMap.get(oilCanId) == null) {
            fyfMap.put(oilCanId, false);
        }
        if (Singleton.getInstance().ChuanKou4) {
            modeType = 0;
        } else {
            modeType = 4;
        }


        // int mod = SharePreManager.getRelayMod();
        if (isHHeightAlarm) {
            if (fyfMap.get(oilCanId) == false) {
                fyfMap.put(oilCanId, true);//将某个油罐设置为打开防溢阀
                distribution(oilCanId, modeType, true);
            }
        } else {
            if (fyfMap.get(oilCanId) == true) {
                fyfMap.put(oilCanId, false);//将某个油罐设置为打开防溢阀
            }
        }

    }

    public void FYFL(int oilCanId, boolean isLLowAlarm) {
        if (!Singleton.getInstance().ChuanKou4) {
            return;
        } else {
            modeType = 1;
        }
//        if (isLLowAlarm) {
//            if (lMap.get(oilCanId) != null) {
//
//            }
//            if (lMap.get(oilCanId) == false) {
//                lMap.put(oilCanId, true);//将某个油罐设置为打开防溢阀
//                distribution(oilCanId, modeType, true);
//            }
//        } else {
//            if (lMap.get(oilCanId) == true) {
//                lMap.put(oilCanId, false);//将某个油罐设置为打开防溢阀
//            }
//        }
        if (isLLowAlarm) {
            if (lMap.get(oilCanId) != null) {
                if (lMap.get(oilCanId) == false) {

                    lMap.put(oilCanId, true);//将某个油罐设置为低油位报警
                }
            } else {

                lMap.put(oilCanId, true);//将某个油罐设置为低油位报警
            }
            distribution(oilCanId, modeType, true);
        }
        else {
            if (lMap.get(oilCanId)==null){
                fyfMap.put(oilCanId, false);
            }
            else  {
                lMap.put(oilCanId, false);//将某个油罐设置为打开防溢阀
            }
        }
    }

    boolean isClose;//是否所有的油罐都为非报警状态

    public void closeAlarm(int oilCanId) {
        fyfMap.put(oilCanId, false);
        for (int i = 0; i < lMap.size(); i++) {
            if (lMap.valueAt(i) != null && lMap.valueAt(i) == true) {
                isClose = false;
                break;
            }
        }
        closeL_S();


    }

    public boolean IsWenDuAlarm = true;

    public void CanleAlarm() {

        IsWenDuAlarm = false;
        closeL_S();
    }

    public void openLowOilAlarm(final int oilCanId, boolean isLLowAlarm) {

        openL_S();

    }


    SoundManager sm = SoundManager.getInstance();

    /**
     * 打开声光报警
     */
    public void openL_S() {
        sm.playBGM(R.raw.test, true);
        //ioUtils.gpioSetValue(io, high);
        Log.e("TAG", "打开声光");
        XzjhSystemManager mManager = (XzjhSystemManager) MyApplication.getInstance().getSystemService("xzjh_server");
        mManager.xzjhSetGpioValue(1, 1);
    }

    /**
     * 关闭声光报警
     */
    public void closeL_S() {
        sm.pauseBGM();
        // ioUtils.gpioSetValue(io, low);
        Log.e("TAG", "关闭声光");
        XzjhSystemManager mManager = (XzjhSystemManager) MyApplication.getInstance().getSystemService("xzjh_server");
        mManager.xzjhSetGpioValue(1, 0);
    }


    public void openQyb(int id, ObservableManager.ReciveListener listener) {
        AsyManager.getInstance().openOne(id, (byte) 0x00, listener);
    }
//    public void openFyf(int id,ObservableManager.ReciveListener listener){
//        AsyManager.getInstance().openOne(id, , listener);
//    }

    public void closeRelay(int id, ObservableManager.ReciveListener listener) {
        AsyManager.getInstance().closeOne(id, listener);
    }

    private TimerTask task;
    Timer timer;

    public void stopAlarm() {

        if (!SharePreManager.getAlarm()) {
            // closeL_S();
            SharePreManager.saveAlarm(true);
            /* task = new TimerTask() {
                @Override
                public void run() {
                    SharePreManager.saveAlarm(false);
                    task.cancel();
                    timer.cancel();
                }
            };
             timer = new Timer();
            timer.schedule(task, SharePreManager.getAlarmTime() * 1000);*/


        } else {
            SharePreManager.saveAlarm(false);

            // ToastUtils.showToastLong("请勿重复消警");
        }
    }

    public void openQyb_2(int id, ObservableManager.ReciveListener listener) {
        AsyManager2.getInstance().openOne(id, (byte) 0x00, listener);
    }

    public void closeRelay_2(int id, ObservableManager.ReciveListener listener) {
        AsyManager2.getInstance().closeOne(id, listener);
    }

    /*
     * @param oilCanId
     * @param mod
     * @param openOrClose    openOrClose  true 打开/ false 关闭
     */
    public void distribution(int oilCanId, int mod, boolean openOrClose) {
        open(oilCanId, mod);

    }


    private void open(final int oilCanId, final int mod) {

        AsyManager.getInstance().openOne(oilCanId, mod, new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {
                ToastUtils.showToastLong("Open Success " + oilCanId);
            }

            @Override
            public void error(int id) {
                ToastUtils.showToastLong("Open error " + oilCanId);
            }
        });
//        int max = 0;
//        byte modByte = 0x00;
//        switch (mod) {
//            case SharePreManager.FYF:
//                max = 7;
//                modByte = 0x66;
//                break;
//            case SharePreManager.XYB:
//                modByte = 0x00;
//                max = 6;
//                break;
//            case SharePreManager.MIXING:
//                modByte = 0x00;
//                max = 6;
//                break;
//        }
//
//        if (oilCanId <= max) {
//            //打开防溢阀
//            if (mod == SharePreManager.MIXING) {
//                AsyManager2.getInstance().openOne(oilCanId, modByte, new ObservableManager.ReciveListener() {
//                    @Override
//                    public void recive(int id, IAsyData object) {
//                        ToastUtils.showToastLong("Open Success " + oilCanId);
//                    }
//
//                    @Override
//                    public void error(int id) {
//
//                    }
//                });
//            } else {
//                AsyManager.getInstance().openOne(oilCanId, modByte, new ObservableManager.ReciveListener() {
//                    @Override
//                    public void recive(int id, IAsyData object) {
//                        ToastUtils.showToastLong("Open Success " + oilCanId);
//                    }
//
//                    @Override
//                    public void error(int id) {
//
//                    }
//                });
//            }
//        } else {
//            AsyManager2.getInstance().openOne(oilCanId - max, modByte, new ObservableManager.ReciveListener() {
//                @Override
//                public void recive(int id, IAsyData object) {
//
//                }
//
//                @Override
//                public void error(int id) {
//
//                }
//            });
//        }
    }

    private void close(final int oilCanId, int mod) {
        int max = 0;

        switch (mod) {
            case SharePreManager.FYF:
                max = 7;
                break;
            case SharePreManager.XYB:
                max = 6;
                break;
        }

        if (oilCanId <= max) {
            //打开防溢阀
            AsyManager.getInstance().closeOne(oilCanId, new ObservableManager.ReciveListener() {
                @Override
                public void recive(int id, IAsyData object) {
                    ToastUtils.showToastLong("Open Close " + oilCanId);
                }

                @Override
                public void error(int id) {

                }
            });
        } else {
            AsyManager2.getInstance().closeOne(oilCanId - max, new ObservableManager.ReciveListener() {
                @Override
                public void recive(int id, IAsyData object) {

                }

                @Override
                public void error(int id) {

                }
            });
        }
    }
}
