package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.sansen.yeweiyi.logic.thingsboard.RecordOutbox;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** {@link RecordOutbox} 落盘（交班/库存上报通道），按设备身份分文件，写法同 {@link PendingAlarmFilePersistence}。 */
public final class RecordOutboxFilePersistence implements RecordOutbox.Persistence {

    private static final Type LIST_TYPE = new TypeToken<ArrayList<RecordOutbox.Entry>>() {
    }.getType();

    private final AtomicJsonFile file;

    public RecordOutboxFilePersistence(Context context, String deviceId) {
        this.file = new AtomicJsonFile(IdentityScopedFile.resolve(context, "thingsboard_record_outbox", deviceId),
                LIST_TYPE, "TBRecordOutbox");
    }

    @Override
    public List<RecordOutbox.Entry> loadAll() {
        List<RecordOutbox.Entry> loaded = file.read();
        return loaded == null ? new ArrayList<>() : loaded;
    }

    @Override
    public void saveAll(List<RecordOutbox.Entry> entries) {
        file.write(entries);
    }
}
