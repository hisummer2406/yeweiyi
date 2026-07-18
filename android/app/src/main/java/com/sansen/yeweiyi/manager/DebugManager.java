package com.sansen.yeweiyi.manager;

/**
 * Created by PC on 2018-05-15.
 */

public class DebugManager {
    private static DebugManager instance;
    private DebugListener debugListener;


    public static DebugManager getInstance(){
        if (instance==null){
            instance = new DebugManager();
        }
        return instance;
    }

    public DebugManager(){
    }

    public void setDebugListener(DebugListener debugListener){
        this.debugListener =debugListener;
    }

    public synchronized void send(String data){
        if (debugListener!=null){
            debugListener.debugSendListener(data);
        }
    }

    public synchronized void recive(String data){
        if (debugListener!=null){
            debugListener.debugReciveListener(data);
        }
    }


    public interface DebugListener{
        void debugSendListener(String datas);
        void debugReciveListener(String datas);
    }
}
