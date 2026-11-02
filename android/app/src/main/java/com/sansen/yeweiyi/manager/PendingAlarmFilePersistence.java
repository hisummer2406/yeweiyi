package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;

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

    /** @param deviceId 当前设备身份（出厂编码）；队列按身份分文件，换身份不会读到旧身份的待补投事件。 */
    public PendingAlarmFilePersistence(Context context, String deviceId) {
        this.file = new AtomicJsonFile(IdentityScopedFile.resolve(context, "thingsboard_pending_alarms", deviceId),
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
