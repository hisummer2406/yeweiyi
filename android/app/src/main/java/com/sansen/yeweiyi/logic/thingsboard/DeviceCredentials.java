package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 单台设备的 ThingsBoard 接入凭据：每台设备独立的 access token，不是全部设备共享一份
 * 构建期账号（旧 {@code BackService.userName}/{@code passWord} 就是共享账号，本次替换掉）。
 * <p>
 * 凭据在设备现场配网/换绑时写入本地安全存储（见
 * {@code manager.ThingsBoardCredentialStore}），不经过 {@code local.properties}/BuildConfig
 * 那条构建期注入通道 —— 那条通道天然是"所有设备共享同一份"，做不到独立凭据。
 */
public final class DeviceCredentials {

    private final String deviceId;
    private final String accessToken;

    public DeviceCredentials(String deviceId, String accessToken) {
        this.deviceId = deviceId;
        this.accessToken = accessToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    /** 两者均非空才算"已配网"；任一缺失都不应该尝试连接。 */
    public boolean isValid() {
        return notEmpty(deviceId) && notEmpty(accessToken);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public String toString() {
        // 不打印 accessToken 明文，避免凭据进日志。
        return "DeviceCredentials{deviceId='" + deviceId + "', accessToken=" +
                (notEmpty(accessToken) ? "[REDACTED]" : "null") + '}';
    }
}
