package serialport.jni;

import java.io.FileDescriptor;

/**
 * Created by Administrator on 2019-05-05.
 */

public class SerialPortJNI {
    static {
        System.loadLibrary("serialport");
    }

    public FileDescriptor mFd;

    public String mDevNum;
    public int mSpeed;
    public int mDataBits;
    public int mStopBits;
    public int mParity;

    public int RS485ModFp = -1;

    public static int RS485Read = 0;
    public static int RS485Write = 1;

    public  native int setSpeed(FileDescriptor fd, int speed);

    // 通过FD设置数据位，停止位以及校验位
    public native int setParity(FileDescriptor fd, int dataBits, int stopBits,
                                int parity);

    // 通过串口的名字得到FD
    public native FileDescriptor openDev(String devNum);

    public native FileDescriptor open485Dev(String devNum);

    // 通过FD关闭设备
    public native int closeDev(FileDescriptor fd);

    public native int close485Dev(FileDescriptor fd);

    // 将缓存数组传给jni，然后对数组进行赋值
    public native int readBytes(FileDescriptor fd, byte[] buffer, int length);

    // 通过FD设置缓存数组跟长度
    public native boolean writeBytes(FileDescriptor fd, byte[] buffer,
                                     int length);

    // 设置某一设备的模式
    public native int set485mod(int mode);
}
