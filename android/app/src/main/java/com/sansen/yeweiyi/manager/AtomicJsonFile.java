package com.sansen.yeweiyi.manager;

import android.util.Log;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * ThingsBoard 几个落盘状态（补发队列、当前报警集合、命令去重记录）共用的 JSON 文件读写，保证掉电安全：
 * <ul>
 *   <li>写：先写 {@code .tmp}，{@code fsync} 刷到磁盘后再 rename 覆盖正式文件。之前只 {@code flush()}
 *       就 rename，数据可能还在页缓存里，掉电后留下空文件或半截文件（代码审核 M4）。</li>
 *   <li>读：解析失败时把坏文件改名为 {@code .corrupt-时间戳} 保留下来再按"没有数据"处理，
 *       不让下一次写入直接覆盖掉、事后无从排查。</li>
 * </ul>
 */
final class AtomicJsonFile {

    private static final Gson GSON = new Gson();

    private final File file;
    private final Type type;
    private final String tag;

    AtomicJsonFile(File file, Type type, String tag) {
        this.file = file;
        this.type = type;
        this.tag = tag;
    }

    /** @return 解析出的内容；文件不存在、内容为空或已损坏时返回 null（损坏的文件会被改名保留）。 */
    <T> T read() {
        if (!file.exists()) {
            return null;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (Exception e) {
            Log.e(tag, "读取失败，按空数据处理: " + file, e);
            quarantine();
            return null;
        }
    }

    /** @return 是否真正写到磁盘并替换了正式文件。 */
    boolean write(Object value) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp);
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // RuntimeException 也要接：Gson 序列化失败抛的是 JsonIOException
            GSON.toJson(value, type, writer);
            writer.flush();
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            Log.e(tag, "写入失败: " + file, e);
            deleteQuietly(tmp);
            return false;
        }
        if (!tmp.renameTo(file)) {
            Log.e(tag, "rename 失败: " + tmp + " -> " + file);
            deleteQuietly(tmp);
            return false;
        }
        return true;
    }

    private void quarantine() {
        File corrupt = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        if (!file.renameTo(corrupt)) {
            Log.w(tag, "损坏文件改名保留失败: " + file);
        }
    }

    private void deleteQuietly(File tmp) {
        // 临时文件留着不影响正确性（下次写入会覆盖），但失败时清掉更干净，也避免磁盘满时继续占空间
        if (tmp.exists() && !tmp.delete()) {
            Log.w(tag, "删除临时文件失败: " + tmp);
        }
    }
}
