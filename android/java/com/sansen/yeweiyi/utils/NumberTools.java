package com.sansen.yeweiyi.utils;

import java.text.DecimalFormat;

public class NumberTools {
    /**
     * 保留两位有效小数
     * DecimalFormat is a concrete subclass of NumberFormat that formats decimal numbers.
     * @param d
     * @return
     */
    public static double formatDouble4(double d) {
        DecimalFormat df = new DecimalFormat("#.00");


        return Double.parseDouble(df.format(d));
    }

}
