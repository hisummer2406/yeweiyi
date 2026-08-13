package com.sansen.yeweiyi.manager;

import android.os.Environment;
import android.util.Log;

import com.sansen.yeweiyi.BuildConfig;
import com.sansen.yeweiyi.bean.OilCan;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 探头报文录制器 —— golden master（特征化测试）的数据来源。
 *
 * <p>本机没有真实探头硬件，串口/协议层的行为在开发环境里验证不了。要在重构解析逻辑时
 * 有「改动是否改变了行为」的判据，唯一的办法是把现场真实工况下的报文与其解析结果成对
 * 带回来，作为可在纯 JVM 上重放的 fixture。本类就是采集这批数据的钩子。
 *
 * <p><b>默认关闭。</b>开启方式：在 {@code local.properties} 里加 {@code sansen.probeRecording=true}
 * 后重新构建（照 {@code ApiConfig} 那四个凭据的注入模式，见 {@code app/build.gradle}）。
 * 关闭时 {@link #ENABLED} 是编译期常量 {@code false}，每个方法首行即返回，
 * 对探头链路的行为与时序无任何影响。
 *
 * <p><b>录的是什么：</b>探头轮询链路一进一出两处接缝，按线程内先后顺序成对。
 * <ul>
 *   <li>IN —— 喂给 {@code XxxCompany.probeInfoPase(String, IParseDataListener)} 的 hex 报文。
 *       录这个而不是它上游的原始 {@code byte[]}，因为它才是解析逻辑真正的输入，
 *       也是将来抽出的 {@code XxxParser} 的输入，重放时喂进去的和现场当时喂进去的才是同一个东西。</li>
 *   <li>OUT —— {@code hexDataParseFinish(OilCan)} 拿到的解析产出。
 *       {@code probeInfoPase} 是同线程同步回调，所以同一线程内 IN 之后的第一条 OUT 就是它的产出，
 *       不需要额外的关联 id。</li>
 * </ul>
 *
 * <p><b>覆盖范围：</b>只覆盖探头轮询路径的三种 probeType（0 中诚 / 1 LD / 2 LD2）。
 * {@code VR350Factory} 走的是 PC 通信线程，不经这条路径、也不回调 {@code hexDataParseFinish}，不在本类范围内。
 *
 * <p><b>格式：</b>见 {@link ProbeTrafficFormat}。格式的定义与解析都在那里，本类只负责落盘 ——
 * 读取方（测试侧的 fixture 加载器）用的是同一份定义，不存在两边各写各的解析而漂移的可能。
 */
public final class ProbeTrafficRecorder {

    private static final String TAG = "ProbeTrafficRecorder";

    /**
     * 编译期常量。构建时由 app/build.gradle 从 local.properties 的 sansen.probeRecording 注入，
     * 缺失即 false —— 也就是说不特意开启就永远是关的。
     */
    private static final boolean ENABLED = BuildConfig.PROBE_RECORDING;

    private static final String DIR_NAME = "yeweiyi-probe-record";

    private static final Object LOCK = new Object();

    private static BufferedWriter writer;
    /** 当前 writer 对应的日期，用于跨天时换文件。采集常常跑满一天，按天分文件便于整理成 fixture。 */
    private static String writerDay;
    /** 打开失败只告警一次，避免在轮询线程里刷屏。 */
    private static boolean openFailureLogged;

    private ProbeTrafficRecorder() {
    }

    /** 录制是否开启。调用方通常不需要判断，各 record 方法内部已经先判过。 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 录一帧待解析的输入报文。
     *
     * @param hexFrame  即将喂给 probeInfoPase 的 hex 字符串
     * @param probeType 当时的探头类型（0 中诚 / 1 LD / 2 LD2）
     * @param oilCanPos 当时轮询到的油罐位
     */
    public static void recordRequest(String hexFrame, int probeType, int oilCanPos) {
        if (!ENABLED) {
            return;
        }
        write(ProbeTrafficFormat.formatIn(
                System.currentTimeMillis(), probeType, oilCanPos, hexFrame));
    }

    /**
     * 录一次解析产出。字段取的是三个探头解析器实际会写入的那些 setter 对应的值，
     * 外加油罐位/探头类型用于定位。
     *
     * <p>注意 {@code lastTime} 是解析时打的时间戳，重放时必然不同，
     * 录下来只作参考，断言时应排除。
     */
    public static void recordParsed(OilCan oilCan) {
        if (!ENABLED) {
            return;
        }
        if (oilCan == null) {
            Log.w(TAG, "hexDataParseFinish 收到 null OilCan，跳过本条录制");
            return;
        }
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("oilLevel", String.valueOf(oilCan.getOilLevel()));
        fields.put("waterLevel", String.valueOf(oilCan.getWaterLevel()));
        fields.put("temper", String.valueOf(oilCan.getTemper()));
        fields.put("oilVolume", String.valueOf(oilCan.getOilVolume()));
        fields.put("waterVolume", String.valueOf(oilCan.getWaterVolume()));
        fields.put("oilWeight", String.valueOf(oilCan.getOilWeight()));
        fields.put("oilRatio", String.valueOf(oilCan.getOilRatio()));
        fields.put("surPlus", String.valueOf(oilCan.getSurPlus()));
        fields.put("canAddOilWeight", String.valueOf(oilCan.getCanAddOilWeight()));
        fields.put("alarmByte", String.valueOf(oilCan.getAlarmByte()));
        fields.put("lastTime", String.valueOf(oilCan.getLastTime()));
        write(ProbeTrafficFormat.formatOut(System.currentTimeMillis(),
                oilCan.getProbeType(), oilCan.getOilCanPos(), fields));
    }

    private static void write(String line) {
        synchronized (LOCK) {
            BufferedWriter w = obtainWriter();
            if (w == null) {
                return;
            }
            try {
                w.write(line);
                w.newLine();
                // 每条都 flush：设备可能被直接断电，缓冲区里的数据会丢，
                // 而一天的采集重跑一次的代价远大于这点 IO。
                w.flush();
            } catch (IOException e) {
                Log.w(TAG, "写入录制文件失败，本条丢弃", e);
                closeQuietly();
            }
        }
    }

    /** 调用方必须持有 LOCK。 */
    private static BufferedWriter obtainWriter() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (writer != null && today.equals(writerDay)) {
            return writer;
        }
        closeQuietly();
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            if (!openFailureLogged) {
                Log.w(TAG, "外部存储不可用，探头报文录制未生效");
                openFailureLogged = true;
            }
            return null;
        }
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                if (!openFailureLogged) {
                    Log.w(TAG, "录制目录创建失败: " + dir.getAbsolutePath());
                    openFailureLogged = true;
                }
                return null;
            }
            File file = new File(dir, "probe-" + today + ".tsv");
            writer = new BufferedWriter(new FileWriter(file, true));
            writerDay = today;
            openFailureLogged = false;
            Log.i(TAG, "探头报文录制已开启: " + file.getAbsolutePath());
            return writer;
        } catch (IOException e) {
            if (!openFailureLogged) {
                Log.w(TAG, "录制文件打开失败", e);
                openFailureLogged = true;
            }
            return null;
        }
    }

    /** 调用方必须持有 LOCK。 */
    private static void closeQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            Log.w(TAG, "关闭录制文件失败", e);
        }
        writer = null;
        writerDay = null;
    }
}
