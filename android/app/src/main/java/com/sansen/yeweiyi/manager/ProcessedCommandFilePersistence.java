package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.rpc.ProcessedCommandStore;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ProcessedCommandStore.Persistence} 的文件实现：JSON 写在应用私有目录下，
 * 跨进程重启存活，用来支撑"重启后已处理/执行中命令的安全恢复"（issue #35 acceptance）。
 * <p>
 * 先写临时文件再 rename，而不是直接覆盖原文件——设备是现场固定终端，随时可能断电，
 * 直接覆盖在写到一半时断电会把文件写坏，rename 是原子操作，坏的只会是临时文件。
 */
public final class ProcessedCommandFilePersistence implements ProcessedCommandStore.Persistence {

    private static final String TAG = "TBCommandStore";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE =
            new TypeToken<HashMap<String, ProcessedCommandStore.Record>>() {
            }.getType();

    private final File file;

    public ProcessedCommandFilePersistence(Context context) {
        this.file = new File(context.getFilesDir(), "thingsboard_processed_commands.json");
    }

    @Override
    public Map<String, ProcessedCommandStore.Record> loadAll() {
        if (!file.exists()) {
            return new HashMap<>();
        }
        try (FileReader reader = new FileReader(file)) {
            Map<String, ProcessedCommandStore.Record> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded == null ? new HashMap<>() : loaded;
        } catch (Exception e) {
            // 文件损坏/格式不对：当成"从没记录过任何命令"，不阻塞启动。
            // 代价是丢失去重历史，但比启动崩溃或死循环安全。
            Log.e(TAG, "读取命令去重记录失败，按空表处理: " + file, e);
            return new HashMap<>();
        }
    }

    /**
     * @return 是否真的落到盘上。磁盘满、目录不可写、rename 失败都返回 false——
     * 调用方（{@link ProcessedCommandStore#record}）靠这个值决定要不要继续执行命令，
     * 所以这里绝不能"记了日志就当成功"。
     */
    @Override
    public boolean saveAll(Map<String, ProcessedCommandStore.Record> records) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileWriter writer = new FileWriter(tmp)) {
            GSON.toJson(records, MAP_TYPE, writer);
            writer.flush();
        } catch (IOException | RuntimeException e) {
            // RuntimeException 也要接：Gson 序列化失败抛的是 JsonIOException，
            // 漏掉的话会穿到 MQTT 回调线程上把整条 RPC 处理流程打断。
            Log.e(TAG, "写入命令去重记录失败: " + file, e);
            deleteQuietly(tmp);
            return false;
        }
        if (!tmp.renameTo(file)) {
            Log.e(TAG, "rename 命令去重记录文件失败: " + tmp + " -> " + file);
            deleteQuietly(tmp);
            return false;
        }
        return true;
    }

    private static void deleteQuietly(File tmp) {
        // 临时文件留着不影响正确性（下次 saveAll 会覆盖），但失败时清掉更干净，
        // 也避免磁盘满的场景下这个半截文件继续占着空间。
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "删除临时文件失败: " + tmp);
        }
    }
}
