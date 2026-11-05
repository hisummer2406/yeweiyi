package com.sansen.yeweiyi.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.sansen.yeweiyi.listener.NetListener;
import com.sansen.yeweiyi.manager.ThingsBoardManager;

/**
 * 设备与 ThingsBoard CE 的接入入口：AIDL 服务外壳不变（{@link IBackService}，
 * {@code MainActivity}/{@code SocketManager} 照旧 bindService 调用），内部实现从
 * 阿里云 Alink 风格 MQTT 换成 {@link ThingsBoardManager} 那一整套 ThingsBoard 原生
 * telemetry/attributes/RPC 逻辑。见 issue #37。
 * <p>
 * 报警上报不经过这里（见 {@code CloudAlarmReporter}）。不再维护的旧能力：
 * 卸油/报警/交班/侧漏等历史记录的 MQTT 拉取。
 */
public class BackService extends Service {

    private static final String TAG = "BackService";

    private ThingsBoardManager thingsBoardManager;
    private NetListener netListener;

    private final IBackService.Stub iBackService = new IBackService.Stub() {
        @Override
        public boolean sendAlarm(String message) throws RemoteException {
            return sendAlarms(message);
        }

        @Override
        public boolean sendUnloadOil(String message) throws RemoteException {
            return sendUnloadOils(message);
        }

        @Override
        public void reloadSocket() throws RemoteException {
            thingsBoardManager.reconnectNow();
        }

        @Override
        public void stopSocket() throws RemoteException {
        }

        @Override
        public boolean isConnect() {
            return thingsBoardManager.isConnected();
        }
    };

    /**
     * 已停用：报警上报改由 {@code CloudAlarmReporter} 在本地报警产生/恢复处直接调用
     * {@link ThingsBoardManager#reportAlarmTransition}，不再经过 AIDL。AIDL 接口方法保留，
     * 只是不再上报，避免同一次报警经新旧两条路径重复发送。
     */
    private boolean sendAlarms(String msgInfo) {
        Log.w(TAG, "sendAlarms 已停用，报警由 CloudAlarmReporter 上报，忽略: " + msgInfo);
        return false;
    }

    private boolean sendUnloadOils(String msgInfo) {
        thingsBoardManager.reportRawEvent("stockEvent", msgInfo);
        return true;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return iBackService;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thingsBoardManager = ThingsBoardManager.getInstance(this);
        thingsBoardManager.start();

        netListener = new NetListener(this);
        netListener.begin(netMobile -> {
            if (netMobile >= 0) {
                thingsBoardManager.reconnectNow();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        netListener.unregisterListener();
        thingsBoardManager.stop();
    }
}
