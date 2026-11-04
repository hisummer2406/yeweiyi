package com.sansen.yeweiyi.logic.shift;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 交班/库存记录的时间：同一瞬间的本地时间字符串（yyyy-MM-dd HH:mm:ss，存库/显示用）与 epoch 毫秒（截到整秒）。
 * 两者取自同一个 now，保证上报时由 epoch 算出的标识与界面上的时间对得上（issue #49）。
 */
public final class ShiftTime {
    public final long epochMs;
    public final String local;

    private ShiftTime(long epochMs, String local) {
        this.epochMs = epochMs;
        this.local = local;
    }

    public static ShiftTime of(long nowMs, TimeZone tz) {
        long seconds = truncateToSecond(nowMs);
        return new ShiftTime(seconds, formatSeconds(seconds, tz));
    }

    public static long truncateToSecond(long ms) {
        return ms - ((ms % 1000L) + 1000L) % 1000L; // 不用 Math.floorDiv：API 24 才有，minSdk 21
    }

    public static String formatSeconds(long ms, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
    }
}
