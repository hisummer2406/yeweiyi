package serialport.manager;

import android.util.Log;

import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;

import asynmsg.manage.AsyManager;
import serialport.buff.IoBuffer;
import serialport.code.ISanSenCode;

public class QCode implements ISanSenCode {

    private static final String TAG = "QCode";

    @Override
    public synchronized byte[] encode(byte chainNo, byte commd, byte[] content) {
        int len = content.length + 3;
        byte[] bytes = new byte[len];
        bytes[0] = commd;
        bytes[1] = chainNo;
        bytes[2] = (byte) content.length;
        System.arraycopy(content, 0, bytes, 3, content.length);
        Log.i(TAG, "encode: Bytes："+ DataUtils.bytesToHexString(bytes,bytes.length));
        byte[] crc = CRCheck.getInstance().getCRC16IBM(bytes);

        byte [] value = new byte[bytes.length+5];
        value[0] = (byte) 0xFB;
        value[1] = (byte) 0xFB;
        value[2] = (byte) 0xFB;

        System.arraycopy(bytes, 0, value, 3, bytes.length);
        value[value.length - 2] = crc[1];
        value[value.length - 1] = crc[0];
       // Log.i(TAG,"驱动板发送数据："+DataUtils.bytesToHexString(value,value.length));
        return value;
    }

    @Override
    public boolean decode(IoBufferManager manager, IoBuffer in) {
        int startPosition = in.position();
        in.mark();
        int limit = in.remaining();
        byte[] bytes = new byte[limit];
        in.get(bytes);
        Log.i(TAG, "驱动板接受数据: " + DataUtils.bytesToHexString(bytes, bytes.length));
        in.reset();

        while (in.hasRemaining()) {
            if (in.remaining() > 6) {
                if (in.get() == (byte) 0xFB) {
                    if (in.get() == (byte) 0xFB) {
                        if (in.get() == (byte) 0xFB) {
                            in.mark();
                            byte commd = in.get();
                            int chaind = in.get() & 0xFF;
                            byte len = in.get();
                            if (len<0){
                                return true;
                            }
                            Log.i(TAG, "decode: 链号："+chaind+"命令"+DataUtils.bytesToHexString(commd)+" 长度："+(int)len);
                            if (in.remaining() > (int) len + 2) {
                                in.reset();
                                byte[] content = new byte[3 + len];
                                in.get(content);
                                Log.i(TAG, "decode: 要校验crc的值"+DataUtils.bytesToHexString(content,content.length));
                                byte[] crc = CRCheck.getInstance().getCRC16IBM(content);
                                byte[] crc2 = new byte[2];
                                in.get(crc2);
                                Log.i(TAG,DataUtils.bytesToHexString(crc,crc.length)+" "+DataUtils.bytesToHexString(crc2,crc2.length));
                                if (crc[0] == crc2[1] && crc[1] == crc2[0]) {
                                    Log.i(TAG, "decode: CRC校验正确");
                                    bytes = new byte[len];
                                    System.arraycopy(content, 3, bytes, 0, bytes.length);
                                    QuDongBan data = new QuDongBan();
                                    data.setCommd(commd);
                                    data.setData(bytes);
                                    data.setChainNo(chaind);
                                    manager.write(data);
                                    return true;
                                } else {
                                    Log.i(TAG, "decode: CRC校验失败");
                                    return true;
                                }
                            }
                        }
                    }
                }
            } else {
                in.position(startPosition);
                return false;
            }
        }
        return false;
    }

}
