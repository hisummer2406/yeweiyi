package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 设备凭据的读写入口。纯接口，方便 {@code ThingsBoardManager} 不直接依赖 Android
 * SharedPreferences 就能被单测覆盖；真机实现见 {@code manager.ThingsBoardCredentialStore}。
 */
public interface DeviceCredentialStore {

    DeviceCredentials load();

    void save(DeviceCredentials credentials);

    /** 凭据被吊销/设备换绑时调用：清空本地存储，之后 {@link #load()} 返回一个 {@code isValid()==false} 的实例。 */
    void clear();
}
