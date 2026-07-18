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

public class ZhongChengCompany implements AbstractCompany {
    private static final String TAG = "YLBSCompany";
    private static ZhongChengCompany instance;
    private SparseArray<OilCan> map;
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
    public String sendWenDuInfo(String command, String Probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
        //sb.append(command);
        sb.append(Probeaddress);
        sb.append("03");
        sb.append("450000");
        String crc = getString(CRCheck.crc12(sb.toString().length(), DataUtils.HexString2Bytes(sb.toString())));
        sb.append(crc);
        System.out.println("CRC:" + crc + "---" + sb.toString());
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
    double wendu;

    @Override
    public boolean probeInfoPase(String data, IParseDataListener parseDataListener) {
        data = data.replace(" ", "");

        byte[] bytes = DataUtils.HexString2Bytes(data);
        Log.e("CRC ", "bytes.length " + bytes.length);
        if (bytes.length == 19) {
            byte[] bytes1 = new byte[13];
            // System.arraycopy(bytes,0,bytes1,0,7);
            System.arraycopy(bytes, 4, bytes1, 0, 13);
            String str = DataUtils.bytesToHexString(bytes1, 1).trim();
            // Log.e("probeInfoPase",bytes1[0]+"----"+bytes1[1]);
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
                map = MyApplication.getInstance().getmOilcanDataManager().getData();
                if (map != null) {
                    for (int i = 0; i < map.size(); i++) {
                        oilCan = map.valueAt(i);
                        if (address.equals(oilCan.getProbeAdr())) {
                            oilCan.setOilLevel(BigDecimal.valueOf(oilLevel).add(BigDecimal.valueOf(oilCan.getLevelCorrection())).doubleValue());
                            oilCan.setWaterLevel(BigDecimal.valueOf(waterLevel).add(BigDecimal.valueOf(oilCan.getWaterLevelCorrection())).doubleValue());
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
                            } else {
                                oilVolume = 0.0;
                                oilCan.setCanAddOilWeight(0.0);
                            }
                            oilCan.setOilVolume(DataUtils.doubleFoment(oilVolume));
                            oilCan.setOilWeight(DataUtils.doubleFoment(oilCan
                                    .getDensity() * oilCan.getOilVolume()));
                            oilCan.setTemper(BigDecimal.valueOf(wendu).add(BigDecimal.valueOf(oilCan.getTempCorrection())).doubleValue());
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
                }

            }

            return false;
        } else if (bytes.length == 17) {
            byte[] bytes1 = new byte[13];
            System.arraycopy(bytes, 4, bytes1, 0, 11);
            String str = DataUtils.bytesToHexString(bytes1, 1).trim();
           // Log.i("CRC", "probeInfoPase: 准备crc的数据"+str+"   "+DataUtils.bytesToHexString(bytes1,3));
            if (str.equals("45")) {
                byte[] bytes4 = new byte[2];
                bytes4[0] = bytes1[1];
                bytes4[1] = bytes1[2];
                wendu = Math.floor(calcTemperature(bytes1[1],bytes1[2]));
               // Log.e("CRC 温度", "===" + wendu);
            }
        }

        return false;
    }
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