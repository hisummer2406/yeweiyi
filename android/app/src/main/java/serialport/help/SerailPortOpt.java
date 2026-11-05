package serialport.help;

import android.util.Log;

import com.sansen.yeweiyi.utils.DataUtils;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import serialport.jni.SerialPortJNI;

/**
 * 调用JNI的串口
 *
 * @author dwin
 *
 */
public class SerailPortOpt extends SerialPortJNI {

    private static final String TAG = "SerialPort";

    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    public FileDescriptor openDev(String devNum) {
        super.mFd = super.openDev(devNum);
        if (super.mFd == null) {
            //Log.i(TAG, "native open returns null");
            return null;
        }
        mFileInputStream = new FileInputStream(super.mFd);
        mFileOutputStream = new FileOutputStream(super.mFd);
        return super.mFd;
    }


    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    public int setSpeed(FileDescriptor optFd, int speed) {
        return super.setSpeed(optFd, speed);
    }

    public int setParity(FileDescriptor optFd, int databits, int stopbits,
                         int parity) {
        return super.setParity(optFd, databits, stopbits, parity);
    }

    public int closeDev(FileDescriptor optFd) {
        int retStatus;
        retStatus = super.closeDev(optFd);
        super.mFd = null;
        return retStatus;
    }

    public int readBytes(FileDescriptor fd, byte[] buffer, int length) {
        return super.readBytes(fd, buffer, length);
    }

    public boolean writeBytes(FileDescriptor fd, byte[] buffer, int length) {
        return super.writeBytes(fd, buffer, length);
    }

    public int readBytes(FileDescriptor fd, byte[] buffer) {
        return super.readBytes(fd, buffer, buffer.length);
    }

    public boolean writeBytes(FileDescriptor fd, byte[] buffer) {
        return super.writeBytes(fd, buffer, buffer.length);
    }

    public int readBytes(byte[] buffer) {
        return super.readBytes(mFd, buffer, buffer.length);
    }

    public boolean writeBytes(byte[] buffer) {
//		Log.i(TAG, "writeBytes2: "+ DataUtils.bytesToHexString(buffer,buffer.length));
        /**
         * 错误啊
         */
		return super.writeBytes(mFd, buffer, buffer.length);
    }
}
