package serialport.thread;

import serialport.help.SerialPortHelper;
import serialport.manager.IoBufferManager;

/**
 * Created by Administrator on 2019-04-28.
 */


public abstract class IThread extends Thread {
    public boolean isPause;
    public boolean isStop;

    private SerialPortHelper helper;
    private IoBufferManager bufferManager;

    public IThread(SerialPortHelper helper, IoBufferManager bufferManager){
        this.helper =helper;
        this.bufferManager=bufferManager;
    }

    public void pauseThread(){
        isPause=true;
    }

    public void startThread(){
        isPause=false;
    }

    public void stopThread(){
        isStop=true;
    }

}
