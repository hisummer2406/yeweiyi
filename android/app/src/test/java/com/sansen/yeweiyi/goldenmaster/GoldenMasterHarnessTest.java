package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import org.junit.Test;

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
    public void formatOut往返后每个字段都还原() {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("oilLevel", "1234.0");
        fields.put("temper", "-5.5");
        fields.put("alarmByte", "0");

        ProbeTrafficFormat.Record r = ProbeTrafficFormat.parse(
                ProbeTrafficFormat.formatOut(999L, 2, 7, fields));

        assertNotNull(r);
        assertTrue(r.isOut());
        assertEquals(2, r.probeType);
        assertEquals(7, r.oilCanPos);
        assertEquals(3, r.fields.size());
        assertEquals("1234.0", r.fields.get("oilLevel"));
        // 负温度：本设备会读到负值，格式层不能把负号弄丢
        assertEquals("-5.5", r.fields.get("temper"));
        assertEquals("0", r.fields.get("alarmByte"));
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

    // ---------- 加载器：IN/OUT 按顺序配对 ----------

    @Test
    public void 加载器按顺序配对并容忍脏行() throws Exception {
        ProbeTrafficFixture fixture = ProbeTrafficFixture.load("synthetic-loader-check.tsv");
        assertNotNull("合成样本应当存在于 test resources", fixture);

        List<ProbeTrafficFixture.Pair> pairs = fixture.pairs();
        assertEquals("应得到 4 组配对", 4, pairs.size());
        assertEquals("应跳过 2 行非法记录", 2, fixture.skippedLines());

        // 1) 正常配对
        assertTrue(pairs.get(0).hasParsedResult());
        assertEquals("0103040000000AB8FA", pairs.get(0).hexFrame());
        assertEquals("1234.0", pairs.get(0).parsed.fields.get("oilLevel"));

        // 2) IN 之后直接跟下一条 IN —— 无产出配对，同样要保留：
        //    它锁定的是"这种输入不产生解析结果"这一行为
        assertFalse(pairs.get(1).hasParsedResult());
        assertEquals("0103040000FFFFDEAD", pairs.get(1).hexFrame());

        // 3) 另一种探头类型的正常配对
        assertTrue(pairs.get(2).hasParsedResult());
        assertEquals(0, pairs.get(2).request.probeType);

        // 4) 文件以没有 OUT 的 IN 结尾（断电截断）
        assertFalse(pairs.get(3).hasParsedResult());
    }

    @Test
    public void 可按探头类型筛选配对() throws Exception {
        ProbeTrafficFixture fixture = ProbeTrafficFixture.load("synthetic-loader-check.tsv");
        assertNotNull(fixture);

        assertEquals(1, fixture.pairsOfProbeType(0).size());  // 中诚
        assertEquals(2, fixture.pairsOfProbeType(1).size());  // LD
        assertEquals(1, fixture.pairsOfProbeType(2).size());  // LD2
        assertEquals(0, fixture.pairsOfProbeType(99).size());
    }

    @Test
    public void 资源不存在时返回null而不抛异常() throws Exception {
        // 现场数据尚未采集回来时就是这种情况，ProbeReplayTest 靠它判断是否跳过
        assertNull(ProbeTrafficFixture.load("根本不存在的文件.tsv"));
    }

    // ---------- 比对器：等价性断言的判据 ----------

    @Test
    public void 完全相同的快照无差异() {
        Map<String, String> a = snapshot("1234.0", "21.5");
        assertTrue(new SnapshotComparator().compare(a, snapshot("1234.0", "21.5")).isEmpty());
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

        // 反向：实际多出一个字段
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
