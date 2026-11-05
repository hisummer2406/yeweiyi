package com.sansen.yeweiyi.utils;

import android.text.InputFilter;
import android.text.Spanned;

import java.util.regex.Pattern;

/**
 * 限制 EditText 小数点后最多可输入的位数（默认场景下全项目统一为 1 位）。
 * <p>
 * 只约束"小数位数"，不重复限制可输入的字符——允许的字符集仍由布局里的
 * android:digits / android:inputType 负责，两者可以叠加使用：
 * digits/inputType 挡住非法字符，本过滤器挡住超出位数的小数。
 */
public class DecimalDigitsInputFilter implements InputFilter {

    private final Pattern pattern;

    public DecimalDigitsInputFilter(int decimalDigits) {
        pattern = Pattern.compile("-?[0-9]*\\.?[0-9]{0," + decimalDigits + "}");
    }

    @Override
    public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        String result = dest.subSequence(0, dstart).toString()
                + source.subSequence(start, end).toString()
                + dest.subSequence(dend, dest.length()).toString();
        if (pattern.matcher(result).matches()) {
            return null; // 不拦截，接受本次输入
        }
        return ""; // 丢弃本次输入的字符
    }
}
