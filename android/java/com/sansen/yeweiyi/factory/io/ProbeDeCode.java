package com.sansen.yeweiyi.factory.io;

import com.sansen.yeweiyi.bean.Probe;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;

import org.apache.mina.core.buffer.IoBuffer;

public class ProbeDeCode implements ISanSenCode {

    private static final String TAG = "探针解析类";

    // True 解析成功 并且没有断包粘包
    // false 解析失败 有断包粘包

    @Override
    public byte[]  encode(byte commd, byte[] random, byte[] data) {
        return null;
    }

    @Override
    public boolean decode(IoBufferManager manager, IoBuffer in) {
        int startPosition = in.position();

        while (in.hasRemaining()) {
            byte b = in.get();

            if (DataUtils.byteToHexString(b).equalsIgnoreCase("FB")) {
                startPosition = in.position() - 1;
                //Log.i(TAG, "decode: 剩余的个数"+in.remaining());
                if (in.remaining()<=0){
                    return false;
                }
                byte c = in.get();
                if (DataUtils.byteToHexString(c).equalsIgnoreCase("FB")) {
                    in.mark();

                    //
                    if (in.remaining() >= 27) {
                        //FB FB B1 00 01 59 30 36 39 36 2E 30 39 53 30 30 30 30 2E 30 30 2B 30 31 37 2E 33 34 50
                        //对数据进行解码
                        //FB FB
                        // B1  命令字
                        // 00 01  探针号
                        // 59 30 36 39 36 2E 30 39
                        // 53 30 30 30 30 2E 30 30
                        // 2B
                        // 30 31 37 2E 33 34
                        // 50  crc
                        //读取命令字
                        CRCheck crCheck = CRCheck.getInstance();
                        byte[] content = new byte[26];
                        in.get(content);
                        //Log.i(TAG, "decode: 需要解析的探针数据"+DataUtils.bytesToHexString(content));
                        byte crc = crCheck.calculate_crc8_maxim(content, content.length);
                        if (crc == in.get()) {
                            in.reset();
                            try {
                                byte[] byte7 = new byte[7];
                                Probe probe = new Probe();
                                probe.setCommand(DataUtils.byteToHexString(in.get()));
                                in.get();
                                probe.setId(DataUtils.byteToHexString(in.get()));
                                in.get();
                                in.get(byte7);
                                probe.setOilLevel(Double.parseDouble(new String(byte7, "ascii")));
                                in.get();
                                byte7= new byte[7];
                                in.get(byte7);
                                //b1000159303637382e313353303030302e30302b3031352e3933

                                byte7= new byte[7];
                                in.get(byte7);
                                probe.setTemperature(String.valueOf(Double.parseDouble(new String(byte7, "ascii"))));
                                manager.write(probe);
                            } catch (Exception e) {
                                //e.printStackTrace();
                                return true;
                            }

                        }
                    } else {
                        in.position(startPosition);
                        return false;
                    }
                }
            }
        }
        return true;

    }


    private String paseString(byte[] bytes) {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < bytes.length; i++) {
            buffer.append((char) Integer.parseInt(DataUtils.byteToHexString(bytes[i]), 16));
        }
        return buffer.toString();
    }




    void ProbeDecode() {

    }

}
