package serialport.help;

import android.content.Context;
import android.util.Log;

import com.sansen.yeweiyi.utils.DataUtils;

import java.io.FileDescriptor;
import java.io.InputStream;

/**
 * Created by Administrator on 2019-04-28.
 */

public class SerialPortHelper {
    public static final String TYPE_HEX = "HEX";
    private static final String TAG = "SerialPortHelper";

    Context context;

    /**
     * 自定义串口对象
     */
    private static SerialPortHelper serialPort;

    /**
     * 调用JNI的串口
     */
    private SerailPortOpt serialportopt;

    /**
     * 读取数据线程
     */

    /**
     * 串口接收到的流
     */
    private InputStream mInputStream;

    /**
     * 用来判断串口是否已打开
     */
    public boolean isOpen = false;

    /*
     * 接收到的数据
     */
    String data;

    /**
     * 实例化并打开串口对象
     *
     * @param devNum   串口号 S0，S1，S2，S3,S4
     * @param dataBits 数据位
     *                 起始位之后就是传送数据位。数据位一般为8位一个字节的数据（也有6位、7位的情况），低位（LSB）在前，高位（MSB）在后。
     * @param speed    波特率 在串行通信中，数据是按位进行传送的，因此传送速率用每秒钟传送数据位的数目来表示，称之为波特率。
     * @param stopBits 停止位 停止位在最后，用以标志一个字符传送的结束，它对应于逻辑1状态。
     * @param parity   校验位 可以认为是一个特殊的数据位。校验位一般用来判断接收的数据位有无错误，一般是奇偶校验。在使用中，该位常常取消。
     */
    public SerialPortHelper(String devNum, int speed, int dataBits,
                            int stopBits, int parity) {
        serialportopt = new SerailPortOpt();
        boolean isopen = openSerial(devNum, speed, dataBits, stopBits, parity);

    }


    /**
     * 打开串口时传入参数，可以指定打开某个串口，并设置相应的参数
     *
     * @param devNum   串口号 COM0，COM1，COM2，COM3,COM4
     * @param dataBits 数据位
     * @param speed    波特率
     * @param stopBits 停止位
     * @param parity   校验位
     * @return
     */
    private boolean openSerial(String devNum, int speed, int dataBits,
                               int stopBits, int parity) {
        serialportopt.mDevNum = devNum;
        serialportopt.mDataBits = dataBits;
        serialportopt.mSpeed = speed;
        serialportopt.mStopBits = stopBits;
        serialportopt.mParity = parity;

        // 打开串口
        FileDescriptor fd = serialportopt.openDev(devNum);

        if (fd == null) {
            //Log.i(TAG, "openSerial: 打开串口失败 校验位："+parity+devNum+speed+dataBits);
            return false;// 串口打开失败

        } else {
            // 设置串口参数
            serialportopt.setSpeed(fd, speed);
            serialportopt.setParity(fd, dataBits, stopBits, parity);
            mInputStream = serialportopt.getInputStream();
            isOpen = true;
            //Log.i(TAG, "openSerial: 打开串口成功 校验位："+parity+devNum+speed+dataBits);
            return true;
        }
    }

    /**
     * 关闭串口
     */
    public void closeSerial() {

        if (serialportopt.mFd != null) {
            serialportopt.closeDev(serialportopt.mFd);
            isOpen = false;
        }
    }


    /**
     * 发送数据
     */
    public synchronized void sendData(byte[] datas) {
       Log.i(TAG, "串口:" + serialportopt.mDevNum + "\n发送到串口中的数据: " + DataUtils.bytesToHexString(datas, datas.length));
        try {
//            出错了

            serialportopt.writeBytes(datas);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    /**
     * 接收数据
     *
     * @return 接收到的字符串
     */
    public synchronized byte[] receiveData() {
        if (mInputStream == null) {
            return null;
        }
        byte[] buf = new byte[1024];
        int size = serialportopt.readBytes(buf);
        if (size > 0) {
            byte[] bytes = new byte[size];
            System.arraycopy(buf, 0, bytes, 0, size);
         //  Log.e(TAG, "串口中接收的数据: " + DataUtils.bytesToHexString(bytes, bytes.length));
//            if (AsyManager.getInstance().listence != null && bytes != null && bytes.length > 0) {
//                AsyManager.getInstance().listence.reciveData(bytes);
//                Log.i("YMODE 接收到数据",DataUtils.bytesToHexString(buf,buf.length));
//            }





            return bytes;
        }
        return null;
    }


}
