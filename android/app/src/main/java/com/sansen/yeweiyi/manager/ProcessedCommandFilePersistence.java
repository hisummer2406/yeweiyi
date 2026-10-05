package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.rpc.ProcessedCommandStore;

import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ProcessedCommandStore.Persistence} 的文件实现：JSON 写在应用私有目录下，
 * 跨进程重启存活，用来支撑"重启后已处理/执行中命令的安全恢复"（issue #35 acceptance）。
 * <p>
 * 掉电安全的写法（写临时文件 → fsync → rename）和损坏文件的处理见 {@link AtomicJsonFile}。
 * 文件损坏/格式不对时当成"从没记录过任何命令"，不阻塞启动——代价是丢失去重历史，
 * 但比启动崩溃或死循环安全。
 */
public final class ProcessedCommandFilePersistence implements ProcessedCommandStore.Persistence {

    private static final Type MAP_TYPE =
            new TypeToken<HashMap<String, ProcessedCommandStore.Record>>() {
            }.getType();

    private final AtomicJsonFile file;

    public ProcessedCommandFilePersistence(Context context) {
        this.file = new AtomicJsonFile(new File(context.getFilesDir(), "thingsboard_processed_commands.json"),
                MAP_TYPE, "TBCommandStore");
    }

    @Override
    public Map<String, ProcessedCommandStore.Record> loadAll() {
        Map<String, ProcessedCommandStore.Record> loaded = file.read();
        return loaded == null ? new HashMap<>() : loaded;
    }

    @Override
    public boolean saveAll(Map<String, ProcessedCommandStore.Record> records) {
        return file.write(records);
    }
}
