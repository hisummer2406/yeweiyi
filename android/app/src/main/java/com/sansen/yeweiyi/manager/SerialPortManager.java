package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseActivity;
import com.sansen.yeweiyi.bean.BrandBean;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.factory.IParseDataListener;
import com.sansen.yeweiyi.factory.LD2Company;
import com.sansen.yeweiyi.factory.LDCompany;
import com.sansen.yeweiyi.factory.PCDataFactory;
import com.sansen.yeweiyi.factory.VR350Factory;
import com.sansen.yeweiyi.factory.ZhongChengCompany;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import serialport.help.SerialPortHelper;

public class SerialPortManager implements IParseDataListener {

    private static final String TAG = "SerialPortManager";
    private static SerialPortManager instance;
    // 串口名列表
    public static final String[] m_iSerialPort = {"S4", "S1", "S2",
            "S3"};
    // 波特率
    public static final int[] baudrate = {115200, 57600, 38400, 19200, 9600,
            4800, 2400, 1200, 250};
    // 数据位
    public static final int[] databits = {5, 6, 7, 8};
    // 停止位
    public static final int[] stopbits = {1, 2};
    // 校验位
    /**
     * { "None", "Odd", "Even", "Mark", "Space" }
     */
    public static final int[] paritys = {'n', 'o', 'e', 'm', 's'};
    private BrandBean brandBean;

    // pc端 232
    private PCPortThread pcPortThread;

    /**
     * 串口1 负责与PC进行通讯
     */
    private SerialPortHelper VR350;
    // 探针 串口1 AMA5
    private SerialPortHelper serialPortS2;
//    private SerialPortHelper serialPortS4;
    /**
     * 串口 232 AMA1 驱动板 硬件接口 RX1 TX1 GND
     */
    public static SerialPortHelper serialPort2 = new SerialPortHelper(
            m_iSerialPort[2], baudrate[4], databits[3], stopbits[0], paritys[0]);

    // 从外部传进来
    //
    // 的hander对象用于与主线程的交互
    private Handler mHandler;

    private Context mContext;

    private OilcanDataManager mOilCanDataManager;

    private boolean isStopProbe = true;
    private SerialPortHelper Censtar;

    /**
     * 获取SerialPortManager 该对象唯一 在获取时实现串口的初始化操作
     *
     * @param context
     * @return SerialPortManager
     */
    public static SerialPortManager getInstance(Context context) {

        if (instance == null) {
            instance = new SerialPortManager(context);
        }
        return instance;
    }

    /**
     * 从外面接收一个handler
     *
     * @param handler
     */
    public void setHandler(Handler handler) {

        if (handler != null) {
            mHandler = handler;
        }
    }

    /**
     * @param context
     */
    private SerialPortManager(Context context) {
        mContext = context;
        brandBean = (BrandBean) SharePrefUtil.getObj(context, SharePrefUtil.BrandSet);

        if (brandBean == null) {
            brandBean = new BrandBean(5, 3, 0, 0);
        }

        mOilCanDataManager = MyApplication.getInstance()
                .getmOilcanDataManager();

        initSerialPort();
        initThread();
    }

    /**
     * 初始化串口数据
     */
    private void initSerialPort() {
        // 连接探针
        serialPortS2 = new SerialPortHelper(m_iSerialPort[2], baudrate[brandBean.getBaudrate()],
                databits[brandBean.getDatabits()], stopbits[brandBean.getStopbits()], paritys[brandBean.getParitys()]);

//        serialPortS4=new SerialPortHelper(m_iSerialPort[0], baudrate[brandBean.getBaudrate()],
//                databits[brandBean.getDatabits()], stopbits[brandBean.getStopbits()], paritys[brandBean.getParitys()]);
        /**
         * FC FC FC
         * 06 1B
         * 01 05 00 00 00 00 00 00 00 00 04 19 38 04 19 38 00 00 00 00 00 00 00 00 00 06 8B CA 29
         */
    }

    /**
     * 初始化并启动访问串口线程
     */
    private void initThread() {
        // 访问Pc
        PCDataFactory pcDataFactory = null;
        //Log.i(TAG, "initThread:    " + SharePreManager.getBackstage());
        if (VR350Factory.VR350R.equals(SharePreManager.getBackstage())) {
            VR350 = new SerialPortHelper(m_iSerialPort[2], baudrate[4],
                    databits[3], stopbits[0], paritys[0]);
            pcPortThread = new PCPortThread("ReceiveThread1", VR350);
            pcDataFactory = new VR350Factory(mContext, pcPortThread);

        }

        pcPortThread.setFactory(pcDataFactory);

        pcPortThread.start();

        requestThread_2 = new RequestThread("Request02", serialPortS2);
        requestThread_2.start();
    }

    public void stopProbeThread() {
        isStopProbe = false;
    }

    public void startProbeThread() {
        isStopProbe = true;
    }

    public void sendByteProbe(byte[] bytes) {
        serialPortS2.sendData(bytes);
        //Log.i("发送的数据", DataUtils.bytesToHexString(bytes, bytes.length));
    }


    /**
     * 请求探针数据
     *
     * @author PC
     */
    class RequestThread extends Thread {
        private SerialPortHelper serialPort;
        private String name;
        int isWenDu = 0;
        //IoBufferManager buffManager;

        public RequestThread(String name, SerialPortHelper serialPort) {
            super(name);
            this.name = name;
            this.serialPort = serialPort;
        }

        public void run() {
            String data = null;
            // 如果串口一直打开，那么线程就一直执行
            SparseArray<OilCan> mOilCanData;
            OilCan mOilCan = null; // 由这里开始
            String oilCanData = null;
            boolean isRight = false;
            int j;
            while (serialPort.isOpen) {
                if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.BOOL_RRMAINNING, false) &&
                        System.currentTimeMillis() > DateUtils.getStringToDate(SharePrefUtil.getString(MyApplication.getInstance(),
                                SharePrefUtil.TIME_REMAINING, DateUtils.getYYYMMdd()), "yyyy-MM-dd")) {

                } else {
                    //  Log.e("TAG","--------------"+isStopProbe);
                    if (isStopProbe) {
                        mOilCanData = mOilCanDataManager.getData();// 保证每一次获取的数据为最新
                        // 由这里把新的信息分发到各个页面
                        for (j = 1; j <= mOilCanData.size(); j++) {
                            if (mOilCanData != null) {
                                try {
                                    if (mOilCanData.get(j) != null) {
                                        mOilCan = mOilCanData.get(j);
                                    } else {
                                        break;
                                    }
                                } catch (Exception e) {
                                    break;
                                }
                            }
                            if (isStopProbe) {
                                for (int i = 0; i < 5; i++) { //0x01,0x06,0x00,0x01,0x00,0x02修改波特率

                                    switch (mOilCan.getProbeType()) {
                                        case 0:
                                            if (isWenDu == 0) {

                                                isWenDu++;
                                                oilCanData = ZhongChengCompany.getInstance().sendWenDuInfo("FEFE", mOilCan.getProbeAdr(), mContext);

                                            } else {
                                                isWenDu++;
                                                oilCanData = ZhongChengCompany.getInstance().sendProbeInfo("FEFE", mOilCan.getProbeAdr(), mContext);
                                                if (isWenDu > 10)
                                                    isWenDu = 0;
                                            }
                                            break;
                                        case 1:
                                            oilCanData = LDCompany.getInstance().sendProbeInfo("", mOilCan.getProbeAdr(), mContext);
                                            //  oilCanData = RouXingCompany.getInstance().sendProbeInfo("04", mOilCan.getProbeAdr(),mContext);
                                            break;

                                        case 2:
                                            oilCanData = LD2Company.getInstance().sendProbeInfo("", mOilCan.getProbeAdr(), mContext);
                                            //  oilCanData = RouXingCompany.getInstance().sendProbeInfo("04", mOilCan.getProbeAdr(),mContext);
                                            break;
                                    }
                                    serialPort.sendData(DataUtils.HexString2Bytes(oilCanData));
//                                Log.i(TAG, "发送到串口数据" + oilCanData);
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e1) {
                                        // TODO Auto-generated catch block
                                        e1.printStackTrace();
                                    }

                                    data = null;
                                    byte[] bytes = serialPort.receiveData();

                                    if (bytes != null) {
                                        data = DataUtils.bytesToHexString(bytes, bytes.length);
                                    }
                                    //  Log.i(TAG, "run: 接受串口数据"+(bytes==null?bytes: DataUtils.bytesToHexString(bytes, bytes.length)));

                                    if (StringUtils.isNotEmpty(data)) {

                                        switch (mOilCan.getProbeType()) {
                                            case 0:
                                                try {
                                                    isRight = ZhongChengCompany
                                                            .getInstance()
                                                            .probeInfoPase(data, SerialPortManager.this);

                                                } catch (Exception e) {
                                                    //e.printStackTrace();
                                                }
                                                break;
                                            case 1:
                                                try {
                                                    isRight = LDCompany
                                                            .getInstance()
                                                            .probeInfoPase(data, SerialPortManager.this);
                                                } catch (Exception e) {

                                                }
                                                break;
                                        }
//                                    Log.i(TAG, "串口否判断正常" + mOilCan.getErrorTimes() + "  " + isRight);
                                        if (isRight) {
                                            mOilCan.setErrorTimes(0);
//                                        Log.i(TAG, "串口数据校验成功: "+mOilCan.getErrorTimes()+"==="+data);
                                            if (MyApplication.alarmStates.containsKey(mOilCan.getOilCanPos()) && MyApplication.alarmStates.get(mOilCan.getOilCanPos()).getConnectError() == 1) {
                                                MyApplication.alarmStates.get(mOilCan.getOilCanPos()).setConnectError(0);
                                            }
                                            break;
                                        } else {
//                                        Log.i(TAG, "第几次:" + i + " run: 串口接收数据" + data);
                                            mOilCan.setErrorTimes(mOilCan.getErrorTimes() + 1);
                                            //  Log.w(TAG, "串口CRC错误时: "+mOilCan.getErrorTimes()+"==="+data);
                                        }
                                    } else {
                                        mOilCan.setErrorTimes(mOilCan.getErrorTimes() + 1);
                                        // Log.w(TAG, "串口为空时: "+mOilCan.getErrorTimes());
                                    }
                                    crcError(mOilCan);
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        //e.printStackTrace();
                                    }
                                }

                            }
                        }
                    } else {
                        byte[] bytes = serialPort.receiveData();
//                    Log.i("读取探针中的数据", bytes + "");
                        if (bytes != null && bytes.length > 0) {
                            reciveDataListener.reciveData(bytes);
                        }
                    }
                }

            }
        }
    }

    ProbeReciveDataListener reciveDataListener;

    public interface ProbeReciveDataListener {
        void reciveData(byte[] bytes);
    }

    public void setProbeReciveData(ProbeReciveDataListener reciveDataListener) {
        this.reciveDataListener = reciveDataListener;
    }

    private void crcError(OilCan mOilCan) {
//        Log.i(TAG, "crcError: " + mOilCan.getErrorTimes());

        switch (mOilCan.getProbeType()) {
            case 0:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 1:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 2:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 3:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }//05 16 08 49 11 10 00 00 00 08 24 FF FF
                break;
            case 4:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 5:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 6:
                if (mOilCan.getErrorTimes() >= 5) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
            case 20:
                if (mOilCan.getErrorTimes() >= 20) {
                    handler.sendEmptyMessage(mOilCan.getOilCanPos());
                }
                break;
        }
    }

    OilMessage oilMessage;
    Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            oilMessage = new OilMessage();
            oilMessage.setDate(DateUtils.getYYYMMddHHmmss());
            oilMessage.setOilCanId(msg.what);
            oilMessage.setType(OilMessage.PRO_ERR_CONN);
            if (OilcanDataManager.getInstance(MyApplication.getInstance()).getData(msg.what) != null)
                oilMessage.setJieZhi(DataUtils.getOilType()[OilcanDataManager.getInstance(MyApplication.getInstance()).getData(msg.what).getOilType()]);

            if (MyApplication.alarmStates.containsKey(oilMessage.getOilCanId()) && MyApplication.alarmStates.get(oilMessage.getOilCanId()).getConnectError() == 0) {

                MyApplication.getInstance().getmDBManager().saveAlarm(oilMessage.getOilCanId(), oilMessage);
                MyApplication.alarmStates.get(oilMessage.getOilCanId()).setConnectError(1);
            }
            AlarmManager.getInstance().addAlarmState(oilMessage);
        }

        ;
    };

    private RequestThread requestThread_2;

    // 回调方法 当数据请求完成
    @Override
    public void hexDataParseFinish(OilCan oilCan) {
//        Log.i("刷新数据", oilCan.getOilCanPos() + "   " + oilCan.getOilLevel());
        // 每次更新完毕 都要去更新 oildataMagager
        mOilCanDataManager.refreshData(oilCan);

        if (mHandler != null) {
            Message msg = Message.obtain();
            msg.what = BaseActivity.DATA_REFRESH;//05 16 08 49 11 10 00 00 00 08 24 FF FF
            msg.obj = oilCan;                    //05 16 08 49 11 10 00 00 00 0F 24 F8
            mHandler.sendMessage(msg);
        }
    }


    /**
     * 请求探针数据
     *
     * @author PC
     */
    Lock lock = new ReentrantLock();


    @Override
    public void hexTestDataParseFinish(OilCan oilCan) {
        // 每次更新完毕 都要去更新 oildataMagager
        mOilCanDataManager.refreshTestData(oilCan);
        if (mHandler != null) {
            Message msg = Message.obtain();
            msg.what = BaseActivity.DATA_REFRESH;
            msg.obj = oilCan;

            mHandler.sendMessage(msg);
        }
    }

    public class PCPortThread extends Thread {
        private SerialPortHelper serialPort;
        public PCDataFactory hexFactory;

        public PCPortThread(String name, SerialPortHelper serialPort) {
            super(name);
            this.serialPort = serialPort;
        }

        public SerialPortHelper getSerialPort() {
            return serialPort;
        }

        public void setFactory(PCDataFactory hexFactory) {
            this.hexFactory = hexFactory;
        }

        public void run() {
            String data = null;
            while (serialPort.isOpen) {
                byte[] bytes = serialPort.receiveData();
                data = null;
                if (bytes != null) {
                    data = DataUtils.bytesToHexString(bytes, bytes.length);
//                    Log.i(TAG, "run: 后台接收到数据" + data);
                }
                if (StringUtils.isNotEmpty(data)) {
                    hexFactory.transportData(data);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        //e.printStackTrace();
                    }

                } else {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        //e.printStackTrace();
                    }
                }

            }
        }
    }
}
