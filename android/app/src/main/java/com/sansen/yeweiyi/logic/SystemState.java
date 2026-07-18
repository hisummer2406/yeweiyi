package com.sansen.yeweiyi.logic;

/**
 * Created by PC on 2017-12-18.
 */

public class SystemState {
    private static SystemState instance;
    public static SystemState getInstance(){
        if (instance==null){
            instance = new SystemState();
        }
        return instance;
    }

    //true 连接正常  false 连接失败
    private boolean isDriverBoardConnect=true;//驱动板是否连接正常  默认设置为true

    private boolean isNetConnect=true;//网络是否连接正常

    public boolean isDriverBoardConnect() {
        return isDriverBoardConnect;
    }

    public void setDriverBoardConnect(boolean driverBoardConnect) {
        isDriverBoardConnect = driverBoardConnect;
    }

    public boolean isNetConnect() {
        return isNetConnect;
    }

    public void setNetConnect(boolean netConnect) {
        isNetConnect = netConnect;
    }
}
