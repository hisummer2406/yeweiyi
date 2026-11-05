package serialport.manager;

import android.util.Log;

import com.sansen.yeweiyi.bean.BaseInfo;
import com.sansen.yeweiyi.sensor.SenSorInfo;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;

import serialport.code.ISanSenBaseCode;
import serialport.code.ISanSenCode;

public class SensorCode implements ISanSenBaseCode {

    private static final String TAG = "QCode";

    void ProbeDecode() {

    }

    @Override
    public byte[] encode(byte address) {

        byte[] bytes = new byte[6];
        bytes[0] = address;
        bytes[1] = 0x03;
        bytes[2] = 0x00;
        bytes[3] = 0x04;
        bytes[4] = 0x00;
        bytes[5] = 0x01;
        byte[] crcByte = CRCheck.getInstance().getModBusCRCByte(bytes);
        byte[] content = new byte[8];
        System.arraycopy(bytes, 0, content, 0, bytes.length);
        content[6] = crcByte[1];
        content[7] = crcByte[0];
        System.out.println(DataUtils.bytesToHexString(content, content.length));
        return content;
    }

    @Override
    public boolean decode(byte[] data) {
// 01 03 02 00 88 B8 22

        if (data.length == 7) {
            byte[] content = new byte[data.length - 2];
            System.arraycopy(data, 0, content, 0, content.length);
            byte[] crc = CRCheck.getInstance().getModBusCRCByte(content);
            if (crc[0] == data[data.length - 1] && crc[1] == data[data.length - 2]) {
                Log.i(TAG, "decode: CRC校验正常");
                byte[] value = new byte[2];
                System.arraycopy(content, 3, value, 0, value.length);
                int result = DataUtils.byte2Short(value);
                SenSorInfo.getInstance().pressure.set(DoubleUtil.double2Str(result / 100f));
                Log.i(TAG, "decode: 结果数据:" + SenSorInfo.getInstance().pressure.get());
                return true;
            } else {
                Log.i(TAG, "decode: CRC校验失败");
            }
        }
        return false;
    }
}