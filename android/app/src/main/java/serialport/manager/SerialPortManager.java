package serialport.manager;

import android.util.Log;
import android.support.v4.util.ArrayMap;

import com.sansen.yeweiyi.applog.RelayLog;
import com.sansen.yeweiyi.applog.ProbeProxyLog;
import com.sansen.yeweiyi.manager.ProbeProxyManager;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;

import java.util.Set;

import asynmsg.manage.AsyManager;
import serialport.help.SerialPortHelper;
import serialport.thread.IThread;

/**
 * HAL：底层 JNI 字节收发，按启动模式将串口 4 数据路由到控制板或探针代理。
 * UI/业务层经 com.sansen.yeweiyi.manager.SerialPortManager 访问硬件。
 * 代理解码与缓存由 ProbeProxyManager 提供，HAL 不解析探针协议。
 */

public class SerialPortManager {

    public static final String MAIN_CONTROL = "mainControl";
    public static final String DEVICE_CONTROL = "deviceControl";


    public final String TAG = "SeripotManager";

    public ArrayMap<String, IThread> maps = new ArrayMap<String, IThread>();

    // 串口名列表
    public static final String[] m_iSerialPort = {"S4", "S1", "S2",
            "S5"};

    // 波特率
    public static final int[] baudrate = {115200, 57600, 38400, 19200, 9600,
            4800, 2400, 1200, 250};

    // 数据位++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    public static final int[] databits = {5, 6, 7, 8};

    // 停止位
    public static final int[] stopbits = {1, 2};

    // 校验位
    /**
     * { "None", "Odd", "Even", "Mark", "Space" }
     */
    public static final int[] paritys = {'n', 'o', 'e', 'm', 's'};

    private SerialPortHelper mainSerialPort = new SerialPortHelper(m_iSerialPort[0], baudrate[4],
            databits[3], stopbits[0], paritys[0]);

//    private SerialPortHelper driverSerialBoard = new SerialPortHelper(m_iSerialPort[0], baudrate[4],
//            databits[3], stopbits[0], paritys[0]);
//
//    //给她的话，应该是[0]
//    private SerialPortHelper sensorSerialPort = new SerialPortHelper(m_iSerialPort[0], baudrate[4],
//            databits[3], stopbits[0], paritys[0]);

    //IO 解码工具
    private IoBufferManager mainbuffManager = new IoBufferManager(new QCode());

    //IO 解码工具
    private IoBufferManager driverBoardBuffManager = new IoBufferManager(new QCode());


    private static SerialPortManager instance = new SerialPortManager();

    private SerialPortManager() {
        //启动主控制线程
        startMainContorlThread();
      //  startDriverBoardContorlThread();
    }


    private void startMainContorlThread() {
        mainbuffManager
                .setIoHandler(
                        new IoBufferManager.IoHandler() {
                            @Override
                            public void resultObjet(Object object) {
//                                Log.i(TAG, "resultObjet: 第一个驱动板接受的对象"+object);
                                AsyManager.getInstance().recive((QuDongBan)object);
                            }
                        }

                );
        mainThread.setName("串口接受线程");
        mainThread.start();
        maps.put(MAIN_CONTROL, mainThread);
        AsyManager.getInstance().setSerportHelp(mainSerialPort);
    }

//    private void startDriverBoardContorlThread() {
//        driverBoardBuffManager
//                .setIoHandler(
//                        new IoBufferManager.IoHandler() {
//                            @Override
//                            public void resultObjet(Object object) {
//                                Log.i(TAG, "第二个驱动板接受的对象: "+object);
//                                AsyManager2.getInstance().recive((QuDongBan) object);
//                            }
//                        }
//
//                );
//        deviceThread.setName("串口接受线程");
//        deviceThread.start();
//        maps.put(DEVICE_CONTROL, deviceThread);
//      //  AsyManager2.getInstance().setSerportHelp(driverSerialBoard);
//    }


    public void startThread(String key) {
        maps.get(key).startThread();
    }

    public void pauseThread(String key) {
        maps.get(key).pauseThread();
    }

    public void stopThread(String key) {
        maps.get(key).stopThread();
    }

    public void stopAllThread() {
        Set<String> keys = maps.keySet();
        for (String key : keys) {
            maps.get(key).stopThread();
        }
    }


    public static synchronized SerialPortManager getInstance() {
        if (instance == null) {
            instance = new SerialPortManager();
        }
        return instance;
    }


    private IThread mainThread = new IThread(mainSerialPort, mainbuffManager) {

        @Override
        public void run() {
            while (!isStop) {
                if (ProbeProxyManager.isProxyMode()) {
                    try {
                        Thread.sleep(10);
                        if (isPause) continue;
                        if (!mainSerialPort.isOpen) {
                            ProbeProxyManager.invalidate();
                            ProbeProxyLog.notice("serial4-closed");
                            continue;
                        }
                        byte[] request = mainSerialPort.receiveData();
                        if (request != null && request.length > 0) {
                            byte[] reply = ProbeProxyManager.serial4Request(request);
                            if (reply != null && mainSerialPort.isOpen) mainSerialPort.sendData(reply);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                if (SharePreManager.getRelaySwitch()) {
                    try {
                        Thread.sleep(250);
                        if (mainSerialPort.isOpen) {
                            byte[] datas = mainSerialPort.receiveData();
//                        byte[] datas = new byte[]{(byte) 0xFA, (byte) 0xFA, (byte) 0xFA, (byte) 0xA6,0x02,0x02,0x00,0x00,(byte) 0x61, (byte) 0xA8};
                            if (datas != null && datas.length > 0) {
                                RelayLog.onReceive(datas);
//                            DebugManager.getInstance().recive(DebugManager.PROBE_DATA, DataUtils.bytesToHexString(datas, datas.length));
                               //Log.i(TAG, "撬装站 run: 接收的数据" + DataUtils.bytesToHexString(datas, datas.length));
                                mainbuffManager.setBuffer(datas);
                                if (reciveDataListener != null) {
                                    reciveDataListener.reciveData(datas);
                                }
                            }
                        }
                        //如果暂停线程的话
                        while (isPause) {
                            Thread.sleep(500);
//                        Log.i(TAG, "run: 休眠中");
                        }
//                    Log.i(TAG, "run: 正常运行"+mainSerialPort.isOpen);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "run 执行失败", e);
                    }
                }else{
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "run 执行失败", e);
                    }
                }
            }
        }
    };

//    private IThread deviceThread = new IThread(driverSerialBoard, driverBoardBuffManager) {
//
//        @Override
//        public void run() {
//            while (!isStop) {
//                if (SharePreManager.getRelaySwitch()) {
//                    if (SharePreManager.getOneOrTwo()==2) {
//                        try {
//                            Thread.sleep(250);
//
//                            if (driverSerialBoard.isOpen) {
//                                byte[] datas = driverSerialBoard.receiveData();
//                                Log.i(TAG, "第二个驱动版: " + (datas == null ? datas : DataUtils.bytesToHexString(datas, datas.length)));
//
//                                if (datas != null && datas.length > 0) {
//                                    driverBoardBuffManager.setBuffer(datas);
//                                    if (reciveDataListener != null) {
//                                        reciveDataListener.reciveData(datas);
//                                    }
//                                }
//                            }
//                            //如果暂停线程的话
//                            while (isPause) {
//                                Thread.sleep(500);
////                        Log.i(TAG, "run: 休眠中");
//                            }
////                    Log.i(TAG, "run: 正常运行"+mainSerialPort.isOpen);
//                        } catch (InterruptedException e) {
//                            //e.printStackTrace();
//                        }
//                    }
//                }else{
//                    try {
//                        Thread.sleep(250);
//                    } catch (InterruptedException e) {
//                        //e.printStackTrace();
//                    }
//                }
//            }
//        }
//    };


    ProbeReciveDataListener reciveDataListener;

    public void sendByteProbe(byte[] datas) {
        if (!ProbeProxyManager.allowsControl()) return;
        mainSerialPort.sendData(datas);
//        Log.i("发送的数据", DataUtils.bytesToHexString(datas, datas.length));
    }

    public void sendMsgMain(byte[] data) {
        if (!ProbeProxyManager.allowsControl()) return;
        //        LogUtils.i(TAG, "发送" + DataUtils.bytesToHexString(bytes, bytes.length));
        mainSerialPort.sendData(data);
    }


    public interface ProbeReciveDataListener {
        void reciveData(byte[] bytes);
    }

    public void setProbeReciveData(ProbeReciveDataListener reciveDataListener) {
        this.reciveDataListener = reciveDataListener;
    }

//    private void startPresureThread() {
//        new Thread() {
//            @Override
//            public void run() {
//                SensorCode sensorCode = new SensorCode();
//
//                while (sensorSerialPort.isOpen) {
//                    sensorSerialPort.sendData(sensorCode.encode((byte) 01));
//                    try {
//                        Thread.sleep(1000);
//                    } catch (InterruptedException e) {
//                        //e.printStackTrace();
//                    }
//                    byte[] bytes = sensorSerialPort.receiveData();
//                    if (bytes!=null) {
//                        sensorCode.decode(bytes);
//                    }
//                }
//            }
//        }.start();
//    }
}
