package com.sansen.yeweiyi.goldenmaster;

import com.sansen.yeweiyi.manager.ProbeTrafficFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析结果快照的逐字段比对器 —— golden master 等价性断言的判据。
 *
 * <p>用途：{@code §2} 抽出 {@code XxxParser} 之后，把新解析器对同一份 fixture 报文的产出，
 * 与现场录制下来的产出逐字段比对。**零差异**才允许合入（对应 spec 的「抽取前后行为等价」）。
 *
 * <p><b>为什么不直接 equals：</b>
 * <ul>
 *   <li>{@code lastTime} 之类的字段由采集时刻决定，与解析逻辑无关，必须排除
 *       （见 {@link ProbeTrafficFormat#VOLATILE_FIELDS}）</li>
 *   <li>浮点字段允许一个极小的容差 —— 但**默认是 0**，
 *       即默认要求逐位一致。放宽容差是一个需要显式作出的决定，不是默认行为</li>
 *   <li>差异必须能指出「哪个字段、期望什么、实际什么」，
 *       否则一个红测试无法定位到是哪一处解析逻辑变了</li>
 * </ul>
 */
public final class SnapshotComparator {

    private final double tolerance;

    /** 严格比对：浮点也要求逐位一致。这是默认，也应当是常态。 */
    public SnapshotComparator() {
        this(0.0d);
    }

    /**
     * @param tolerance 浮点字段允许的绝对误差。传非 0 值意味着你**明确决定**接受
     *                  抽取前后存在数值差异 —— 这个决定应当在提交信息里写明理由。
     */
    public SnapshotComparator(double tolerance) {
        this.tolerance = tolerance;
    }

    /**
     * 比对期望（现场录制的）与实际（新解析器产出的）两份字段快照。
     *
     * @return 差异列表；为空表示等价
     */
    public List<Difference> compare(Map<String, String> expected, Map<String, String> actual) {
        List<Difference> diffs = new ArrayList<Difference>();
        if (expected == null || actual == null) {
            if (expected != actual) {
                diffs.add(new Difference("<整条快照>",
                        expected == null ? "null" : "非null",
                        actual == null ? "null" : "非null"));
            }
            return diffs;
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String key = e.getKey();
            if (ProbeTrafficFormat.VOLATILE_FIELDS.contains(key)) {
                continue;
            }
            String expectedValue = e.getValue();
            if (!actual.containsKey(key)) {
                diffs.add(new Difference(key, expectedValue, "<缺失>"));
                continue;
            }
            String actualValue = actual.get(key);
            if (!valuesEqual(expectedValue, actualValue)) {
                diffs.add(new Difference(key, expectedValue, actualValue));
            }
        }
        for (String key : actual.keySet()) {
            if (!ProbeTrafficFormat.VOLATILE_FIELDS.contains(key) && !expected.containsKey(key)) {
                diffs.add(new Difference(key, "<缺失>", actual.get(key)));
            }
        }
        return diffs;
    }

    private boolean valuesEqual(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (tolerance <= 0.0d) {
            return false;
        }
        try {
            double a = Double.parseDouble(expected);
            double b = Double.parseDouble(actual);
            // NaN 不参与容差比较：两边都 NaN 时上面的 equals 已经命中
            return !Double.isNaN(a) && !Double.isNaN(b) && Math.abs(a - b) <= tolerance;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 这里曾有一个 snapshotOf(Map) 归一化方法，已删除。
    // 它只拷贝 OUT_FIELDS 里的键，等于把上面"多出字段算作差异"这条规则架空：
    // §2 抽出的解析器若多产出或改名了某个字段，经它归一化后会被静默丢掉，
    // 等价性闸门报零差异。作为 §2 解析器的桥接入口，它削弱的正是自己要守的那道闸。
    // §2 接入时请直接构造字段表传给 compare()，不要再引入这类"顺手过滤"的中间层。

    /** 一处字段差异。 */
    public static final class Difference {
        public final String field;
        public final String expected;
        public final String actual;

        Difference(String field, String expected, String actual) {
            this.field = field;
            this.expected = expected;
            this.actual = actual;
        }

        @Override
        public String toString() {
            return field + ": 录制值=" + expected + " 实际值=" + actual;
        }
    }
}
