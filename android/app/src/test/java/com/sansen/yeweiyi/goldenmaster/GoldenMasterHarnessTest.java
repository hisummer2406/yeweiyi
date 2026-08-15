package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * golden master 骨架自身的测试。
 *
 * <p>这批测试**不需要任何现场数据**，现在就能跑。它们要证明的是：等现场数据回来时，
 * 加载器读得对、比对器判得准。骨架不可信的话，现场采集回来的数据也没有意义 ——
 * 而那时才发现问题的代价是重跑一次现场采集。
 *
 * <p>真正用现场报文做重放的测试见 {@link ProbeReplayTest}。
 */
public class GoldenMasterHarnessTest {

    // ---------- 格式：写入方与读取方共用同一份定义，往返必须无损 ----------

    @Test
    public void formatIn往返后字段不变() {
        String line = ProbeTrafficFormat.formatIn(1756684800000L, 1, 3, "0103040000000AB8FA");
        ProbeTrafficFormat.Record r = ProbeTrafficFormat.parse(line);

        assertNotNull(r);
        assertTrue(r.isIn());
        assertFalse(r.isOut());
        assertEquals(1756684800000L, r.timestamp);
        assertEquals(1, r.probeType);
        assertEquals(3, r.oilCanPos);
        assertEquals("0103040000000AB8FA", r.hexFrame);
    }

    @Test
    public void formatIn去掉hex中的空格() {
        // 录制点拿到的 data 可能是 bytesToHexString(bytes, size) 的产物，那个重载是带空格的
        // （见 CLAUDE.md "三个 bytesToHexString 重载"）。fixture 里统一存无空格形式。
        String line = ProbeTrafficFormat.formatIn(1L, 0, 1, "01 03 04 00");
        assertEquals("01030400", ProbeTrafficFormat.parse(line).hexFrame);
    }

    @Test
    public void 末位空字段仍能读回来() {
        // 回归：parse 曾对整行 trim()，把 formatIn(null) 写出的行尾空字段连同分隔符一起吃掉，
        // 导致列数不足被判为非法行 —— 写得出却读不回来，往返保证不成立。
        String line = ProbeTrafficFormat.formatIn(1L, 0, 1, null);
        ProbeTrafficFormat.Record r = ProbeTrafficFormat.parse(line);

        assertNotNull("末位为空字段的行必须能解析", r);
        assertEquals("", r.hexFrame);
    }

    @Test
    public void 行尾换行符不影响解析() {
        String line = ProbeTrafficFormat.formatIn(1L, 0, 1, "AABB");
        assertNotNull(ProbeTrafficFormat.parse(line + "\n"));
        assertNotNull(ProbeTrafficFormat.parse(line + "\r\n"));
        assertEquals("AABB", ProbeTrafficFormat.parse(line + "\r\n").hexFrame);
    }

    @Test
    public void formatOut往返后每个字段与解析器都还原() {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("oilLevel", "1234.0");
        fields.put("temper", "-5.5");
        fields.put("alarmByte", "0");

        ProbeTrafficFormat.Record r = ProbeTrafficFormat.parse(
                ProbeTrafficFormat.formatOut(999L, 2, 7, "LD2Company", fields));

        assertNotNull(r);
        assertTrue(r.isOut());
        assertEquals(2, r.probeType);
        assertEquals(7, r.oilCanPos);
        assertEquals("LD2Company", r.parser);
        assertEquals(3, r.fields.size());
        assertEquals("1234.0", r.fields.get("oilLevel"));
        // 负温度：本设备会读到负值，格式层不能把负号弄丢
        assertEquals("-5.5", r.fields.get("temper"));
        assertEquals("0", r.fields.get("alarmByte"));
    }

    @Test
    public void 解析器缺省时记为占位符() {
        ProbeTrafficFormat.Record r = ProbeTrafficFormat.parse(
                ProbeTrafficFormat.formatOut(1L, 0, 1, null, new LinkedHashMap<String, String>()));
        assertEquals(ProbeTrafficFormat.UNKNOWN_PARSER, r.parser);
    }

    @Test
    public void parse对非法输入一律返回null而不抛异常() {
        // 采集文件可能因断电以半行结尾，加载器要能跳过而不是让整份 fixture 作废
        assertNull(ProbeTrafficFormat.parse(null));
        assertNull(ProbeTrafficFormat.parse(""));
        assertNull(ProbeTrafficFormat.parse("   "));
        assertNull(ProbeTrafficFormat.parse("随便一行不是记录的文字"));
        assertNull(ProbeTrafficFormat.parse("IN\t123\t1"));                  // 列数不足
        assertNull(ProbeTrafficFormat.parse("XX\t123\t1\t3\tAABB"));         // 类型非法
        assertNull(ProbeTrafficFormat.parse("IN\tnotanumber\t1\t3\tAABB"));  // 时间戳非法
        assertNull(ProbeTrafficFormat.parse("IN\t123\tx\t3\tAABB"));         // 探头类型非法
    }

    @Test
    public void lastTime被列为易变字段() {
        // lastTime 是解析时打的时间戳，重放时必然不同。它必须在排除名单里，
        // 否则每一条重放断言都会因为这个字段红掉。
        assertTrue(ProbeTrafficFormat.VOLATILE_FIELDS.contains("lastTime"));
        assertTrue(ProbeTrafficFormat.OUT_FIELDS.contains("lastTime"));
    }

    // ---------- 加载器：一帧 IN 对应它之后、下一条 IN 之前的全部 OUT ----------

    private ProbeTrafficFixture fixture() throws Exception {
        ProbeTrafficFixture f = ProbeTrafficFixture.load("synthetic-loader-check.tsv");
        assertNotNull("合成样本应当存在于 test resources", f);
        return f;
    }

    @Test
    public void 加载器正确切分配对并统计异常() throws Exception {
        ProbeTrafficFixture f = fixture();

        assertEquals("应得到 5 组配对", 5, f.pairs().size());
        assertEquals("应跳过 2 行非法记录", 2, f.skippedLines());
        assertEquals("应记录 1 条孤立 OUT", 1, f.orphanOutRecords());
        assertEquals("产出总数应为 6 条", 6, f.totalResultCount());
    }

    @Test
    public void 一帧产出多条时全部保留() throws Exception {
        // 回归（HIGH）：早先版本一帧只取第一条 OUT，其余静默丢弃且不计数。
        // LD2Company 对帧内每个探针各回调一次，一个 4 罐站点会因此只留下 1 号罐的基线，
        // 而计数上完全看不出来 —— 这类损失只有重跑一次现场才能发现。
        ProbeTrafficFixture.Pair multi = fixture().pairsOfProbeType(2).get(0);

        assertEquals("一帧 LD2 报文的 3 条产出必须全部保留", 3, multi.results.size());
        assertEquals(5, multi.results.get(0).oilCanPos);
        assertEquals(6, multi.results.get(1).oilCanPos);
        assertEquals(7, multi.results.get(2).oilCanPos);
    }

    @Test
    public void 能识别一帧被两个解析器先后处理() throws Exception {
        // 回归（HIGH）：分发 switch 的 case 1 缺 break（8.2），LD 帧会先后经
        // LDCompany 与 LD2Company 解析。若不记录产出者，fixture 会把 LDCompany 的产出
        // 当作 probeType 1 的基线，而设备实际采用的是 LD2Company 的 —— 用这样的基线
        // 验证抽取出的 LDParser 会显示零差异，却与真实行为背离。
        ProbeTrafficFixture.Pair fallThrough = null;
        for (ProbeTrafficFixture.Pair p : fixture().pairsOfProbeType(1)) {
            if (p.producingParsers().size() > 1) {
                fallThrough = p;
            }
        }
        assertNotNull("合成样本里应有一组 fall-through 配对", fallThrough);

        assertEquals(Arrays.asList("LDCompany", "LD2Company"),
                new java.util.ArrayList<String>(fallThrough.producingParsers()));
        assertEquals(1, fallThrough.resultsFrom("LDCompany").size());
        assertEquals(1, fallThrough.resultsFrom("LD2Company").size());

        // 设备实际采用的是最后写入的那条 —— 即 LD2Company 的结果覆盖了 LDCompany 的
        assertEquals("LD2Company", fallThrough.effectiveResult().parser);
        assertEquals("2222.0", fallThrough.effectiveResult().fields.get("oilLevel"));
    }

    @Test
    public void 无产出的配对被保留() throws Exception {
        // IN 后直接跟 IN（解析失败/CRC 错），以及以 IN 结尾（断电截断）。
        // 这类样本锁定的是"这种输入不产生解析结果"这一行为，同样有价值。
        int empty = 0;
        for (ProbeTrafficFixture.Pair p : fixture().pairs()) {
            if (!p.hasParsedResult()) {
                empty++;
                assertTrue("无产出配对的 results 应为空", p.results.isEmpty());
                assertNull(p.effectiveResult());
            }
        }
        assertEquals(2, empty);
    }

    @Test
    public void 可按探头类型筛选配对() throws Exception {
        ProbeTrafficFixture f = fixture();
        assertEquals(2, f.pairsOfProbeType(0).size());   // 中诚
        assertEquals(2, f.pairsOfProbeType(1).size());   // LD
        assertEquals(1, f.pairsOfProbeType(2).size());   // LD2
        assertEquals(0, f.pairsOfProbeType(99).size());
    }

    @Test
    public void 资源不存在时返回null而不抛异常() throws Exception {
        // 现场数据尚未采集回来时就是这种情况，ProbeReplayTest 靠它判断是否跳过
        assertNull(ProbeTrafficFixture.load("根本不存在的文件.tsv"));
    }

    // ---------- 比对器：等价性断言的判据 ----------

    @Test
    public void 完全相同的快照无差异() {
        assertTrue(new SnapshotComparator()
                .compare(snapshot("1234.0", "21.5"), snapshot("1234.0", "21.5")).isEmpty());
    }

    @Test
    public void 任一字段不同都要被指出且能定位() {
        List<SnapshotComparator.Difference> diffs =
                new SnapshotComparator().compare(snapshot("1234.0", "21.5"), snapshot("1234.5", "21.5"));

        assertEquals(1, diffs.size());
        assertEquals("oilLevel", diffs.get(0).field);
        assertEquals("1234.0", diffs.get(0).expected);
        assertEquals("1234.5", diffs.get(0).actual);
    }

    @Test
    public void 默认零容差下浮点必须逐位一致() {
        // 默认严格。放宽容差必须是显式决定，不能是默认行为。
        assertFalse(new SnapshotComparator()
                .compare(snapshot("1234.0", "21.5"), snapshot("1234.0000001", "21.5"))
                .isEmpty());

        assertTrue(new SnapshotComparator(0.001d)
                .compare(snapshot("1234.0", "21.5"), snapshot("1234.0000001", "21.5"))
                .isEmpty());
    }

    @Test
    public void 易变字段被排除在比对之外() {
        Map<String, String> expected = snapshot("1234.0", "21.5");
        expected.put("lastTime", "1000");
        Map<String, String> actual = snapshot("1234.0", "21.5");
        actual.put("lastTime", "999999");

        assertTrue("lastTime 不同不应算作差异",
                new SnapshotComparator().compare(expected, actual).isEmpty());
    }

    @Test
    public void 字段缺失或多出都算差异() {
        Map<String, String> expected = snapshot("1234.0", "21.5");
        Map<String, String> missing = new LinkedHashMap<String, String>();
        missing.put("oilLevel", "1234.0");

        List<SnapshotComparator.Difference> diffs = new SnapshotComparator().compare(expected, missing);
        assertEquals(1, diffs.size());
        assertEquals("temper", diffs.get(0).field);
        assertEquals("<缺失>", diffs.get(0).actual);

        // 反向：实际多出一个字段。§2 的解析器若多产出或改名了字段，必须在这里红掉。
        Map<String, String> extra = snapshot("1234.0", "21.5");
        extra.put("oilVolume", "800.0");
        diffs = new SnapshotComparator().compare(expected, extra);
        assertEquals(1, diffs.size());
        assertEquals("oilVolume", diffs.get(0).field);
        assertEquals("<缺失>", diffs.get(0).expected);
    }

    @Test
    public void 非数值字段不受容差影响() {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("oilLevel", "abc");
        Map<String, String> actual = new LinkedHashMap<String, String>();
        actual.put("oilLevel", "abd");

        assertFalse(new SnapshotComparator(1000d).compare(expected, actual).isEmpty());
    }

    private static Map<String, String> snapshot(String oilLevel, String temper) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("oilLevel", oilLevel);
        m.put("temper", temper);
        return m;
    }
}
