package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * golden master fixture 加载器（测试侧）。
 *
 * <p>把 {@code app/src/test/resources/goldenmaster/} 下由设备采集来的 {@code .tsv}
 * 加载成一组「输入 hex 报文 → 解析结果快照」的配对。
 *
 * <p><b>配对规则</b>：{@code probeInfoPase} 是同线程同步回调 {@code hexDataParseFinish}，
 * 所以一条 IN 之后、下一条 IN 之前的**全部** OUT 都是这一帧的产出。
 *
 * <p><b>一帧对多条，不是一对一。</b>两个原因：
 * <ul>
 *   <li>{@code LD2Company.probeInfoPase} 对帧内每个探针各回调一次
 *       （{@code for (i = 0; i < decimal; i++)}），一帧可产出 N 条</li>
 *   <li>分发 switch 的 {@code case 1} 缺 {@code break}（`docs/重构方案.md` 8.2），
 *       一帧 LD 报文先后经 {@code LDCompany} 与 {@code LD2Company} 处理，两者都会产出</li>
 * </ul>
 * 因此 {@link Pair#results} 是一个列表。早先版本只取第一条 OUT、其余静默丢弃，
 * 会让一个 4 罐站点的 fixture 只包含 1 号罐的基线，且从计数上完全看不出来 ——
 * 这类损失只有重跑一次现场才能发现。
 *
 * <p>某条 IN 之后直接跟下一条 IN（解析失败、CRC 错、帧被丢弃）时，
 * 记为 {@link Pair#results} 为空的**无产出配对** —— 这类样本同样有价值：
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
    private final int orphanOutRecords;

    private ProbeTrafficFixture(String name, List<Pair> pairs, int skippedLines, int orphanOutRecords) {
        this.name = name;
        this.pairs = Collections.unmodifiableList(pairs);
        this.skippedLines = skippedLines;
        this.orphanOutRecords = orphanOutRecords;
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
        int orphanOut = 0;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            ProbeTrafficFormat.Record pendingIn = null;
            List<ProbeTrafficFormat.Record> pendingOut = new ArrayList<ProbeTrafficFormat.Record>();
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
                        pairs.add(new Pair(pendingIn, pendingOut));
                        pendingOut = new ArrayList<ProbeTrafficFormat.Record>();
                    }
                    pendingIn = record;
                } else if (pendingIn != null) {
                    // 归入当前这一帧 —— 一帧可能产出多条，全部收下
                    pendingOut.add(record);
                } else {
                    // OUT 而无 pendingIn：文件被从中间截断。计入 orphan 而非静默丢弃，
                    // 让"数据有缺口"这件事在测试输出里可见。
                    orphanOut++;
                }
            }
            if (pendingIn != null) {
                pairs.add(new Pair(pendingIn, pendingOut));
            }
        } finally {
            reader.close();
        }
        return new ProbeTrafficFixture(resourceName, pairs, skipped, orphanOut);
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

    /**
     * 找不到所属 IN 的孤立 OUT 记录数。正常应为 0；非零说明文件被从中间截断，
     * 或录制期间发生了本类未预料的调用顺序 —— 两种都值得排查，不应静默吞掉。
     */
    public int orphanOutRecords() {
        return orphanOutRecords;
    }

    /** 全部配对中解析产出的总条数。用于识别"配对数正常但产出被吞掉"这类问题。 */
    public int totalResultCount() {
        int n = 0;
        for (Pair p : pairs) {
            n += p.results.size();
        }
        return n;
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

    /**
     * 一次「喂进去的报文 → 当时解析出的<b>全部</b>结果」。
     * {@link #results} 为空表示该输入未产生任何解析结果。
     */
    public static final class Pair {
        public final ProbeTrafficFormat.Record request;
        /** 本帧产出的全部结果，按录制顺序。可能为空、一条或多条，见类注释。 */
        public final List<ProbeTrafficFormat.Record> results;

        Pair(ProbeTrafficFormat.Record request, List<ProbeTrafficFormat.Record> results) {
            this.request = request;
            this.results = Collections.unmodifiableList(
                    new ArrayList<ProbeTrafficFormat.Record>(results));
        }

        public String hexFrame() {
            return request.hexFrame;
        }

        public boolean hasParsedResult() {
            return !results.isEmpty();
        }

        /** 只取指定解析器产出的结果。用于绕开 fall-through 造成的双解析器产出。 */
        public List<ProbeTrafficFormat.Record> resultsFrom(String parser) {
            List<ProbeTrafficFormat.Record> out = new ArrayList<ProbeTrafficFormat.Record>();
            for (ProbeTrafficFormat.Record r : results) {
                if (parser.equals(r.parser)) {
                    out.add(r);
                }
            }
            return out;
        }

        /** 本帧产出结果的解析器集合。含多于一个即命中了分发 switch 的 fall-through（8.2）。 */
        public Set<String> producingParsers() {
            Set<String> names = new LinkedHashSet<String>();
            for (ProbeTrafficFormat.Record r : results) {
                names.add(r.parser);
            }
            return names;
        }

        /**
         * 设备实际采用的那条结果 —— 同一油罐位若被多次写入，后者覆盖前者，
         * 所以取最后一条。这也是 8.2 缺 {@code break} 导致 LD 结果被 LD2 覆盖的体现。
         */
        public ProbeTrafficFormat.Record effectiveResult() {
            return results.isEmpty() ? null : results.get(results.size() - 1);
        }

        @Override
        public String toString() {
            return "probeType=" + request.probeType
                    + " oilCanPos=" + request.oilCanPos
                    + " 产出=" + results.size() + "条" + producingParsers()
                    + " frame=" + request.hexFrame;
        }
    }
}
