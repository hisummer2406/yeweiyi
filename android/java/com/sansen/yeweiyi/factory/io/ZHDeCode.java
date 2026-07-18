package com.sansen.yeweiyi.factory.io;

import android.util.Log;

import com.sansen.yeweiyi.structbean.StructData;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;

import org.apache.mina.core.buffer.IoBuffer;

/**
 * Created by PC on 2018-07-26.
 */

public class ZHDeCode implements ISanSenCode {
    private static final String TAG = "中石化协议解析类";

   // FA FA FA 0A 68 89 20 00 0B 34 36 35 34 36 34 36 31 32 34 36 74 FB FB
    @Override
    public byte[] encode(byte commd, byte[] random, byte[] json) {
        int length=12+json.length;
        byte [] head={(byte) 0xFA, (byte) 0xFA, (byte) 0xFA};
        byte [] data = new byte[length];
        System.arraycopy(head,0,data,0,head.length);
        data[3] = commd;
        System.arraycopy(random,0,data,4,2);

        byte [] jsonlen=DataUtils.shortToByte((short) json.length);

        byte jsonLenCRC=CRCheck.getInstance().calculate_crc8_maxim(jsonlen,2);

        Log.i("中石化  发送的长度",DataUtils.bytesToHexString(jsonlen)+"  "+json.length);
        System.arraycopy(jsonlen,0,data,6,2);
        data[8]=jsonLenCRC;
        System.arraycopy(json,0,data,9,json.length);
        int crcContLen=6+json.length;
        byte[]crcContent =new byte[crcContLen];
        System.arraycopy(data,3,crcContent,0,crcContLen);
        byte crc=CRCheck.getInstance().calculate_crc8_maxim(crcContent,crcContLen);
        data[json.length+9]=crc;
        data[json.length+10]= (byte) 0xFB;
        data[json.length+11]= (byte) 0x0A;
        return data;
    }
    int count;
    @Override
    public boolean decode(IoBufferManager manager, IoBuffer in) {
        int startPosition;
        try {
            while (in.hasRemaining()) {
                byte b = in.get();
                if (b == (byte) 0xFA) {
                    startPosition = in.position() - 1;
                    b = in.get();
                    if (b == (byte) 0xFA) {
                        b = in.get();
                        if (b == (byte) 0xFA) {
                            in.mark();
                            byte commd = in.get();
                            byte[] random = new byte[2];
                            in.get(random);
                            byte[] len = new byte[2];
                            in.get(len);
                            byte crcLen = CRCheck.getInstance().calculate_crc8_maxim(len, 2);
                            if (crcLen != in.get()) {
                                Log.i("中石化", "长度CRC失败");
                                return true;
                            }
                            int jsonLen = DataUtils.byte2Short(len);
                            Log.i("中石化  现在长度", in.limit() + "");
                            Log.i("中石化", "解析数据长度  " + jsonLen);
                            if (in.remaining() < jsonLen + 3) {
                                Log.i("中石化", "断包");
                                in.position(startPosition);
                                if (count == 0) {
                                    count++;
                                    return true;
                                }
                                return false;
                            }
                            byte[] jsonByte = new byte[jsonLen];
                            in.get(jsonByte);
                            //Log.i("中石化 json Byte[]",DataUtils.bytesToHexString(jsonByte,jsonLen));
                            //Log.i("中石化 json",new String(jsonByte));
                            byte crc = in.get();
                            in.reset();
                            byte[] crcContent = new byte[6 + jsonLen];
                            in.get(crcContent);
                            byte crcResut = CRCheck.getInstance().calculate_crc8_maxim(crcContent, crcContent.length);
                            Log.i("中石化 CRC", DataUtils.byteToHexString(crc) + "  " + DataUtils.byteToHexString(crcResut));
                            if (crcResut == crc) {
                                Log.i("中石化 CRC", "校验通过  " + in.position());
                                StructData structData = new StructData();
                                structData.setData(jsonByte);
                                structData.setCommd(commd);
                                structData.setRandom(random);
                                manager.write(structData);
                                in.position(in.position() + 3);
                                return true;
                            } else {
                                Log.i("中石化 CRC", "校验失败");
                            }
                        }
                        return true;
                    }
                    return true;
                }
            }
        }catch (Exception e){
            return true;
        }
        Log.i("中石化 CRC","走完了");
        return true;
    }
}
