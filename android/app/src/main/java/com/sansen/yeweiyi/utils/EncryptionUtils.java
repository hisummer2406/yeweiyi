package com.sansen.yeweiyi.utils;

import android.util.Log;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import Decoder.BASE64Encoder;

/**
 * Created by PC on 2018-01-08.
 */

public class EncryptionUtils {

    /**利用MD5进行加密
     * @param str  待加密的字符串
     * @return  加密后的字符串
     * @throws NoSuchAlgorithmException  没有这种产生消息摘要的算法
     */
    public static String EncoderByMd5(String str) {
        //确定计算方法
        String result = "";
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");

            byte[] bytes = md5.digest(str.getBytes());

            for(byte b : bytes)
            {
                String temp = Integer.toHexString(b & 0xff);
                if(temp.length() == 1)
                {
                    temp = "0" + temp;
                }
                result = result + temp;
            }

        } catch (NoSuchAlgorithmException e) {
            Log.e("EncryptionUtils", "EncoderByMd5 执行失败", e);
        }
        BASE64Encoder base64en = new BASE64Encoder();
        //加密后的字符串
        result = base64en.encode(result.getBytes());


        return result;
    }
    /**判断用户密码是否正确
     * @param newpasswd  用户输入的密码
     * @param oldpasswd  数据库中存储的密码－－用户密码的摘要
     * @return
     * @throws NoSuchAlgorithmException
     * @throws UnsupportedEncodingException
     */
    public static boolean checkpassword(String newpasswd, String oldpasswd){
        newpasswd = newpasswd.replace(" ", "");
        oldpasswd = oldpasswd.replace(" ", "");

        if(newpasswd.equals(oldpasswd))
            return true;
        else
            return false;
    }


    public static void main(String[] args) throws Exception
    {
//        MessageDigest md5 = MessageDigest.getInstance("MD5");
//        String password = "123456";
//        byte[] bytes = md5.digest(password.getBytes());
//        String result = "";
//        for(byte b : bytes)
//        {
//            String temp = Integer.toHexString(b & 0xff);
//            if(temp.length() == 1)
//            {
//                temp = "0" + temp;
//            }
//            result = result + temp;
//        }
//        BASE64Encoder base64Encoder = new BASE64Encoder();
//        System.out.println(base64Encoder.encode(result.getBytes()));
        System.out.println(EncoderByMd5("123456"));
    }

}
