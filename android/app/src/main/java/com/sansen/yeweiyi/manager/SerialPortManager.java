package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.BrandBean;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.factory.IParseDataListener;
import com.sansen.yeweiyi.factory.LD2Company;
import com.sansen.yeweiyi.factory.LDCompany;
import com.sansen.yeweiyi.factory.PCDataFactory;
import com.sansen.yeweiyi.factory.VR350Factory;
import com.sansen.yeweiyi.factory.ZhongChengCompany;
import com.sansen.yeweiyi.repository.AlarmRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import serialport.help.SerialPortHelper;

/**
 * 协议编排层：串口/PC/探头三路轮询、厂商协议分发（factory/）、报警联动、经 {@link DataRefreshCallback}
 * 把解析结果回调给 UI 层。不是 HAL——底层字节收发在 {@code serialport.manager.SerialPortManager}
 * （263 行，零 UI 依赖，几乎无调用方）。两者同名易混淆：UI/业务层应始终经本类，禁止直接调用
 * {@code serialport.manager.SerialPortManager}。见 openspec/changes/layer-data-and-hardware-boundaries §8、
 * `CLAUDE.md` "Known landmines"。
 */
public class SerialPortManager implements IParseDataListener {

    private static final String TAG = "SerialPortManager";
    private static SerialPortManager instance;
    // 串口名列表
    public static final String[] m_iSerialPort = {"S4", "S1", "S2",
            "S5"};
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

    /**
     * 串口 232 AMA1 驱动板 硬件接口 RX1 TX1 GND
     */
    public static SerialPortHelper serialPort2 = new SerialPortHelper(
            m_iSerialPort[2], baudrate[4], databits[3], stopbits[0], paritys[0]);

    // 从外部（BaseActivity）传进来的回调，用于把探头数据刷新通知交回 UI 层
    private DataRefreshCallback mDataRefreshCallback;

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
     * 从外面（BaseActivity）接收一个数据刷新回调
     *
     * @param callback
     */
    public void setDataRefreshCallback(DataRefreshCallback callback) {

        if (callback != null) {
            mDataRefreshCallback = callback;
        }
    }

    /**
     * @param context
     */
    private SerialPortManager(Context context) {
        if (SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.isTestStatus, 0) == 0) {
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
    }


    private void initSerialPort() {


    }


    private void initThread() {
        // 访问Pc
        PCDataFactory pcDataFactory = null;
        //Log.i(TAG, "initThread:    " + SharePreManager.getBackstage());
        if (VR350Factory.VR350R.equals(SharePreManager.getBackstage())) {
            VR350 = new SerialPortHelper(m_iSerialPort[3], baudrate[4],
                    databits[3], stopbits[0], paritys[0]);
            pcPortThread = new PCPortThread("ReceiveThread1", VR350);
            pcDataFactory = new VR350Factory(mContext, pcPortThread);

        }
        serialPortS2 = new SerialPortHelper(m_iSerialPort[2], baudrate[4],
                databits[3], stopbits[0], paritys[0]);
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
    }

    class RequestThread extends Thread {
        private SerialPortHelper serialPort;
        private String name;
        // 42（ChangDu/传感器长度）请求节流计数器，跟温度请求（见 wenDuTurnPos）无关（原名 isWenDu）。
        int changDuThrottle = 0;
        // 温度轮询指针，存罐位号（SparseArray key）。替代原先共享计数器巧合命中
        // 的机制（issue #28）。初值 -1，保证首次使用时自动定位到第一个真实罐位。
        private int wenDuTurnPos = -1;
        private boolean isGetChangDu=true;
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
                        if (isStopProbe) {
                            mOilCanData = mOilCanDataManager.getData();// 保证每一次获取的数据为最新
                            // 由这里把新的信息分发到各个页面
                            // 按 SparseArray 实际条目遍历（valueAt），不假设罐位号连续编号——
                            // 罐没配置罐高时不在数组里，编号会有空洞，旧写法一撞到空洞就 break，
                            // 之后所有罐都轮询不到（代码审查发现，比 issue #28 本身更严重）。
                            for (j = 0; j < mOilCanData.size(); j++) {
                                if (mOilCanData != null) {
                                    try {
                                        mOilCan = mOilCanData.valueAt(j);
                                        if (mOilCan == null) {
                                            continue;
                                        }
                                    } catch (Exception e) {
                                        break;
                                    }
                                }
                                if (isStopProbe) {
                                    // 谁轮到就在这个罐的 i==0 问温度。size 只取一次避免取模除零
                                    // （UI线程的refresh()可能并发清空数组）；用 key 而不是数值算术
                                    // 推进，罐位号有洞时也不会永久卡死——indexOfKey 找不到就自动
                                    // 重新定位到第一个真实罐位（代码审查发现）。
                                    boolean isMyWenDuTurn = false;
                                    int oilCanCount = mOilCanData.size();
                                    if (oilCanCount > 0) {
                                        int wenDuTurnIndex = mOilCanData.indexOfKey(wenDuTurnPos);
                                        if (wenDuTurnIndex < 0) {
                                            wenDuTurnPos = mOilCanData.keyAt(0);
                                            wenDuTurnIndex = 0;
                                        }
                                        isMyWenDuTurn = (mOilCan.getOilCanPos() == wenDuTurnPos);
                                        if (isMyWenDuTurn) {
                                            wenDuTurnPos = mOilCanData.keyAt((wenDuTurnIndex + 1) % oilCanCount);
                                        }
                                    }
                                    for (int i = 0; i < 5; i++) { //0x01,0x06,0x00,0x01,0x00,0x02修改波特率
                                        switch (mOilCan.getProbeType()) {
                                            case 0:
                                                Log.e("TAG探针地址",i+"===");
                                                if (i == 0 && isMyWenDuTurn) {
                                                    oilCanData = ZhongChengCompany.getInstance().sendWenDuInfo("FEFE", mOilCan.getProbeAdr(), mContext);
                                                } else if (changDuThrottle % 10 == 1) {
                                                    oilCanData = ZhongChengCompany.getInstance().sendChangDuInfo("FEFE", mOilCan.getProbeAdr(), mContext);
                                                } else {
                                                    oilCanData = ZhongChengCompany.getInstance().sendProbeInfo("FEFE", mOilCan.getProbeAdr(), mContext);
                                                }
                                                changDuThrottle++;
                                                if (changDuThrottle > 100) {
                                                    changDuThrottle = 0;
                                                }
                                                if (isGetChangDu) {
                                                    isGetChangDu = false;

                                                }
                                                break;
                                            case 1:
                                                oilCanData = LDCompany.getInstance().sendProbeInfo("", mOilCan.getProbeAdr(), mContext);
                                                //  oilCanData = RouXingCompany.getInstance().sendProbeInfo("04", mOilCan.getProbeAdr(),mContext);
                                                break;

                                            case 2:
                                                oilCanData = LD2Company.getInstance().sendProbeInfo("", "02", mContext);
                                                //  oilCanData = RouXingCompany.getInstance().sendProbeInfo("04", mOilCan.getProbeAdr(),mContext);
                                                break;
                                        }
                                        serialPort.sendData(DataUtils.HexString2Bytes(oilCanData));
                                Log.i(TAG, "发送到串口数据" + oilCanData);
                                        try {
                                            Thread.sleep(50);
                                        } catch (InterruptedException e1) {
                                            // 注：此处未调用 Thread.currentThread().interrupt() 恢复中断位。
                                            // 恢复后本轮循环内后续的 sleep 会立即抛异常，属于行为变更，
                                            // 需在真机上验证停机流程后再定。当前仅记录，便于排查。
                                            Log.w(TAG, "发送后等待被中断, 油罐位=" + mOilCan.getOilCanPos(), e1);
                                        }

                                        data = null;
                                        byte[] bytes = serialPort.receiveData();

                                        if (bytes != null) {
                                            data = DataUtils.bytesToHexString(bytes, bytes.length);
                                        }
                                        if (StringUtils.isNotEmpty(data)) {

                                            // golden master 录制入口（默认关闭）。录在这里而不是更上游的
                                            // serialPort.receiveData()，因为 data 才是 probeInfoPase 真正吃进去的东西。
                                            ProbeTrafficRecorder.recordRequest(data, mOilCan.getProbeType(), mOilCan.getOilCanPos());

                                            switch (mOilCan.getProbeType()) {
                                                case 0:
                                                    try {
                                                        ProbeTrafficRecorder.setActiveParser("ZhongChengCompany");
                                                        isRight = ZhongChengCompany
                                                                .getInstance()
                                                                .probeInfoPase(data, SerialPortManager.this);

                                                    } catch (Exception e) {
                                                        Log.e(TAG, "中诚协议解析失败, 油罐位=" + mOilCan.getOilCanPos()
                                                                + " 原始报文=" + data, e);
                                                    }
                                                    break;
                                                case 1:
                                                    try {
                                                        ProbeTrafficRecorder.setActiveParser("LDCompany");
                                                        isRight = LDCompany
                                                                .getInstance()
                                                                .probeInfoPase(data, SerialPortManager.this);
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "LD协议解析失败, 油罐位=" + mOilCan.getOilCanPos()
                                                                + " 原始报文=" + data, e);
                                                    }
                                                    // 2026-08-15 补上此处原先漏写的 break。
                                                    // 漏写时执行会穿透到 case 2，同一帧 LD 报文再被 LD2Company 解析一次。
                                                    // 后果不止是"LD 结果被覆盖"：LD2Company 不校验 CRC（只判 bytes.length > 6），
                                                    // LD 的 9 字节应答直接通过；它按 decimal = bytes[2]/2 循环（LD 应答的字节
                                                    // 计数位是 4，故循环 2 次），并按通道号 "0"+(i+1) 匹配油罐 —— 于是 LD 探头
                                                    // 的正常应答会被当作两路 4-20mA 通道值，写到探针地址 01、02 的油罐上。
                                                    // 详见 docs/重构方案.md 8.2 与 issue #5。
                                                    break;
                                                case 2:
                                                    try {
                                                        ProbeTrafficRecorder.setActiveParser("LD2Company");
                                                        isRight = LD2Company
                                                                .getInstance()
                                                                .probeInfoPase(data, SerialPortManager.this);
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "LD2协议解析失败, 油罐位=" + mOilCan.getOilCanPos()
                                                                + " 原始报文=" + data, e);
                                                    }
                                                    break;
                                                default:
                                                    // 配了一个没有解析器的探头类型。此前静默无效，现在留下可定位的记录 ——
                                                    // 无人值守设备上"配错探头类型"与"探头没接上"的现象都是没有读数。
                                                    // 只记日志：不设置 isRight、不抛异常、不影响后续 crcError() 调用。
                                                    Log.w(TAG, "未知探头类型 probeType=" + mOilCan.getProbeType()
                                                            + ", 油罐位=" + mOilCan.getOilCanPos()
                                                            + ", 本次不解析。请核对探针配置表。");
                                                    break;
                                            }
//                                    Log.i(TAG, "串口否判断正常" + mOilCan.getErrorTimes() + "  " + isRight);
                                            if (isRight) {
                                                mOilCan.setErrorTimes(0);
//                                        Log.i(TAG, "串口数据校验成功: "+mOilCan.getErrorTimes()+"==="+data);
                                                if (MyApplication.alarmStates.containsKey(mOilCan.getOilCanPos()) && MyApplication.alarmStates.get(mOilCan.getOilCanPos()).getConnectError() == 1) {
                                                    MyApplication.alarmStates.get(mOilCan.getOilCanPos()).setConnectError(0);
                                                }
                                                // i==0 问的是温度，这次成功不代表油位/长度也问到了，不能
                                                // 直接break结束这一轮；也不用continue——同一根探针刚发完
                                                // 45温度应答，若零间隔紧接着收到下一条44请求，RS-485总线
                                                // 来不及做收发切换，这条新请求很可能被探针错过（issue #28），
                                                // 所以要让下面的 crcError()+sleep(50) 照常跑出这段间隔。
                                                if (!(mOilCan.getProbeType() == 0 && i == 0 && isMyWenDuTurn)) {
                                                    break;
                                                }
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
                                            Log.w(TAG, "轮询间隔等待被中断, 油罐位=" + mOilCan.getOilCanPos(), e);
                                        }
                                    }

                                }
                            }
                        } else {
                            byte[] bytes = serialPort.receiveData();
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

                AlarmRepositoryImpl.getInstance().saveAlarm(oilMessage.getOilCanId(), oilMessage);
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
        // golden master 录制出口（默认关闭）。probeInfoPase 是同线程同步回调，
        // 因此同一线程内最近一条 IN 记录就是本条 OUT 的输入，无需额外关联 id。
        ProbeTrafficRecorder.recordParsed(oilCan);
        // 每次更新完毕 都要去更新 oildataMagager
        mOilCanDataManager.refreshData(oilCan);

        if (mDataRefreshCallback != null) {
            mDataRefreshCallback.onDataRefresh(oilCan);
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
        if (mDataRefreshCallback != null) {
            mDataRefreshCallback.onDataRefresh(oilCan);
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
                   Log.i(TAG, "run: 后台接收到数据" + data);
                }
                if (StringUtils.isNotEmpty(data)) {
                    hexFactory.transportData(data);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Log.w(TAG, "后台接收线程等待被中断(有数据分支)", e);
                    }

                } else {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Log.w(TAG, "后台接收线程等待被中断(无数据分支)", e);
                    }
                }

            }
        }
    }
}
