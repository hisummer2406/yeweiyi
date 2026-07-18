package com.sansen.yeweiyi.utils;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Created by PC on 2018-02-25.
 */

public class TestUtils {
    public static void TestSystem(){

        try {
            Process su;
            DataOutputStream os;
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("uiautomator runtest AutoRunner.jar -c main.MainTest");
            os.flush();
            os.close();
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

}
