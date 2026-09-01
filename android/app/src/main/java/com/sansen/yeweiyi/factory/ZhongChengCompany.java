package com.sansen.yeweiyi.factory;

import android.content.Context;
import android.util.Log;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Created by PC on 2018-05-23.
 */

public class ZhongChengCompany implements AbstractCompany {
    private static final String TAG = "YLBSCompany";
    private static ZhongChengCompany instance;
    private double oilVolume;
    private double canAddOilWeight;

    private ZhongChengCompany() {
    }


    @Override
    public String sendProbeInfo(String command, String probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
        // sb.append(command);
        sb.append(probeaddress);
        sb.append("03");
        sb.append("440000");
        String crc = getString(CRCheck.crc12(sb.toString().length(), DataUtils.HexString2Bytes(sb.toString())));
        sb.append(crc);
        // System.out.println("CRC:" + crc+"---"+sb.toString());
        return "FEFE" + sb.toString();
    }

    @Override
    public String sendChangDuInfo(String command, String Probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
       // sb.append(command);
        sb.append(Probeaddress);
        sb.append("03420000");
        String crc = getString(CRCheck.crc12(sb.toString().length(), DataUtils.HexString2Bytes(sb.toString())));
        sb.append(crc);
       // System.out.println("CRC:" + crc + "---" + sb.toString());
        return "FEFE" + sb.toString();
    }
    @Override
    public String sendWenDuInfo(String command, String Probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
        //sb.append(command);
        sb.append(Probeaddress);
        sb.append("03");
        sb.append("450000");
        String crc = getString(CRCheck.crc12(sb.toString().length(), DataUtils.HexString2Bytes(sb.toString())));
        sb.append(crc);
        return "FEFE" + sb.toString();
    }

    public String getString(String data) {
        while (data.length() < 4) {
            data = 0 + data;
        }
        return data;
    }

    short crc;
    byte[] byte2 = new byte[2];
    OilCan oilCan;
    int oilRoate;
    double Changdu;

    /**
     * 校验帧尾 2 字节 CRC——覆盖范围是去掉开头 FEFE 头、去掉结尾 2 字节校验码之后的全部字节。
     * 算法用真实协议报文核对过（issue #28 排查记录）：查询帧（sendProbeInfo/sendWenDuInfo
     * 用的 CRCheck.crc12）和 44/45 应答帧的校验码，用同一套 crc12 都能精确复算出来。
     * 总线冲突/噪声拼出的坏帧，即便长度和功能码凑巧对上、地址字节凑巧等于某个真实探针地址，
     * CRC 对不上的概率也有 65535/65536——这是堵住 issue #28 里"孤立一次误报警"的关键一环。
     */
    static boolean isCrcValid(byte[] bytes) {
        if (bytes == null || bytes.length < 5) {
            return false;
        }
        byte[] payload = new byte[bytes.length - 4];
        System.arraycopy(bytes, 2, payload, 0, payload.length);
        String expected = String.format("%02X%02X", bytes[bytes.length - 2] & 0xFF, bytes[bytes.length - 1] & 0xFF);
        String computed = CRCheck.crc12(payload.length, payload);
        return computed.equalsIgnoreCase(expected);
    }

    /** 从下标2开始找下一个 FEFE 帧头，判断后面是否还粘着另一条帧。找不到返回 -1。 */
    private static int indexOfNextFrameHeader(byte[] bytes) {
        for (int i = 2; i + 1 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0xFE && (bytes[i + 1] & 0xFF) == 0xFE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean probeInfoPase(String data, IParseDataListener parseDataListener) {
        data = data.replace(" ", "");
        return parseFrame(DataUtils.HexString2Bytes(data), parseDataListener);
    }

    /** 中成协议里已知的三种合法帧长度（44/45/42 应答），供 parseFrame 优先按长度切分用。 */
    private static final int[] KNOWN_FRAME_LENGTHS = {19, 17, 14};

    /**
     * 解析一段字节，其中可能包含一条或多条完整帧首尾相接——串口应答偶尔会晚到，
     * 跟下一条请求的应答被一起读出来拼成一整块（issue #28）。整体 CRC 校验不过时，
     * 优先按已知的合法帧长度（19/17/14）试切开头一段，通过 CRC 就当成一条完整帧，
     * 剩余部分递归处理。这样比"盲目找下一个 FEFE 帧头"更可靠：如果残留帧自己的
     * 最后一字节恰好是 0xFE，紧跟着真实帧头的两个 0xFE，会拼出三个连续的 0xFE，
     * 找帧头会在真实帧头前一位就误切一刀，且递归时固定从下标2开始扫描（为跳过
     * "本段自己的帧头"），真实帧头永远扫不到，两段都会丢——这个反例已用构造字节
     * 验证过（代码审查发现，概率约1/256）。长度都试不出来时才退回找帧头兜底。
     */
    private boolean parseFrame(byte[] bytes, IParseDataListener parseDataListener) {
        if (isCrcValid(bytes)) {
            return parseValidatedFrame(bytes, parseDataListener);
        }
        for (int len : KNOWN_FRAME_LENGTHS) {
            if (len < bytes.length && isCrcValid(Arrays.copyOfRange(bytes, 0, len))) {
                boolean firstOk = parseValidatedFrame(Arrays.copyOfRange(bytes, 0, len), parseDataListener);
                boolean restOk = parseFrame(Arrays.copyOfRange(bytes, len, bytes.length), parseDataListener);
                return firstOk || restOk;
            }
        }
        int splitAt = indexOfNextFrameHeader(bytes);
        if (splitAt <= 0) {
            return false;
        }
        boolean firstOk = parseFrame(Arrays.copyOfRange(bytes, 0, splitAt), parseDataListener);
        boolean restOk = parseFrame(Arrays.copyOfRange(bytes, splitAt, bytes.length), parseDataListener);
        return firstOk || restOk;
    }

    /** 已经通过 CRC 校验、确认是完整一条帧之后，按长度分发到具体功能码的解析逻辑。 */
    private boolean parseValidatedFrame(byte[] bytes, IParseDataListener parseDataListener) {
        if (bytes.length == 19) {
            byte[] bytes1 = new byte[13];
            System.arraycopy(bytes, 4, bytes1, 0, 13);
            String str = DataUtils.bytesToHexString(bytes1, 1).trim();
            if (str.equals("44")) {
                byte[] bytes2 = new byte[4];
                bytes2[0] = bytes1[1];
                bytes2[1] = bytes1[2];
                bytes2[2] = bytes1[3];
                bytes2[3] = bytes1[4];

                byte[] bytes3 = new byte[4];
                bytes3[0] = bytes1[9];
                bytes3[1] = bytes1[10];
                bytes3[2] = bytes1[11];
                bytes3[3] = bytes1[12];
                String address = DataUtils.byteToHexString(bytes[2]);
                double oilLevel = Math.floor(DataUtils.byteToFloat(bytes2, bytes2.length) * 10f) / 10f;
                double waterLevel = Math.floor(DataUtils.byteToFloat(bytes3, bytes3.length) * 10f) / 10f;
                Log.e("TAG"," 油高 "+oilLevel+" 探针地址 "+address);
                oilCan = MyApplication.getInstance().getmOilcanDataManager().getDataByProbe(address);
                if (oilCan != null) {
                    oilCan.setOilLevel(BigDecimal.valueOf(oilLevel).add(BigDecimal.valueOf(oilCan.getLevelCorrection())).doubleValue());
                    oilCan.setWaterLevel(Math.max(0, BigDecimal.valueOf(waterLevel).add(BigDecimal.valueOf(oilCan.getWaterLevelCorrection())).doubleValue()));

                    if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.SHOW_VOLUME, true)) {
                        if (oilCan.getHightArray().length > 1) {
                            oilVolume = DataUtils.getLitre(
                                    oilCan.getOilLevel(), oilCan.getHightArray(),
                                    oilCan.getLitreArray());
                        }
                        if (oilCan.getMaxVolume() != 0) {
                            canAddOilWeight = (oilCan.getMaxVolume() * oilCan.getDensity()) - DataUtils.getLitre(oilCan.getOilLevel(), oilCan.getHightArray(), oilCan.getLitreArray()) * oilCan.getDensity();
                            oilCan.setSurPlus(DataUtils.getOilcanSurplus(oilCan));
                            oilCan.setCanAddOilWeight(DataUtils.doubleFoment(oilCan.getSurPlus() * oilCan.getDensity()));
                        }
                    }
//                             else {
//                                oilVolume = 0.0;
//                                oilCan.setCanAddOilWeight(0.0);
//                            }
                    oilCan.setOilVolume(DataUtils.doubleFoment(oilVolume));
                    oilCan.setOilWeight(DataUtils.doubleFoment(oilCan
                            .getDensity() * oilCan.getOilVolume()));
                    // 温度不在这里写：44帧只带油高/水位，温度由该罐自己的45帧应答
                    // 绑定更新（见下方 bytes.length==17 分支），收到之前维持上次的值（issue #28）。
                    oilRoate = (int) ((oilCan.getOilLevel() / oilCan
                            .getMaxHeight()) * 100);

                    oilCan.setOilRatio(oilRoate > 100 ? 100 : oilRoate);
                    oilCan.setLastTime(System.currentTimeMillis());
                    if (parseDataListener != null) {
                        parseDataListener.hexDataParseFinish(oilCan);
                    }
                    return true;
                }
            }
            return false;
        } else if (bytes.length == 17) {
            byte[] bytes1 = new byte[13];
            System.arraycopy(bytes, 4, bytes1, 0, 11);
            String str = DataUtils.bytesToHexString(bytes1, 1).trim();
           // Log.i("CRC", "probeInfoPase: 准备crc的数据"+str+"   "+DataUtils.bytesToHexString(bytes1,3));
            if (str.equals("45")) {
                // 45帧按探针地址绑定到对应油罐，取址方式与44帧一致，不再写共享字段（issue #28）。
                String address = DataUtils.byteToHexString(bytes[2]);
                double temp = Math.floor(calcTemperature(bytes1[1], bytes1[2]));
                OilCan can = MyApplication.getInstance().getmOilcanDataManager().getDataByProbe(address);
                if (can != null) {
                    can.setTemper(BigDecimal.valueOf(temp).add(BigDecimal.valueOf(can.getTempCorrection())).doubleValue());
                    return true;
                }
            }
        }
        else if (bytes.length==14){
            // 长度两个字节在 bytes[10]（低字节0A）、bytes[11]（高字节FA）
            byte[] bytes4 = new byte[2];
            bytes4[0] = bytes[10];
            bytes4[1] = bytes[11];

            // 小端模式解析长度
            int value = java.nio.ByteBuffer.wrap(bytes4)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)  // 高字节在前
                    .getShort() & 0xFFFF;

            int decimal = bytes[2] & 0xFF;
            // TanZhenLang 只开16位，探针地址理论上到255，越界就丢弃这次数据（历史遗留）。
            if (decimal < Singleton.getInstance().TanZhenLang.length) {
                Singleton.getInstance().TanZhenLang[decimal] = value;
            }
        }

        return false;
    }
    OilCanSet oilCanSets;
    public  double calcTemperature(byte highByte, byte lowByte) {
        // 将 byte 转为无符号 int
        int high = highByte & 0xFF;
        int low = lowByte & 0xFF;
        int rawValue = (high << 8) | low;   // 高字节在左，低字节在右
        return rawValue / 16.0;             // 等价于 rawValue * 0.0625
    }


    public static ZhongChengCompany getInstance() {
        if (instance == null) {
            instance = new ZhongChengCompany();
        }
        return instance;
    }
}
