package com.sansen.yeweiyi.utils;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Random;

import atg.jni.ATGJNI;

public class DataUtils {
    private static DecimalFormat douFormat;

    public static double doubleFoment(double value) {

        douFormat = new DecimalFormat("#.00");
        double result = Double.parseDouble(douFormat.format(value));
        return result;
    }

    public static double getOilcanSurplus(OilCan oilCan) {

        return Arith.sub(Arith.sub((double) oilCan.getMaxVolume(), getOilLitre(oilCan)), getWaterLitre(oilCan));
    }


    public static String dou2AscHex(double d) {
        if (d == 0) {
            return "3030303030303030";
        }
        return str2AscHex(Integer.toHexString(Float.floatToIntBits((float) d)));

    }

    public static String str2AscHex(String str) {
        char[] charArray = str.toCharArray();
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < charArray.length; i++) {
            buffer.append(Integer.toHexString((int) charArray[i]));
        }
        return buffer.toString();
    }



    public static double getAverageTemp(double t1, double t2, double t3,
                                        double t4, double t5) {

        Double add = Arith.add(Arith.add(t1, t2, t3), Arith.add(t4, t5));

        return Arith.div(add, 5.0);
    }

    public static double getLitre(double oilHieght, int[] hightArray,
                                  double[] litreArray) {
        if (hightArray.length != 30) {

            return getVoulme(oilHieght, hightArray, litreArray);
        }

        return ATGJNI.getLitre((float) oilHieght, hightArray, litreArray);
    }

    public static double getHight(double curVoulme, int[] height, double[] volume) {
        int length = height.length;
        int i;//当前的位置
        //如果油低于最低点，那么取最低的值
        if (curVoulme < volume[0]) {
            return height[0];
        }
//        //如果高于最高点，取最高的值
//        if (curHight > height[length - 1]) {
//            double reuslt = volume[length - 1];
//            return reuslt;
//        }
        //定位当前油位所在的位置
        for (i = 0; i < length; i++) {
            if ((curVoulme >= volume[i]) && (curVoulme <= volume[i + 1]))
                break;
        }
        double h1 = height[i];
        double h2 = height[i + 1];
        double v1 = curVoulme - volume[i];
        double v2 = volume[i + 1] - volume[i];
        double bili = v1 /v2;
        double cur = h1 + (h2 - h1) * bili;
        return cur;
    }


    public static double getVoulme(double curHight, int[] height, double[] volume) {
        int length = height.length;
        int i;//当前的位置
        //如果油低于最低点，那么取最低的值
        if (curHight < height[0]) {
            return volume[0];
        }
//        //如果高于最高点，取最高的值
//        if (curHight > height[length - 1]) {
//            double reuslt = volume[length - 1];
//            return reuslt;
//        }
        //定位当前油位所在的位置
        for (i = 0; i < length; i++) {
           // Log.e("TAG","i :"+i+" lengh"+length);
            if (i>=length-1){

                i=length-2;
                //Log.i("TAG","----"+i+"   volume"+volume.length+"hight ---"+height.length);
                break;
            }
            if ((curHight >= height[i]) && (curHight <= height[i + 1]))
                break;
        }
        double v1 = volume[i];
        double v2 = volume[i + 1];
        double h1 = curHight - height[i];
        double h2 = height[i + 1] - height[i];
        double bili = h1 / h2;
        double cur = v1 + (v2 - v1) * bili;
        return cur;
    }

    public static double getOilLitre(OilCan oilCan) {
        if (oilCan.getHightArray() == null || oilCan.getLitreArray() == null) {
            return 0;
        }

        double oilVolume = DataUtils.getLitre(oilCan.getOilLevel(),
                oilCan.getHightArray(), oilCan.getLitreArray());
        double waterVolume = DataUtils.getLitre(oilCan.getWaterLevel(),
                oilCan.getHightArray(), oilCan.getLitreArray());

        if (oilCan.getMaxVolume() >= oilVolume) {

            return Arith.sub(oilVolume, waterVolume);
        }
        return 0;
    }

    public static double getWaterLitre(OilCan oilCan) {
        if (oilCan.getHightArray() == null || oilCan.getLitreArray() == null) {
            return 0;
        }

        double waterVolume = DataUtils.getLitre(oilCan.getWaterLevel(),
                oilCan.getHightArray(), oilCan.getLitreArray());

        return Arith.get3Double(waterVolume);
    }

    /**
     * 获取VR协议的CRC
     *
     * @param data
     * @return
     */
    public static String getCrcPC(String data) {
        if (data.length() % 2 != 0) {
            return "0000";
        }
        int yy = 0;
        for (int i = 0; i < data.length(); i++) {

            if (i % 2 != 0) {
                continue;
            }
            yy = yy + Integer.parseInt(data.substring(i, i + 2), 16);
        }
        int ss = 65536 - (yy % 65536);
        String crc = Integer.toHexString(ss).toUpperCase();
        return str2AscHex(StringUtils.getFillUpCode(crc, 4));
    }

    public static String AddNull(String str) {
        String regex = "(.{2})";
        str = str.replaceAll(regex, "$1 ");
        return str;
    }

    public static int classGetID(Object o, Class z) throws SecurityException,
            NoSuchMethodException, IllegalArgumentException,
            IllegalAccessException, InvocationTargetException {
        z.cast(o);
        Method m = z.getMethod("getId", new Class[]{});
        return (Integer) m.invoke(o, new Object[]{});
    }

    public static String getD2D(int id) {
        StringBuilder d2d = new StringBuilder(Integer.toHexString(id * 65536 + 65535 - id));
        while (d2d.length() < 8) {
            d2d.insert(0, "0");

        }

        return d2d.toString().toUpperCase();
    }

    public static String Dec2Hex(long dec) {
        String hexStr = "";
        long decAbs = Math.abs(dec);
        while (decAbs > 0) {
            long lastFour = decAbs & 15;
            if (lastFour > 9)
                hexStr = (char) ('A' + lastFour - 10) + hexStr;
            else
                hexStr = lastFour + hexStr;
            decAbs >>= 4;
        }
        hexStr = dec < 0 ? "-" + hexStr : dec == 0 ? "0" : hexStr;
        return hexStr;
    }

    public static String Dec2HexAddZero(long dec) {
        String dec2Hex = Dec2Hex(dec);
        while (dec2Hex.length() < 2) {
            return "0" + dec2Hex;
        }
        return dec2Hex;
    }

    private static byte c;
    private static byte[] b = new byte[1];
    private static byte[] sendbyte;
    private static String s;
    private static CRCheck crCheck;


    /**
     * 生成6位随机数
     *
     * @return
     */
    public static String getSix() {
        Random rad = new Random();

        String result = rad.nextInt(1000000) + "";

        if (result.length() != 6) {
            return getSix();
        }
        return result;
    }
    public static byte[] hexStringToByteArray(String hex) {
        hex = hex.replaceAll("\\s+", ""); // 去除空格
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    public static byte[] HexString2Bytes(String src) {
        byte[] ret = new byte[src.length() / 2];
        byte[] tmp = src.getBytes();
        for (int i = 0; i < tmp.length / 2; i++) {
            ret[i] = uniteBytes(tmp[i * 2], tmp[i * 2 + 1]);
        }
        return ret;
    }

    public static short[] HexString2Shorts(String src) {
        short[] ret = new short[src.length() / 2];
        byte[] tmp = src.getBytes();
        for (int i = 0; i < tmp.length / 2; i++) {
            ret[i] = uniteBytes(tmp[i * 2], tmp[i * 2 + 1]);
        }
        return ret;
    }

    /**
     * 将两个ASCII字符合成一个字节； 如："EF"--> 0xEF
     *
     * @param src0 byte
     * @param src1 byte
     * @return byte
     */
    private static byte uniteBytes(byte src0, byte src1) {
        byte _b0 = Byte.decode("0x" + new String(new byte[]{src0}))
                .byteValue();
        _b0 = (byte) (_b0 << 4);
        byte _b1 = Byte.decode("0x" + new String(new byte[]{src1}))
                .byteValue();
        byte ret = (byte) (_b0 ^ _b1);
        return ret;
    }

    /**
     * 将Hex数组转换为Hex字符串
     *
     * @param src
     * @param size
     * @return
     */
    public static String bytesToHexString(byte[] src, int size) {
        String ret = "";
        if (src == null || size <= 0) {
            return null;
        }
        for (int i = 0; i < size; i++) {
            String hex = Integer.toHexString(src[i] & 0xFF);
            if (hex.length() < 2) {
                hex = "0" + hex;
            }
            hex += " ";
            ret += hex;
        }
        return ret.toUpperCase();
    }
    public static float hexToFloat(String hex) {
        // 去掉空格
        hex = hex.replaceAll("\\s+", "");

        // 将 HEX 转为 int
        int bits = Integer.parseInt(hex, 16);

        // 将 int 转为 float
        return Float.intBitsToFloat(bits);
    }
    public static float byteToFloat(byte[] src, int size) {
        return   hexToFloat(bytesToHexString(src,size));
    }

    public static String byteToHexString(byte src) {
        StringBuilder stringBuilder = new StringBuilder("");
        int v = src & 0xFF;
        String hv = Integer.toHexString(v);
        if (hv.length() < 2) {
            stringBuilder.append(0);
        }
        stringBuilder.append(hv);
        return stringBuilder.toString();
    }


    /* Convert byte[] to hex string.这里我们可以将byte转换成int，然后利用Integer.toHexString(int)来转换成16进制字符串。
     * @param src byte[] data
     * @return hex string
     */
    public static String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return null;
        }
        for (int i = 0; i < src.length; i++) {
            int v = src[i] & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString();
    }

    public static short[] toShortArray(byte[] src) {

        int count = src.length >> 1;
        short[] dest = new short[count];
        for (int i = 0; i < count; i++) {
            dest[i] = (short) (src[i * 2] << 8 | src[2 * i + 1] & 0xff);
        }
        return dest;
    }

    public static byte[] toByteArray(short[] src) {

        int count = src.length;
        byte[] dest = new byte[count << 1];
        for (int i = 0; i < count; i++) {
            dest[i * 2] = (byte) (src[i] >> 8);
            dest[i * 2 + 1] = (byte) (src[i] >> 0);
        }

        return dest;
    }

    /**
     * 将byte[2]转换成short
     *
     * @param b
     * @return
     */
    public static short byte2Short(byte[] b) {
        return (short) (((b[0] & 0xff) << 8) | (b[1] & 0xff));
    }

    /**
     * byte数组转int
     *
     * @param b
     * @return
     */
    public static int byte2Int(byte[] b) {
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    public static byte[] shortToByte(short number) {
        return new byte[] {
                (byte) ((number >> 8) & 0xFF),
                (byte) (number & 0xFF)
        };
    }

    public static String addASIIHex(byte[] bytes) {
        long temp = 0;
        for (byte b : bytes) {
            temp += b;
        }
        return convertStringToHex(Long.toHexString(temp).toUpperCase());
    }

    /**
     * ASII转byteStr
     *
     * @param str
     * @return
     */
    public static String convertStringToHex(String str) {

        char[] chars = str.toCharArray();

        StringBuffer hex = new StringBuffer();
        for (int i = 0; i < chars.length; i++) {
            hex.append(Integer.toHexString((int) chars[i]));
        }

        return hex.toString();
    }

    /**
     * HexStr to ASII
     *
     * @param hex
     * @return
     */
    public static String convertHexToString(String hex) {

        StringBuilder sb = new StringBuilder();
        StringBuilder temp = new StringBuilder();

        // 49204c6f7665204a617661 split into two characters 49, 20, 4c...
        for (int i = 0; i < hex.length() - 1; i += 2) {

            // grab the hex in pairs
            String output = hex.substring(i, (i + 2));
            // convert hex to decimal
            int decimal = Integer.parseInt(output, 16);
            // convert the decimal to character
            sb.append((char) decimal);

            temp.append(decimal);
        }

        return sb.toString();
    }

    public static String[] getOilType() {
        String json = "";
        String[] arr = MyApplication.getInstance().getResources().getStringArray(R.array.oiltypeArray);
        if (SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.LANGUAGE, "zh").equals("zh")) {
            json = SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.CN_OIlTYPE, "[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        } else if (SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.LANGUAGE, "zh").equals("en")) {
            json = SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.EN_OIlTYPE, "[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        }else if (SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.LANGUAGE, "zh").equals("th")) {
            json = SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.TH_OIlTYPE, "[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        }
        List<String> data = JSONObject.parseArray(json, String.class);
        String[] temp = new String[arr.length + data.size()];
        System.arraycopy(arr, 0, temp, 0, arr.length - 1);
        int i = arr.length - 1;
        for (String str : data) {
            temp[i++] = str;
        }
        return temp;
    }

    public static String getProbeStr(int type){
        return MyApplication.getInstance().getResources().getStringArray(R.array.probeTypeArray)[type];
    }

    public static float bytesToFloat(byte[] data) {// 解析4个字节中的数据，按照IEEE754的标准
        int s = 0;// 浮点数的符号
        float f = 0;// 浮点数
        int e = 0;// 指数
        if ((data[3] & 0xff) >= 128) {// 求s
            s = -1;
        } else {
            s = 1;
        }
        int temp = 0;// 指数位的最后一位
        if ((data[2] & 0xff) >= 128) {
            temp = 1;
        } else
            temp = 0;
        e = ((data[3] & 0xff) % 128) * 2 + temp;// 求e
        // f=((data[2]&0xff)-temp*128+128)/128+(data[1]&0xff)/(128*256)+(data[0]&0xff)/(128*256*256);
        float[] data2 = new float[3];
        data2[0] = data[0] & 0xff;
        data2[1] = data[1] & 0xff;
        data2[2] = data[2] & 0xff;
        f = (data2[2] - temp * 128 + 128) / 128 + data2[1] / (128 * 256)
                + data2[0] / (128 * 256 * 256);
        float result = 0;
        if (e == 0 && f != 0) {// 次正规数
            result = (float) (s * (f - 1) * Math.pow(2, -126));
            return result;
        }
        if (e == 0 && f == 0) {// 有符号的0
            result = (float) 0.0;
            return result;
        }
        if (s == 0 && e == 255 && f == 0) {// 正无穷大
            result = (float) 1111.11;
            return result;
        }
        if (s == 1 && e == 255 && f == 0) {// 负无穷大
            result = (float) -1111.11;
            return result;
        } else {
            result = (float) (s * f * Math.pow(2, e - 127));
            return result;
        }

    }

    /**
     * 将byte[2]转换成short
     *
     * @param value
     * @return
     */
    public static short byteToShort(byte[] value) {
        return (short) (((value[0] & 0xff) << 8) | (value[1] & 0xff));
    }
    public static byte[] hexString2Bytes(String src) {
        byte[] ret = new byte[src.length() / 2];
        byte[] tmp = src.getBytes();
        for (int i = 0; i < tmp.length / 2; i++) {
            ret[i] = uniteBytes(tmp[i * 2], tmp[i * 2 + 1]);
        }
        return ret;
    }

    /**
     * 将Hex数组转换为Hex字符串
     * [0x65,0x66] -> 6566  没有间隔符
     *
     * @return
     */
    public static String bytesToHexString(byte b) {
        int v = b & 0xFF;
        return Integer.toHexString(v);
    }


    /**
     * 校验和
     *
     * @param msg 需要计算校验和的byte数组
     * @return 计算出的校验和数组
     */
    public static byte SumCheck(byte[] msg) {
        int sum;
        byte data = 0;
        for (int i = 0; i < msg.length; i++) {
            data^=msg[i];
        }
//        System.out.println("校验 "+byteToHexString(data));
        return (byte) (data-0x80);
    }
    /**
     * 将int转 4 字节数组
     *
     * @param num
     * @return
     */
    public static byte[] int2Byte(int num) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((num >> 24) & 0xff);
        bytes[1] = (byte) ((num >> 16) & 0xff);
        bytes[2] = (byte) ((num >> 8) & 0xff);
        bytes[3] = (byte) (num & 0xff);
        return bytes;
    }
}

