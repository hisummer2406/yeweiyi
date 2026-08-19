package com.sansen.yeweiyi.utils;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

import storage.IMountService;

public class USBUtils {
	static IMountService iMountService;
    public static String TAG ="USBUtils";
    static {  
        try {  
            Method method = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder binder = (IBinder) method.invoke(null, "mount");
            iMountService = IMountService.Stub.asInterface(binder);
            Log.i(TAG, "static initializer: "+iMountService);
        } catch (Exception e) {
            //e.printStackTrace();
        }  
    }  
  
  
    public static void unMount() {  
        try {  
            iMountService.unmountVolume("/storage/usbhost1", true, true);
        } catch (RemoteException e) {
            Log.i(TAG, "unMount: 错误");
            //e.printStackTrace();
        }  
    }  
  
    public static void mount(){  
        try {  
            iMountService.mountVolume(android.os.Environment.getExternalStorageDirectory().toString());  
        } catch (Exception e) {
            //e.printStackTrace();
        }  
    }  
}
