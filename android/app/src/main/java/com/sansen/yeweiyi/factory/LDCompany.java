package com.sansen.yeweiyi.factory;

import android.content.Context;
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

public class LDCompany implements AbstractCompany {
    private static final String TAG = "YLBSCompany";
    private static LDCompany instance;
    private SparseArray<OilCan> map;
    private double oilVolume;
    private double canAddOilWeight;

    private LDCompany() {
    }

    @Override
    public String sendProbeInfo(String command, String probeaddress, Context context) {
        StringBuffer sb = new StringBuffer();
        sb.append(probeaddress);
        sb.append("03");
        sb.append("00020002");
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

    short crc;
    byte[] byte2 = new byte[2];
    OilCan oilCan;
    int oilRoate;

    @Override
    public boolean probeInfoPase(String data, IParseDataListener parseDataListener) {
        data = data.replace(" ", "");
        byte[] bytes = DataUtils.HexString2Bytes(data);
        if (bytes.length == 9) {
            byte[] bytes1 = new byte[7];
            System.arraycopy(bytes,0,bytes1,0,7);
           //  Log.i(TAG, "probeInfoPase: 准备crc的数据"+DataUtils.bytesToHexString(bytes1,bytes1.length));
            String crc = getString(CRCheck.getModBusCRC(bytes1));
            byte[] crcBytes = DataUtils.HexString2Bytes(crc);
           //  Log.i(TAG, "probeInfoPase: crc值"+DataUtils.bytesToHexString(crcBytes,crcBytes.length));
            if (crcBytes[0] == bytes[8] && crcBytes[1] == bytes[7]) {
                byte[] bytes2 = new byte[4];
                bytes2[0] = bytes[3];
                bytes2[1] = bytes[4];
                bytes2[2] = bytes[5];
                bytes2[3] = bytes[6];
                String address = DataUtils.byteToHexString(bytes[0]);
                double oilLevel = DataUtils.byte2Short(bytes2);
               //  Log.i(TAG, "probeInfoPase: 油位"+DataUtils.byte2Short(bytes2) +" "+oilLevel);
                map = MyApplication.getInstance().getmOilcanDataManager().getData();
                if (map != null) {
                    for (int i = 0; i < map.size(); i++) {
                        oilCan = map.valueAt(i);
                       //  Log.i(TAG, "probeInfoPase:地址 "+address+ "   "+oilCan.getProbeAdr());
                        if (address.equals(oilCan.getProbeAdr())) {
                            // 油高 + 补偿
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

                            // 温度+补偿BigDecimal.valueOf(probe.getOilLevel()).add(BigDecimal.valueOf(oilCan.getLevelCorrection())).doubleValue()
                            oilCan.setTemper(BigDecimal.valueOf(0).add(BigDecimal.valueOf(oilCan.getTempCorrection())).doubleValue());

                            // 油比例
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
        }
        return false;
    }

    public static LDCompany getInstance() {
        if (instance == null) {
            instance = new LDCompany();
        }
        return instance;
    }
}
