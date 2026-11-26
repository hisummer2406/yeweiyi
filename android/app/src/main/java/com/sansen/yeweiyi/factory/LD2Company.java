package com.sansen.yeweiyi.factory;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.math.BigDecimal;

/**
 * Created by PC on 2018-05-23.
 */

public class LD2Company implements AbstractCompany {
    private static final String TAG = "YLBSCompany";
    private static LD2Company instance;
    private SparseArray<OilCan> map;
    private double oilVolume;
    private double canAddOilWeight;

    private LD2Company() {
    }

    @Override
    public String sendProbeInfo(String command, String probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
        sb.append("01");
        sb.append("04");
        sb.append("000000");
        sb.append(probeaddress);
        String crc = getString(CRCheck.crc16(DataUtils.HexString2Bytes(sb.toString())));
        sb.append(crc.substring(2));
        sb.append(crc.substring(0, 2));
        System.out.println("CRC:" + sb.toString());
        return sb.toString();
    }


    @Override
    public String sendWenDuInfo(String command, String Probeaddress, Context context) {
        return null;
    }

    @Override
    public String sendChangDuInfo(String command, String Probeaddress, Context context) {
        return null;
    }

    public String getString(String data) {
        while (data.length() < 4) {
            data = 0 + data;
        }
        return data;
    }

    /**
     * 4-20mA 是多通道采集模块：一帧请求要问多少个寄存器（=多少路通道），必须覆盖当前实际配置的
     * 所有罐的探针地址，否则地址号更大的罐永远不会出现在应答帧里（之前硬编码成 2，导致 3 号罐及
     * 以后读不到数据）。空数组/全部非正数时返回 1，避免拼出数量为 0 的畸形请求帧。
     * 不依赖 Context/SparseArray/Log，可在 JVM 单测里直接调用。
     */
    public static int maxChannelNeeded(int[] probeAddresses) {
        int max = 0;
        if (probeAddresses != null) {
            for (int address : probeAddresses) {
                if (address > max) {
                    max = address;
                }
            }
        }
        return max > 0 ? max : 1;
    }

    /**
     * 把 0-based 的通道下标转成通道地址字符串，补零规则与 {@link com.sansen.yeweiyi.bean.OilCan#getProbeAdr()}
     * 保持一致（长度 <=1 才补一位 0），支持到两位数通道（10~16）。原先无条件拼 "0"+(i+1)，
     * 通道 10 会拼成 "010"，永远匹配不上补零成 "10" 的探针地址。
     */
    public static String channelAddress(int channelIndex0Based) {
        String address = String.valueOf(channelIndex0Based + 1);
        if (address.length() <= 1) {
            address = "0" + address;
        }
        return address;
    }

    short crc;
    byte[] byte2 = new byte[2];
    OilCan oilCan;
    int oilRoate;

    @Override
    public boolean probeInfoPase(String data, IParseDataListener parseDataListener) {
        data = data.replace(" ", "");
        byte[] bytes = DataUtils.HexString2Bytes(data);
        Log.i(TAG, "RCR: 准备crc的数据" + data);
        if (bytes.length > 6) {
            int decimal = (bytes[2] & 0xFF) / 2;
            for (int i = 0; i < decimal; i++) {

                byte[] bytes1 = new byte[2];
                bytes1[0] = bytes[i * 2 + 3];
                bytes1[1] = bytes[i * 2 + 4];
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes1) {
                    sb.append(String.format("%02X ", b & 0xFF)); // 大写，两位，不足补0，加空格分隔
                }
                String hex = sb.toString().trim(); // "0A FA 00"
                Integer level = Integer.parseInt(hex.replace(" ", "").trim(), 16);
                Log.e("CRC", hex + "    " + level+" 当前探针  "+i+"  探针数量 "+decimal);
                if (level > 0) {
                    String str2 = String.valueOf(level);
                    String str = str2.substring(1, str2.length());
                    map = MyApplication.getInstance().getmOilcanDataManager().getData();
                    if (map != null) {
                        for (int j = 0; j < map.size(); j++) {
                            oilCan = map.valueAt(j);
                            String address = channelAddress(i);
                            if (address.equals(oilCan.getProbeAdr())) {
                               // Log.e("CRC:高度 ", "   " + i + "   " + oilLevel + "   " + oilCan.getProbeAdr() + "  " + ("0" + (i + 1)));
                                // 油高 + 补偿

                                double oilLevel = (((Double.parseDouble(str) / 249) - 4) * oilCan.getMaxHeight()) / 16;

                                oilCan.setOilLevel(BigDecimal.valueOf(oilLevel).add(BigDecimal.valueOf(oilCan.getLevelCorrection())).doubleValue());
                                //判断是否显示体积
                                if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.SHOW_VOLUME, true)) {
                                    if (oilCan.getHightArray().length > 1) {
                                        // 油体积 = 体积-水体积
                                        oilVolume = DataUtils.getLitre(
                                                oilCan.getOilLevel(), oilCan.getHightArray(),
                                                oilCan.getLitreArray());
                                    }

                                    // 可添油重量
                                    if (oilCan.getMaxVolume() != 0) {
                                        canAddOilWeight = (oilCan.getMaxVolume() * oilCan.getDensity()) - DataUtils.getLitre(oilCan.getOilLevel(), oilCan.getHightArray(), oilCan.getLitreArray()) * oilCan.getDensity();
                                        //可添油体积
                                        oilCan.setSurPlus(DataUtils.getOilcanSurplus(oilCan));
                                        //可添油重量
                                        oilCan.setCanAddOilWeight(DataUtils.doubleFoment(oilCan.getSurPlus() * oilCan.getDensity()));
                                    }

                                } else {
                                    oilVolume = 0.0;
                                    oilCan.setCanAddOilWeight(0.0);
                                }

                                oilCan.setOilVolume(DataUtils.doubleFoment(oilVolume));
                                // 存油重量
                                oilCan.setOilWeight(DataUtils.doubleFoment(oilCan
                                        .getDensity() * oilCan.getOilVolume()));

                                // 本协议不读温度，这里只是把温度补偿值当温度显示。用 setTemper 而不是 setTemperRead：
                                // 不算真实读数，高温持续确认的计时不前进，所以这类探针不会触发高温报警。
                                oilCan.setTemper(BigDecimal.valueOf(0).add(BigDecimal.valueOf(oilCan.getTempCorrection())).doubleValue());

                                // 油比例
                                oilRoate = (int) ((oilCan.getOilLevel() / oilCan
                                        .getMaxHeight()) * 100);

                                oilCan.setOilRatio(oilRoate > 100 ? 100 : oilRoate);
                                oilCan.setLastTime(System.currentTimeMillis());
                                oilCan.setHasReading(true);
                                if (parseDataListener != null) {
                                    parseDataListener.hexDataParseFinish(oilCan);
                                }

                            }
                        }

                    }
                }
                //return false;
            }
            return true;
        }
        return false;
    }

    public static LD2Company getInstance() {
        if (instance == null) {
            instance = new LD2Company();
        }
        return instance;
    }
}
