package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PendingAlarmStore.Persistence} 的文件实现。掉电安全的写法和损坏文件的处理见 {@link AtomicJsonFile}。
 */
public final class PendingAlarmFilePersistence implements PendingAlarmStore.Persistence {

    private static final Type LIST_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private final AtomicJsonFile file;

    public PendingAlarmFilePersistence(Context context) {
        this.file = new AtomicJsonFile(new File(context.getFilesDir(), "thingsboard_pending_alarms.json"),
                LIST_TYPE, "TBPendingAlarms");
    }

    @Override
    public List<String> loadAll() {
        List<String> loaded = file.read();
        return loaded == null ? new ArrayList<>() : loaded;
    }

    @Override
    public void saveAll(List<String> entries) {
        file.write(entries);
    }
}
