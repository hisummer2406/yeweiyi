package com.sansen.yeweiyi.config;

import com.sansen.yeweiyi.BuildConfig;

/**
 * 接口与设备凭据配置。
 *
 * 这些值不再硬编码在源码里，改为构建期由 app/build.gradle 从 local.properties 注入
 * （local.properties 不进版本库）。新环境请照仓库根目录的 local.properties.template 填写；
 * 缺失时构建会打印 [配置缺失] 警告，且这些常量为空字符串。
 *
 * 注意：本文件在 2026-07-31 之前硬编码过真实凭据并已提交进 git 历史，
 * 那批旧凭据必须视为已泄露并完成轮换 —— 删除源码不能撤回历史。
 */
public class ApiConfig {
    /** 阿里云 IoT 产品 Key，用于 MQTT 上报（BackService）。 */
    public static final String productKey = BuildConfig.PRODUCT_KEY;

    /** 阿里云 IoT 产品密钥。目前无代码引用，保留以备设备注册接口使用。 */
    public static final String productSecret = BuildConfig.PRODUCT_SECRET;

    /** 接口请求体 3DES 加密密钥（Base64 编码形式，见 OkgoNet.simplenessPost）。 */
    public static final String key = BuildConfig.REQUEST_ENCRYPT_KEY;

    /** 服务端基址。目前无代码引用。 */
    public static final String BASE_URL = BuildConfig.BASE_URL;

    /**
     * ThingsBoard MQTT 是否走 TLS（{@code ssl://}）连接，见
     * {@code manager.ThingsBoardManager}/{@code manager.ThingsBoardMqttConnection}。
     * 非敏感开关，走 {@code flagField} 通道，缺失即为 false。
     */
    public static final boolean THINGSBOARD_USE_TLS = BuildConfig.THINGSBOARD_USE_TLS;

    /**
     * ThingsBoard Device Profile 里生成的设备自助注册 key/secret，同型号设备共用。
     * 为空时设备不能自动领 token，只能在云平台设置页手填。
     */
    public static final String PROVISION_DEVICE_KEY = BuildConfig.PROVISION_DEVICE_KEY;
    public static final String PROVISION_DEVICE_SECRET = BuildConfig.PROVISION_DEVICE_SECRET;
}
