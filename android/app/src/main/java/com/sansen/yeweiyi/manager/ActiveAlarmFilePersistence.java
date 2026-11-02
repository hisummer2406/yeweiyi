package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.ActiveAlarmTracker;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ActiveAlarmTracker} 的文件持久化。掉电安全的写法和损坏文件的处理见 {@link AtomicJsonFile}。
 * 读失败按空集合处理——最坏结果是重启前 ACTIVE 的报警收不到 CLEARED，不影响本地报警。
 */
public final class ActiveAlarmFilePersistence implements ActiveAlarmTracker.Persistence {

    private static final Type LIST_TYPE = new TypeToken<ArrayList<ActiveAlarmTracker.Entry>>() {
    }.getType();

    private final AtomicJsonFile file;

    /** @param deviceId 当前设备身份（出厂编码）；按身份分文件，见 {@link IdentityScopedFile}。 */
    public ActiveAlarmFilePersistence(Context context, String deviceId) {
        this.file = new AtomicJsonFile(IdentityScopedFile.resolve(context, "thingsboard_active_alarms", deviceId),
                LIST_TYPE, "TBActiveAlarms");
    }

    @Override
    public List<ActiveAlarmTracker.Entry> loadAll() {
        List<ActiveAlarmTracker.Entry> loaded = file.read();
        return loaded == null ? new ArrayList<>() : loaded;
    }

    @Override
    public void saveAll(List<ActiveAlarmTracker.Entry> entries) {
        file.write(entries);
    }
}
