package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PendingAlarmStore.Persistence} 的文件实现，同 {@link ProcessedCommandFilePersistence}
 * 一样用临时文件 + rename 防止断电写坏。
 */
public final class PendingAlarmFilePersistence implements PendingAlarmStore.Persistence {

    private static final String TAG = "TBPendingAlarms";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private final File file;

    public PendingAlarmFilePersistence(Context context) {
        this.file = new File(context.getFilesDir(), "thingsboard_pending_alarms.json");
    }

    @Override
    public List<String> loadAll() {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (FileReader reader = new FileReader(file)) {
            List<String> loaded = GSON.fromJson(reader, LIST_TYPE);
            return loaded == null ? new ArrayList<>() : loaded;
        } catch (Exception e) {
            Log.e(TAG, "读取报警补投队列失败，按空队列处理: " + file, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void saveAll(List<String> entries) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileWriter writer = new FileWriter(tmp)) {
            GSON.toJson(entries, LIST_TYPE, writer);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "写入报警补投队列失败: " + file, e);
            return;
        }
        if (!tmp.renameTo(file)) {
            Log.e(TAG, "rename 报警补投队列文件失败: " + tmp + " -> " + file);
        }
    }
}
