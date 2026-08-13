package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * golden master fixture 加载器（测试侧）。
 *
 * <p>把 {@code app/src/test/resources/goldenmaster/} 下由设备采集来的 {@code .tsv}
 * 加载成一组「输入 hex 报文 → 解析结果快照」的配对。
 *
 * <p><b>配对规则</b>：{@code probeInfoPase} 是同线程同步回调 {@code hexDataParseFinish}，
 * 所以文件里一条 IN 之后的第一条 OUT 就是它的产出。据此顺序配对，不需要关联 id。
 * 若某条 IN 之后直接跟了下一条 IN（解析失败、CRC 错、或帧被丢弃），那条 IN 记为
 * {@link Pair#parsed} 为 {@code null} 的**无产出配对** —— 这类样本同样有价值：
 * 它锁定的是「这种输入不会产生解析结果」这一行为。
 *
 * <p>格式的定义与解析都在 {@link ProbeTrafficFormat}，与设备端写入方共用同一份，
 * 因此这里不自己拆行。
 */
public final class ProbeTrafficFixture {

    /** fixture 在 test resources 下的根目录。 */
    public static final String ROOT = "/goldenmaster/";

    private final String name;
    private final List<Pair> pairs;
    private final int skippedLines;

    private ProbeTrafficFixture(String name, List<Pair> pairs, int skippedLines) {
        this.name = name;
        this.pairs = Collections.unmodifiableList(pairs);
        this.skippedLines = skippedLines;
    }

    /**
     * 加载一份 fixture。
     *
     * @param resourceName {@link #ROOT} 下的文件名，例如 {@code "probe-20260901-ld.tsv"}
     * @return fixture；资源不存在时返回 {@code null}（现场数据尚未采集回来时就是这种情况）
     */
    public static ProbeTrafficFixture load(String resourceName) throws IOException {
        InputStream in = ProbeTrafficFixture.class.getResourceAsStream(ROOT + resourceName);
        if (in == null) {
            return null;
        }
        List<Pair> pairs = new ArrayList<Pair>();
        int skipped = 0;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            ProbeTrafficFormat.Record pendingIn = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                ProbeTrafficFormat.Record record = ProbeTrafficFormat.parse(line);
                if (record == null) {
                    // 采集文件可能因断电以半行结尾，跳过并计数，不让整份 fixture 作废
                    skipped++;
                    continue;
                }
                if (record.isIn()) {
                    if (pendingIn != null) {
                        pairs.add(new Pair(pendingIn, null));
                    }
                    pendingIn = record;
                } else if (pendingIn != null) {
                    pairs.add(new Pair(pendingIn, record));
                    pendingIn = null;
                }
                // OUT 而无 pendingIn：文件被从中间截断，丢弃
            }
            if (pendingIn != null) {
                pairs.add(new Pair(pendingIn, null));
            }
        } finally {
            reader.close();
        }
        return new ProbeTrafficFixture(resourceName, pairs, skipped);
    }

    public String name() {
        return name;
    }

    public List<Pair> pairs() {
        return pairs;
    }

    /** 因格式非法被跳过的行数。非零不一定是问题（断电截断），但值得在测试输出里可见。 */
    public int skippedLines() {
        return skippedLines;
    }

    /** 只取指定 probeType 的配对（0 中诚 / 1 LD / 2 LD2）。 */
    public List<Pair> pairsOfProbeType(int probeType) {
        List<Pair> result = new ArrayList<Pair>();
        for (Pair p : pairs) {
            if (p.request.probeType == probeType) {
                result.add(p);
            }
        }
        return result;
    }

    /** 一次「喂进去的报文 → 当时解析出的结果」。{@link #parsed} 为 null 表示该输入未产生解析结果。 */
    public static final class Pair {
        public final ProbeTrafficFormat.Record request;
        public final ProbeTrafficFormat.Record parsed;

        Pair(ProbeTrafficFormat.Record request, ProbeTrafficFormat.Record parsed) {
            this.request = request;
            this.parsed = parsed;
        }

        public String hexFrame() {
            return request.hexFrame;
        }

        public boolean hasParsedResult() {
            return parsed != null;
        }

        @Override
        public String toString() {
            return "probeType=" + request.probeType
                    + " oilCanPos=" + request.oilCanPos
                    + " frame=" + request.hexFrame;
        }
    }
}
