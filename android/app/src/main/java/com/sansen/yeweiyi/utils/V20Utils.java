package com.sansen.yeweiyi.utils;

import android.util.Log;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class V20Utils {

    private static Map<Double,Double> map;
    private static DecimalFormat df   = new DecimalFormat("########0.00");

    public static double getV20(double v,double p,double t){
        double y = getTemperatureCoefficient(p);
        Log.i("-----------------",y + "");
        double V20 = v*p/(p+(t-20) * y);
        return Double.valueOf(df.format(V20));
    }



    private static double getTemperatureCoefficient(double key){

        if (map == null || map.size() == 0){
            map = new TreeMap<>();
            map.put(0.7013,0.00089);
            map.put(0.7072,0.00088);
            map.put(0.7132,0.00087);
            map.put(0.7193,0.00086);
            map.put(0.7255,0.00085);
            map.put(0.7314,0.00084);
            map.put(0.7380,0.00083);
            map.put(0.7443,0.00082);
            map.put(0.7509,0.00081);
            map.put(0.7574,0.0008);
            map.put(0.7640,0.00079);
            map.put(0.7709,0.00078);
            map.put(0.7772,0.00077);
            map.put(0.7847,0.00076);
            map.put(0.7917,0.00075);
            map.put(0.7990,0.00074);
            map.put(0.8063,0.00073);
            map.put(0.8137,0.00072);
            map.put(0.8213,0.00071);



            map.put(0.8291,0.0007);
            map.put(0.8370,0.00069);
            map.put(0.8450,0.00068);
            map.put(0.8533,0.00067);
            map.put(0.8618,0.00066);
            map.put(0.8704,0.00065);
            map.put(0.8792,0.00064);
            map.put(0.8884,0.00063);
            map.put(0.8977,0.00062);
            map.put(0.9073,0.00061);
            map.put(0.9172,0.0006);
            map.put(0.9276,0.00059);
            map.put(0.9382,0.00058);
            map.put(0.9492,0.00057);
            map.put(0.9609,0.00056);
            map.put(0.9729,0.00055);
            map.put(0.9855,0.00054);
            map.put(0.9951,0.00053);
            map.put(1.0131,0.00052);


//            Set set=map.keySet();
//            Object[] arr=set.toArray();
//            Arrays.sort(arr);
        }


        double y = 0.6954;
        for(Double mapKey:map.keySet()){
            if (y < key && key <= mapKey){
                return map.get(mapKey);
            }
            y = mapKey;
            System.out.println(mapKey);
        }
        return 0;
    }


}
