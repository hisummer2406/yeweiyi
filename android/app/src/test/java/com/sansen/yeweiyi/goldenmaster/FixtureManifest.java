package com.sansen.yeweiyi.goldenmaster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * fixture 清单 —— 记录每份现场采集数据「是什么型号、什么时候采的、什么工况」。
 *
 * <p>存在的理由有两条。其一是 tasks.md §1.7 的要求：一份光有报文没有工况说明的 fixture，
 * 半年后没人说得清它覆盖了什么、没覆盖什么。其二是技术上的：Java 没有可靠的方式枚举
 * classpath 上某个目录下的资源文件，所以需要一份显式清单告诉测试有哪些 fixture 可加载。
 *
 * <p>清单文件为 {@code app/src/test/resources/goldenmaster/manifest.tsv}，
 * 由 {@code tools/prepare-probe-fixture.sh} 在导入采集数据时追加。
 * 清单不存在或没有任何条目时，重放测试整体跳过 —— 那是现场数据尚未采集回来的正常状态。
 */
public final class FixtureManifest {

    public static final String RESOURCE = ProbeTrafficFixture.ROOT + "manifest.tsv";

    private FixtureManifest() {
    }

    /** @return 清单中的全部条目；清单不存在时返回空列表 */
    public static List<Entry> load() throws IOException {
        InputStream in = FixtureManifest.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return Collections.emptyList();
        }
        List<Entry> entries = new ArrayList<Entry>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] parts = trimmed.split("\t", -1);
                if (parts.length < 4) {
                    continue;
                }
                List<Integer> probeTypes = new ArrayList<Integer>();
                for (String t : parts[1].split(",")) {
                    try {
                        probeTypes.add(Integer.valueOf(t.trim()));
                    } catch (NumberFormatException e) {
                        // 型号列写坏了不该让整份清单作废，跳过该项
                    }
                }
                entries.add(new Entry(parts[0], probeTypes, parts[2], parts[3]));
            }
        } finally {
            reader.close();
        }
        return entries;
    }

    /** 清单中的一条：一份 fixture 文件及其采集背景。 */
    public static final class Entry {
        /** fixture 文件名，位于 {@link ProbeTrafficFixture#ROOT} 下。 */
        public final String fileName;
        /** 该份数据覆盖的探头型号（0 中诚 / 1 LD / 2 LD2）。 */
        public final List<Integer> probeTypes;
        /** 采集日期，形如 20260901。 */
        public final String capturedOn;
        /** 采集工况描述，例如「正常工况连续 24h」「人为触发高位报警」。 */
        public final String conditions;

        Entry(String fileName, List<Integer> probeTypes, String capturedOn, String conditions) {
            this.fileName = fileName;
            this.probeTypes = Collections.unmodifiableList(probeTypes);
            this.capturedOn = capturedOn;
            this.conditions = conditions;
        }

        @Override
        public String toString() {
            return fileName + " (探头型号=" + probeTypes + " 采集于=" + capturedOn + " 工况=" + conditions + ")";
        }
    }
}
