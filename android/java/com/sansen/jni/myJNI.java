package com.sansen.jni;

/**
 * Created by PC on 2018-06-06.
 */

public class myJNI {

    static {
        System.loadLibrary("JniTest");
    }
    //native方法 getStrData
    public static native String getPhoneData();
    //native方法 getStrData
    public static native String getPhoneData2();
    //native方法 getStrData
    public static native String getPhoneData3();
    //得到公钥
    public static native String getPublicKey();
    //得到私钥
    public static native String getPrivateKey();

}
