package com.sansen.yeweiyi.logic.thingsboard;

import java.nio.charset.StandardCharsets;

/**
 * 设备身份（出厂编码 = ThingsBoard 设备名称）到本地持久化文件名后缀的映射。
 * <p>
 * 待补投队列、ACTIVE 报警、命令去重记录都是"某个设备身份名下"的状态：换大屏（新编码）后，
 * 旧身份的这些文件必须原样留在盘上、但不能被新身份读到或发送；同一台设备重装/断网重连，编码不变，
 * 落到同一个文件，补投照常。编码按字符串处理，保留前导零；文件名用 UTF-8 字节的十六进制，
 * 不受编码里的特殊字符影响，也不会出现两个不同编码映射到同一个文件。
 */
public final class IdentityScope {

    private IdentityScope() {
    }

    /** 去掉首尾空白；null 视为未配置。 */
    public static String normalize(String deviceId) {
        return deviceId == null ? "" : deviceId.trim();
    }

    /**
     * @return 未配置编码时返回空串（沿用不带后缀的原文件名，即"尚未绑定身份"的存储）；
     * 否则返回 {@code "_" + hex}。
     */
    public static String fileSuffix(String deviceId) {
        String id = normalize(deviceId);
        if (id.isEmpty()) {
            return "";
        }
        byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(1 + bytes.length * 2).append('_');
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
