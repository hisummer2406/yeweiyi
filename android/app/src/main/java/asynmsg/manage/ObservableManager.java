package asynmsg.manage;

import android.util.Log;

import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.DataUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import asynmsg.bean.AsyRecive;
import asynmsg.bean.MyMessage;
import asynmsg.intf.IAsyData;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import serialport.help.SerialPortHelper;

/**
 * Created by PC on 2019-04-09.
 */

public class ObservableManager {
    private static final String TAG = "ObservableManager";

    private ConcurrentHashMap<Integer, AsyRecive> listeners = new ConcurrentHashMap<Integer, AsyRecive>();
    //消息池子 如果里面有数据，就立即将数据发送给串口
    private LinkedBlockingQueue<MyMessage> concurrentLinkedQueue = new LinkedBlockingQueue<MyMessage>();
    private Disposable mDisposable;

    private CompositeDisposable mCompositeDisposable = new CompositeDisposable();
    private SerialPortHelper mainSerialPort;
    private int errorCount;
    private IErrorCodeListener errorListener;
    private UpdateListener updateListener;

    public ObservableManager() {
        Log.i(TAG, "启动了");
        mDisposable = getDisposable();
        mCompositeDisposable.add(mDisposable);
    }

    public void setSerportHelp(SerialPortHelper mainSerialPort) {
        this.mainSerialPort = mainSerialPort;
    }

    long index = 0;

    private Disposable getDisposable() {
        Disposable mObservable = Observable.interval(0, 500, TimeUnit.MILLISECONDS)
                .subscribe(new Consumer<Long>() {
                    @Override
                    public void accept(Long aLong) throws Exception {
//                        Log.i(TAG, "accept: "+aLong);
                        if (SharePreManager.getRelaySwitch()) {
                            for (int key : listeners.keySet()) {
                                AsyRecive recive = listeners.get(key);
                                if (recive != null && recive.createTime != 0 && (System.currentTimeMillis() - recive.createTime) > recive.IntervalTime) {
//                                    Log.i(TAG, "accept: 失败了");
                                    recive.listener.error(key);
                                    listeners.remove(key);
                                    if (errorListener != null) {
                                        errorListener.error(++errorCount);
                                    }
                                }
                            }

                            MyMessage msg = concurrentLinkedQueue.poll();

                            if (msg != null && msg.bytes != null && msg.bytes.length > 0) {
                                sendMsg(msg.bytes);
                                AsyRecive recive = listeners.get(msg.id);
                                if (recive != null && recive.createTime == 0) {
                                    recive.createTime = System.currentTimeMillis();
                                }
                            } else {
                                if (updateListener != null) {
                                    if (index < Long.MAX_VALUE) {
                                        updateListener.update(index++);
                                    } else {
                                        index = 0;
                                    }
                                }
                            }
                        }
                    }
                });
        return mObservable;
    }

    private void sendMsg(byte[] bytes) {
        if (mainSerialPort == null) {
            throw new RuntimeException();
        }
        if (bytes != null && bytes.length > 0 && mainSerialPort.isOpen) {
            mainSerialPort.sendData(bytes);
        }
    }

    public void destory() {
        mCompositeDisposable.remove(mDisposable);
    }

    private void addListener(byte[] key, ObservableManager.ReciveListener listener, int Interval) {
        AsyRecive recive = new AsyRecive();
        recive.listener = listener;
        recive.IntervalTime = Interval;
        if (!listeners.containsKey(DataUtils.byteToShort(key))) {
            listeners.put((int) DataUtils.byteToShort(key), recive);
        }

    }

    public void recive(int key, IAsyData object) {
        AsyRecive recive = listeners.get(key);
        if (recive != null) {
            recive.listener.recive(key, object);
            listeners.remove(key);
            errorCount = 0;
            if (errorListener != null) {
                errorListener.error(0);
            }
        }
    }

    public void addDisposable() {
        mDisposable = getDisposable();
        mCompositeDisposable.add(mDisposable);
    }

    public interface ReciveListener {
        void recive(int id, IAsyData object);

        void error(int id);
    }

    public void sendData(byte[] address, byte[] content, int waitTime, ObservableManager.ReciveListener listener) {
        addListener(address, listener, waitTime);
        addMsgPool(address, content);
    }

    private void addMsgPool(byte[] iden, byte[] content) {
        MyMessage msg = new MyMessage();
        msg.id = DataUtils.byteToShort(iden);
        msg.bytes = content;
        concurrentLinkedQueue.add(msg);

    }

    public interface IErrorCodeListener {
        void error(int error);
    }

    public void setIErrorCodeListener(IErrorCodeListener listener) {
        this.errorListener = listener;
    }

    public interface UpdateListener {
        void update(long aLong);
    }

    public void setUpdateListener(UpdateListener updateListener) {
        this.updateListener = updateListener;
    }
}