package com.sansen.yeweiyi.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class NumberTools {
    /**
     * 保留两位有效小数
     * DecimalFormat is a concrete subclass of NumberFormat that formats decimal numbers.
     * @param d
     * @return
     */
    // [已修复-2026-09-23] 不传 DecimalFormatSymbols 时用设备当前 locale 的小数点符号，西语下会
    // 格式化成"3400,0"这种用逗号的字符串，再被 Double.parseDouble()（只认"."）解析就崩溃——
    // 跟 DataUtils.doubleFoment 是同一类 bug，固定用 Locale.US 避免跟着系统语言变。
    public static double formatDouble4(double d) {
        DecimalFormat df = new DecimalFormat("#.00", DecimalFormatSymbols.getInstance(Locale.US));


        return Double.parseDouble(df.format(d));
    }

}
