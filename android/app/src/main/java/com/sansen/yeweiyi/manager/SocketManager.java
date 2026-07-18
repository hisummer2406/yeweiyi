package com.sansen.yeweiyi.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.service.IBackService;

import java.util.HashMap;
import java.util.List;

public class SocketManager {
    private static final String TAG = "SocketManager";
    private Intent mServiceIntent;
    private Context ct;
    private IBackService iBackService;
    public static boolean isInitialization;

    private static SocketManager mInstance;
    public static HashMap<String, List<OilMessage>> hashMap = new HashMap<String, List<OilMessage>>();

    private SocketManager(Context ct) {
        if (this.ct == null) {
            this.ct = ct;
        }
        initData();
    }

    public void initData() {
        try {
            dataManager = OilcanDataManager.getInstance(ct);
//            mServiceIntent = new Intent(ct, BackService.class);
//            ct.bindService(mServiceIntent, conn, Context.BIND_AUTO_CREATE);
//            mServiceIntent = new Intent(ct, UdpService.class);
//            ct.bindService(mServiceIntent, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            //e.printStackTrace();
        }
    }


    public static SocketManager getInstance(Context ct) {
        if (mInstance == null) {
            mInstance = new SocketManager(ct);
        }
        return mInstance;
    }

    public static void Destory() {
       mInstance.stopSocket();
    }

    public boolean sendAlarm(String str) {
        if (iBackService!=null) {
            try {
                return iBackService.sendAlarm(str);
            } catch (RemoteException e) {
                //e.printStackTrace();
            }
        }
        return false;
    }
    public boolean sendUnloadOil(String str) {
        if (iBackService!=null) {
            try {
                return iBackService.sendUnloadOil(str);
            } catch (RemoteException e) {
                //e.printStackTrace();
            }
        }
        return false;
    }


    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 断开连接
            iBackService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // 连接成功
            System.out.println("服务连接成功");
            iBackService = IBackService.Stub.asInterface(service);
        }
    };

    private OilcanDataManager dataManager;
    private boolean is = true;

    public void stopSocket() {
        if (iBackService!=null) {
            try {
                iBackService.stopSocket();
            } catch (RemoteException e) {
                // TODO Auto-generated catch block
                //e.printStackTrace();
            }
        }
    }

    public void ReLoadSocket() {
        if (iBackService!=null) {
            try {
                iBackService.reloadSocket();
            } catch (RemoteException e) {
                //e.printStackTrace();
            }
        }
    }

    private DataResolveListener dataResolveListener;

    public void setDataResolveListener(DataResolveListener dataResolveListener) {
        this.dataResolveListener = dataResolveListener;
    }

    public interface DataResolveListener {
    }
}
