package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/**
 * 现场报文重放测试 —— golden master 的落点。
 *
 * <h3>当前状态：等待现场数据（tasks.md §1.6）</h3>
 *
 * <p>{@code app/src/test/resources/goldenmaster/manifest.tsv} 里没有条目时，本类整体跳过。
 * 这是现场采集尚未完成时的正常状态，不是失败。数据一旦导入（{@code tools/prepare-probe-fixture.sh}），
 * 这些断言自动生效。
 *
 * <h3>为什么这里不重放"改动前的代码"</h3>
 *
 * <p>tasks.md §1.8 最初写的是"喂入报文，断言解析结果与录制快照一致，此时测的是尚未改动的现有代码"。
 * **那做不到**：{@code LDCompany}/{@code LD2Company}/{@code ZhongChengCompany} 三个解析器都
 * {@code import android.content.Context}、{@code SparseArray}，且 {@code probeInfoPase} 内部会调
 * {@code MyApplication.getInstance().getmOilcanDataManager()} —— 纯 JVM 跑不起来，这正是 §2 要解决的问题本身。
 *
 * <p>但这不构成阻塞，因为**录制文件本身就是基线**。.tsv 里的每一对 (输入报文 → 解析结果)
 * 都是真实硬件在真实工况下产生的，它比"在 JVM 上拿 mock 单例重放旧代码"是更强的证据。
 * 所以：
 * <ul>
 *   <li><b>现在</b>（§1）—— 本类校验采集数据本身的完整性与合理性。
 *       目的是在刚从现场回来时就发现"这批数据不能用"，而不是等建完解析器才发现。
 *       重采一次现场的代价极高。</li>
 *   <li><b>§2 抽出 {@code XxxParser} 之后</b> —— 把 {@link #重放等价性_待第2组抽出Parser后接入()}
 *       接上新解析器，用 {@link SnapshotComparator} 逐字段比对，零差异才允许合入。</li>
 * </ul>
 */
public class ProbeReplayTest {

    /** 加载清单中登记的全部 fixture；没有登记条目时让调用方跳过。 */
    private List<ProbeTrafficFixture> loadRegisteredFixtures() throws Exception {
        List<FixtureManifest.Entry> entries = FixtureManifest.load();
        assumeFalse("现场数据尚未采集回来（manifest.tsv 无条目），跳过重放测试 —— 见 tasks.md §1.6",
                entries.isEmpty());

        List<ProbeTrafficFixture> fixtures = new ArrayList<ProbeTrafficFixture>();
        for (FixtureManifest.Entry entry : entries) {
            ProbeTrafficFixture fixture = ProbeTrafficFixture.load(entry.fileName);
            assertNotNull("清单登记了 " + entry.fileName + " 但资源不存在", fixture);
            fixtures.add(fixture);
        }
        return fixtures;
    }

    @Test
    public void 每份登记的fixture都能加载且非空() throws Exception {
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            assertFalse(fixture.name() + " 里一组配对都没有，这份采集数据不可用",
                    fixture.pairs().isEmpty());
        }
    }

    @Test
    public void 采集数据里必须有成功解析的样本() throws Exception {
        // 全是"无产出"配对意味着这次采集全程解析失败（探头没接上、波特率不对等）。
        // 这种数据看起来有几万行，实际一条都用不了 —— 必须在从现场回来时立刻发现。
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            int withResult = 0;
            for (ProbeTrafficFixture.Pair pair : fixture.pairs()) {
                if (pair.hasParsedResult()) {
                    withResult++;
                }
            }
            assertTrue(fixture.name() + " 中没有任何一条成功解析的样本（共 "
                            + fixture.pairs().size() + " 组配对），这次采集无效",
                    withResult > 0);
        }
    }

    @Test
    public void 解析快照的字段齐全且可解析为数值() throws Exception {
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            for (ProbeTrafficFixture.Pair pair : fixture.pairs()) {
                for (ProbeTrafficFormat.Record result : pair.results) {
                    for (String field : ProbeTrafficFormat.OUT_FIELDS) {
                        String value = result.fields.get(field);
                        assertNotNull(fixture.name() + " 的快照缺少字段 " + field + "：" + pair, value);
                        assertFalse(fixture.name() + " 的字段 " + field + " 为空：" + pair,
                                value.isEmpty());
                        try {
                            Double.parseDouble(value);
                        } catch (NumberFormatException e) {
                            fail(fixture.name() + " 的字段 " + field + " 不是数值（" + value + "）：" + pair);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void 采集文件不应有孤立的解析结果() throws Exception {
        // 孤立 OUT = 找不到所属输入帧，说明文件被从中间截断，或录制期间出现了未预料的调用顺序。
        // 少量可以接受（尾部截断），大量则说明这份采集不可信。
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            assertTrue(fixture.name() + " 有 " + fixture.orphanOutRecords()
                            + " 条孤立 OUT（总产出 " + fixture.totalResultCount() + " 条），数据完整性存疑",
                    fixture.orphanOutRecords() <= 1);
        }
    }

    @Test
    public void 记录下每帧被哪些解析器处理过() throws Exception {
        // 不做断言，只把 fall-through 的实际发生情况打出来：
        // probeType 1 的帧若同时出现 LDCompany 与 LD2Company，就是 8.2 那个缺失的 break
        // 在现场真实发生的证据 —— 这决定了 §2 抽取 LDParser 时该拿哪一条当基线。
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            Map<String, Integer> byParserSet = new TreeMap<String, Integer>();
            for (ProbeTrafficFixture.Pair pair : fixture.pairs()) {
                if (!pair.hasParsedResult()) {
                    continue;
                }
                String key = "probeType=" + pair.request.probeType + " " + pair.producingParsers();
                Integer n = byParserSet.get(key);
                byParserSet.put(key, n == null ? 1 : n + 1);
            }
            System.out.println("[golden master] " + fixture.name() + " 解析器分布：");
            for (Map.Entry<String, Integer> e : byParserSet.entrySet()) {
                System.out.println("    " + e.getKey() + " -> " + e.getValue() + " 帧");
            }
        }
    }

    @Test
    public void 清单声明的探头型号确实出现在数据里() throws Exception {
        // 防止"以为采到了三种探头，实际只采到一种"。覆盖面写在清单里，
        // 但真实覆盖面只有数据说了算。
        List<FixtureManifest.Entry> entries = FixtureManifest.load();
        assumeFalse("现场数据尚未采集回来，跳过 —— 见 tasks.md §1.6", entries.isEmpty());

        for (FixtureManifest.Entry entry : entries) {
            ProbeTrafficFixture fixture = ProbeTrafficFixture.load(entry.fileName);
            assertNotNull(fixture);
            for (Integer probeType : entry.probeTypes) {
                assertFalse("清单声明 " + entry.fileName + " 覆盖探头型号 " + probeType
                                + "，但数据里一条都没有",
                        fixture.pairsOfProbeType(probeType).isEmpty());
            }
        }
    }

    /**
     * §2 的落点：抽出 {@code XxxParser} 后在这里做等价性断言。
     *
     * <p>届时把本方法改成大致这样（对应 tasks.md §2.2、§2.3）：
     * <pre>
     * for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
     *     for (Pair pair : fixture.pairsOfProbeType(1)) {          // 逐个探头型号
     *         if (!pair.hasParsedResult()) continue;
     *         Map&lt;String, String&gt; actual = LDParser.parse(pair.hexFrame()).toSnapshot();
     *         List&lt;Difference&gt; diffs = new SnapshotComparator().compare(pair.parsed.fields, actual);
     *         assertTrue("与现场录制结果不一致 " + pair + " -&gt; " + diffs, diffs.isEmpty());
     *     }
     * }
     * </pre>
     *
     * <p>在此之前它只断言比对器在"自己跟自己比"时判定为等价 —— 这保证接上真解析器时，
     * 红掉一定是解析器的问题，而不是比对器本身有毛病。
     */
    @Test
    public void 重放等价性_待第2组抽出Parser后接入() throws Exception {
        for (ProbeTrafficFixture fixture : loadRegisteredFixtures()) {
            SnapshotComparator comparator = new SnapshotComparator();
            for (ProbeTrafficFixture.Pair pair : fixture.pairs()) {
                for (ProbeTrafficFormat.Record result : pair.results) {
                    assertTrue("比对器对同一份快照判出了差异，说明比对器本身有问题",
                            comparator.compare(result.fields, result.fields).isEmpty());
                }
            }
        }
    }
}
