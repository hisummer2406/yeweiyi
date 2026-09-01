package com.sansen.yeweiyi.manager;

import android.content.Context;

import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentialStore;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.utils.SharePrefUtil;

/**
 * {@link DeviceCredentialStore} 的 Android 实现：存在现有 {@code SharePrefUtil} 的
 * SharedPreferences 文件里，跟设备名等其它现场配置一起，不新起一个存储文件。
 * <p>
 * 凭据写入时机是设备现场配网/换绑（具体交互 UI 不在本次改动范围——本类只负责存取），
 * 吊销/换绑时调用 {@link #clear()}。
 */
public final class ThingsBoardCredentialStore implements DeviceCredentialStore {

    private final Context context;

    public ThingsBoardCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public DeviceCredentials load() {
        String deviceId = SharePrefUtil.getString(context, SharePrefUtil.TB_DEVICE_ID, "");
        String accessToken = SharePrefUtil.getString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
        return new DeviceCredentials(deviceId, accessToken);
    }

    @Override
    public void save(DeviceCredentials credentials) {
        SharePrefUtil.saveString(context, SharePrefUtil.TB_DEVICE_ID, credentials.getDeviceId());
        SharePrefUtil.saveString(context, SharePrefUtil.TB_ACCESS_TOKEN, credentials.getAccessToken());
    }

    @Override
    public void clear() {
        SharePrefUtil.saveString(context, SharePrefUtil.TB_DEVICE_ID, "");
        SharePrefUtil.saveString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
    }
}
