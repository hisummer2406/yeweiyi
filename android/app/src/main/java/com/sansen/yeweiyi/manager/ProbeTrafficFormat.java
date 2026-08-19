package com.sansen.yeweiyi.manager;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探头报文录制文件的格式定义 —— 写入方与读取方共用的唯一一份。
 *
 * <p>写入方是 {@link ProbeTrafficRecorder}（跑在设备上，依赖 Android IO）；
 * 读取方是测试侧的 fixture 加载器（跑在纯 JVM 上）。两边如果各写各的解析，
 * 格式一旦漂移，要等到现场采完一天数据、回来发现读不出来才会暴露 —— 那时补采一次的代价极高。
 * 所以格式的**定义与解析都放在这里**，且本类不 import 任何 {@code android.*}，
 * 可以直接在 JVM 单测里做「格式化 → 解析」的往返验证。
 *
 * <p>行格式（Tab 分隔，首列为记录类型）：
 * <pre>
 * IN   &lt;epochMillis&gt;  &lt;probeType&gt;  &lt;oilCanPos&gt;  &lt;hex报文&gt;
 * OUT  &lt;epochMillis&gt;  &lt;probeType&gt;  &lt;oilCanPos&gt;  &lt;解析器&gt;  &lt;key&gt;=&lt;value&gt;  ...
 * </pre>
 *
 * <p>hex 报文与字段值都不含 Tab，因此可直接按 Tab 切分，无需转义。
 *
 * <p><b>OUT 里为什么要记解析器身份：</b>一帧报文可能被不止一个解析器处理。
 * `SerialPortManager` 的分发 switch 在 {@code case 1} 缺 {@code break}（见 `docs/重构方案.md` 8.2），
 * 因此一帧 LD 报文会先后经 {@code LDCompany} 与 {@code LD2Company} 解析，后者的结果覆盖前者。
 * 若不记录是谁产出的，fixture 会把 LDCompany 的产出当作"probeType 1 的正确基线"，
 * 而设备实际采用的是 LD2Company 的产出 —— 用这样的基线去验证抽取出的 LDParser 会显示零差异，
 * 却与真实行为背离，正是 golden master 要防止的那类失败。
 */
public final class ProbeTrafficFormat {

    public static final String SEP = "\t";

    public static final String TYPE_IN = "IN";
    public static final String TYPE_OUT = "OUT";

    /** 产出解析器未知时的占位符。出现它说明录制点没能识别是谁产出的，属于需要排查的情况。 */
    public static final String UNKNOWN_PARSER = "?";

    /**
     * OUT 记录里的字段及其顺序 —— 取自三个探头解析器实际会写入的那些 setter。
     *
     * <p>{@code lastTime} 是解析时打的时间戳，重放时必然不同，
     * 录下来只作参考，等价性断言应排除，见 {@link #VOLATILE_FIELDS}。
     */
    public static final List<String> OUT_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "oilLevel",
            "waterLevel",
            "temper",
            "oilVolume",
            "waterVolume",
            "oilWeight",
            "oilRatio",
            "surPlus",
            "canAddOilWeight",
            "alarmByte",
            "lastTime"
    ));

    /** 逐字段比对时必须排除的字段：其值由采集时刻决定，与解析逻辑无关。 */
    public static final List<String> VOLATILE_FIELDS = Collections.unmodifiableList(
            Collections.singletonList("lastTime"));

    private ProbeTrafficFormat() {
    }

    public static String formatIn(long timestamp, int probeType, int oilCanPos, String hexFrame) {
        return TYPE_IN + SEP + timestamp + SEP + probeType + SEP + oilCanPos + SEP
                + (hexFrame == null ? "" : hexFrame.replace(" ", ""));
    }

    public static String formatOut(long timestamp, int probeType, int oilCanPos,
                                   String parser, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(TYPE_OUT).append(SEP).append(timestamp).append(SEP)
                .append(probeType).append(SEP).append(oilCanPos).append(SEP)
                .append(parser == null || parser.isEmpty() ? UNKNOWN_PARSER : parser);
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                sb.append(SEP).append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * 解析一行记录。
     *
     * @return 解析结果；行为空、列数不足或类型/数字字段非法时返回 {@code null}
     *         —— 采集文件可能因断电而以半行结尾，调用方按「跳过并计数」处理即可。
     */
    public static Record parse(String line) {
        if (line == null) {
            return null;
        }
        // 只剥掉行尾的换行符，不能用 trim()：末位字段可能本就是空字符串
        // （formatIn 对 null 报文写出的就是这种行），trim 会把那一列的分隔符一起吃掉，
        // 导致列数不足而误判为非法行 —— 写得出却读不回来，往返保证就不成立了。
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        String stripped = line.substring(0, end);
        if (stripped.trim().isEmpty()) {
            return null;
        }
        String[] parts = stripped.split(SEP, -1);
        if (parts.length < 5) {
            return null;
        }
        String type = parts[0];
        if (!TYPE_IN.equals(type) && !TYPE_OUT.equals(type)) {
            return null;
        }
        long timestamp;
        int probeType;
        int oilCanPos;
        try {
            timestamp = Long.parseLong(parts[1]);
            probeType = Integer.parseInt(parts[2]);
            oilCanPos = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (TYPE_IN.equals(type)) {
            return new Record(type, timestamp, probeType, oilCanPos, parts[4], null, null);
        }
        String parser = parts[4].isEmpty() ? UNKNOWN_PARSER : parts[4];
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 5; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            fields.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return new Record(type, timestamp, probeType, oilCanPos, null, parser, fields);
    }

    /** 一条录制记录。IN 携带 {@link #hexFrame}，OUT 携带 {@link #parser} 与 {@link #fields}，另一方为 {@code null}。 */
    public static final class Record {
        public final String type;
        public final long timestamp;
        public final int probeType;
        public final int oilCanPos;
        public final String hexFrame;
        /** 产出本条结果的解析器简名（如 {@code LDCompany}）。仅 OUT 记录有值。 */
        public final String parser;
        public final Map<String, String> fields;

        Record(String type, long timestamp, int probeType, int oilCanPos,
               String hexFrame, String parser, Map<String, String> fields) {
            this.type = type;
            this.timestamp = timestamp;
            this.probeType = probeType;
            this.oilCanPos = oilCanPos;
            this.hexFrame = hexFrame;
            this.parser = parser;
            this.fields = fields == null
                    ? null : Collections.unmodifiableMap(fields);
        }

        public boolean isIn() {
            return TYPE_IN.equals(type);
        }

        public boolean isOut() {
            return TYPE_OUT.equals(type);
        }
    }
}
