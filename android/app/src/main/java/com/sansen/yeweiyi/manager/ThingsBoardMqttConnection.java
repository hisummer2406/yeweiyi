package com.sansen.yeweiyi.manager;

import android.util.Log;

import com.sansen.yeweiyi.logic.thingsboard.BrokerTls;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionMessages;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionResult;
import com.sansen.yeweiyi.logic.thingsboard.ThingsBoardTopics;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocketFactory;

/**
 * ThingsBoard CE 设备端 MQTT 连接：ThingsBoard 官方固定 topic + 每台设备独立 access token
 * 作为 MQTT 用户名，不再是 {@code BackService} 旧代码里全部设备共享一份构建期账号连
 * 阿里云 Alink topic 那种连法。
 * <p>
 * 复用项目里已经在用的 {@code libs/mqtt-client-0.4.0.jar}（Eclipse Paho），走
 * {@code ssl://} scheme 时该客户端支持 {@link MqttConnectOptions#setSocketFactory}。
 * 信任链和主机名校验都由 {@link BrokerTls} 装配：预置了私有 CA 就只信那张（离线私有化部署，
 * issue #40 §4），没预置就用系统信任库（公网 CA 签发证书的常规部署）。两条路径都做主机名校验，
 * 都不提供"跳过证书校验"的开关；证书有问题时 {@code connect()} 抛异常失败，不会静默退化成
 * 不校验或退回明文连接。
 */
public final class ThingsBoardMqttConnection {

    private static final String TAG = "TBMqttConnection";
    private static final int QOS_AT_LEAST_ONCE = 1;
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int KEEP_ALIVE_SECONDS = 30;

    public interface RpcRequestListener {
        void onRpcRequest(String mqttRequestId, String payloadJson);
    }

    /**
     * 共享属性（服务端→设备下发）的两条消息路径——issue #39/#40 P1-4。{@link #onSharedAttributesPush}
     * 对应 {@link ThingsBoardTopics#ATTRIBUTES_PUBLISH} 上的主动推送（根对象直接是变化的属性），
     * {@link #onAttributesResponse} 对应 {@link ThingsBoardTopics#ATTRIBUTES_RESPONSE_SUBSCRIBE}
     * 上对 {@link #publishAttributesRequest} 的应答（包一层 {@code {"shared":...,"client":...}}）。
     * 两种载荷形状不同，交给 {@code SharedAttributesParser} 的两个不同入口解析，这一层只管路由。
     */
    public interface AttributesListener {
        void onSharedAttributesPush(String payloadJson);

        void onAttributesResponse(String requestId, String payloadJson);
    }

    /** 连接建立之后（非首次 {@link #connect} 失败）意外断线时回调，交给调用方走退避重连。 */
    public interface ConnectionLostListener {
        void onConnectionLost(Throwable cause);
    }

    /**
     * 私有化部署预置的 CA 证书从哪来。抽成接口是为了让这个类不依赖 {@code Context}，
     * 实现见 {@link ThingsBoardCaStore}。
     */
    public interface CaCertificateSource {
        /** @return CA 证书流，调用方负责关闭；没有预置私有 CA 时返回 null */
        InputStream openCaCertificate() throws IOException;
    }

    /**
     * 非 final：{@link #updateBrokerUri} 允许配网页面改了 broker 地址之后，下一次
     * {@link #connect} 用新地址重连，不需要重建整个 {@link ThingsBoardMqttConnection} 实例。
     */
    private volatile String brokerUri;
    private final boolean useTls;
    private final CaCertificateSource caCertificateSource;
    private final RpcRequestListener rpcRequestListener;
    private final AttributesListener attributesListener;
    private final ConnectionLostListener connectionLostListener;

    private volatile MqttClient client;

    /**
     * 不再接收单独的 {@code clientId} 构造参数——MQTT client ID 现在从每次 {@link #connect}
     * 传入的 {@link DeviceCredentials#getDeviceId()} 取（见该方法上的注释，PR #38 Codex
     * 第六轮审核 P2）。
     */
    public ThingsBoardMqttConnection(String brokerUri, boolean useTls,
                                      CaCertificateSource caCertificateSource,
                                      RpcRequestListener rpcRequestListener,
                                      AttributesListener attributesListener,
                                      ConnectionLostListener connectionLostListener) {
        this.brokerUri = brokerUri;
        this.useTls = useTls;
        this.caCertificateSource = caCertificateSource;
        this.rpcRequestListener = rpcRequestListener;
        this.attributesListener = attributesListener;
        this.connectionLostListener = connectionLostListener;
    }

    /**
     * @return 连接并订阅 RPC 请求、共享属性推送/应答是否成功；失败原因已记日志，调用方按
     * {@link com.sansen.yeweiyi.logic.thingsboard.ReconnectBackoff} 排下一次重试。
     * <p>
     * 只有 {@code connect()}+{@code subscribe()} 都成功之后才会把新建的客户端赋给
     * {@link #client} 字段——之前是先赋值再连接/订阅，{@code subscribe()} 在已经连上之后
     * 失败时会走进 catch 直接把字段置空，但没有关闭那个仍然连着 broker 的客户端实例，
     * 白白泄漏一个还在跑内部收发线程的 {@code MqttClient}（PR #38 Codex 第五轮审核 P2）。
     * 现在失败分支只操作本地变量，成功前 {@link #client} 字段保持不变，失败时把这个
     * 本地创建的实例关掉再返回。
     * <p>
     * MQTT client ID 用 {@code credentials.getDeviceId()}，不是之前构造时固定传入的一份
     * {@code NetUtils.getMacAddr()}——那个值在 Android 6.0+（本项目 minSdk 21，绝大多数
     * 真机都受影响）拿不到应用层可见的真实 MAC，要么返回空串要么返回固定的
     * {@code "02:00:00:00:00:00"}：空串会让每次连接都失败，固定值会让多台设备用同一个
     * client ID 抢着连 broker——MQTT 协议要求 client ID 唯一，后连的会把先连的挤下线，
     * 两台设备来回互相顶替，谁都连不稳（PR #38 Codex 第六轮审核 P2）。{@code deviceId}
     * 是配网时写入的、真正每台设备唯一的标识，{@link DeviceCredentials#isValid()} 已经保证
     * 走到这里时非空。
     */
    public synchronized boolean connect(DeviceCredentials credentials) {
        if (!credentials.isValid()) {
            Log.e(TAG, "设备未配网（缺 deviceId/accessToken），本次不发起连接");
            return false;
        }
        if (client != null && client.isConnected()) {
            return true;
        }
        String clientId = credentials.getDeviceId();
        MqttClient created = null;
        try {
            created = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            created.setCallback(new InternalCallback(created));

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            // ThingsBoard 设备 MQTT 认证约定：用户名=access token，不需要密码。
            options.setUserName(credentials.getAccessToken());
            options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
            options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
            if (useTls) {
                options.setSocketFactory(createTlsSocketFactory());
            }

            created.connect(options);
            created.subscribe(ThingsBoardTopics.RPC_REQUEST_SUBSCRIBE, QOS_AT_LEAST_ONCE);
            // 共享属性两条通道（issue #39/#40 P1-4）：ATTRIBUTES_PUBLISH 同一个 topic 字符串
            // 既用于本类 publishAttributes() 上报 client attribute，也用于订阅服务端下发的共享
            // 属性推送——这是 ThingsBoard 设备 MQTT API 本身的约定，不是本类的巧合复用。
            created.subscribe(ThingsBoardTopics.ATTRIBUTES_PUBLISH, QOS_AT_LEAST_ONCE);
            created.subscribe(ThingsBoardTopics.ATTRIBUTES_RESPONSE_SUBSCRIBE, QOS_AT_LEAST_ONCE);
            client = created;
            Log.i(TAG, "ThingsBoard MQTT 连接成功: " + brokerUri);
            return true;
        } catch (Exception e) {
            // 覆盖证书校验失败、凭据被吊销（broker 拒绝连接）、连上了但订阅失败、
            // DNS/网络不通等——统一记日志、返回 false，交给调用方走退避重连，
            // 不在这里吞掉细节。created 从未赋给 client 字段，这里直接关掉它，不留泄漏。
            Log.e(TAG, "ThingsBoard MQTT 连接失败: broker=" + brokerUri + " clientId=" + clientId, e);
            closeQuietly(created);
            return false;
        }
    }

    /**
     * 用 {@link ThingsBoardTopics#PROVISION_USERNAME} 临时连同一个 broker（同样的 TLS 校验），
     * 发一次注册请求、等应答后断开。阻塞最长约连接超时 + {@code timeoutMs}，只能在后台线程调用；
     * 不影响 {@link #client} 字段。
     */
    public synchronized ProvisionResult provision(String deviceName, String requestJson, long timeoutMs) {
        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<>();
        MqttClient temp = null;
        try {
            temp = new MqttClient(brokerUri, deviceName, new MemoryPersistence());
            temp.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    answered.countDown();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    if (ThingsBoardTopics.PROVISION_RESPONSE.equals(topic)) {
                        response.set(new String(message.getPayload(), "UTF-8"));
                        answered.countDown();
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setUserName(ThingsBoardTopics.PROVISION_USERNAME);
            options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
            options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
            if (useTls) {
                options.setSocketFactory(createTlsSocketFactory());
            }
            temp.connect(options);
            temp.subscribe(ThingsBoardTopics.PROVISION_RESPONSE, QOS_AT_LEAST_ONCE);
            MqttMessage request = new MqttMessage(requestJson.getBytes("UTF-8"));
            request.setQos(QOS_AT_LEAST_ONCE);
            temp.publish(ThingsBoardTopics.PROVISION_REQUEST, request);

            if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return ProvisionResult.failure(ProvisionResult.Outcome.TIMEOUT, "等待注册应答超时");
            }
            String payload = response.get();
            if (payload == null) {
                return ProvisionResult.failure(ProvisionResult.Outcome.NETWORK_ERROR, "等待应答时连接断开");
            }
            return ProvisionMessages.parseResponse(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultIfAnswered(response.get(), "注册被中断");
        } catch (Exception e) {
            Log.e(TAG, "ThingsBoard 设备注册连接失败: broker=" + brokerUri + " deviceName=" + deviceName, e);
            return resultIfAnswered(response.get(), e.toString());
        } finally {
            closeQuietly(temp);
        }
    }

    /**
     * 出异常前应答可能已经到了（例如等 PUBACK 时断线）。云端那时已把编号标记为已领取，
     * 当成网络失败去重试只会被拒，所以有应答就按应答处理。
     */
    private static ProvisionResult resultIfAnswered(String payload, String errorMessage) {
        if (payload != null) {
            return ProvisionMessages.parseResponse(payload);
        }
        return ProvisionResult.failure(ProvisionResult.Outcome.NETWORK_ERROR, errorMessage);
    }

    private static void closeQuietly(MqttClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.disconnect(3000);
            }
            c.close();
        } catch (MqttException e) {
            Log.w(TAG, "释放 MQTT 客户端失败", e);
        }
    }

    /**
     * 装配 TLS：私有 CA（有就只信它）或系统信任库，两者都带主机名校验，外面再包一层 SNI/协议收敛。
     * <p>
     * 任何一步失败都往外抛，由 {@link #connect} 统一记日志并返回 false 走退避重连——
     * 绝不能在这里"退回一个不校验的工厂"把连接凑合建起来。
     */
    private SSLSocketFactory createTlsSocketFactory() throws Exception {
        String host = BrokerTls.hostFromBrokerUri(brokerUri);
        if (host == null || host.isEmpty()) {
            throw new IllegalStateException("broker 地址解析不出主机名，无法校验服务端证书: " + brokerUri);
        }
        SSLSocketFactory base;
        InputStream caPem = caCertificateSource == null ? null : caCertificateSource.openCaCertificate();
        if (caPem != null) {
            try {
                base = BrokerTls.privateCaSocketFactory(caPem, host);
            } finally {
                caPem.close();
            }
        } else {
            base = BrokerTls.systemTrustSocketFactory(host);
        }
        return new SniSocketFactory(base, host);
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /**
     * 配网页面保存了新的 broker 地址后调用。只更新地址，不在这里主动断线——调用方
     * （{@code ThingsBoardManager}）自己决定要不要先 {@link #disconnect()} 再重连；
     * 如果当前客户端仍然连着旧地址，{@link #connect} 的"已连接直接返回 true"分支会让
     * 新地址在下一次真正需要重连时才生效，不强制打断一个还工作正常的连接。
     */
    public void updateBrokerUri(String brokerUri) {
        this.brokerUri = brokerUri;
    }

    public synchronized void disconnect() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(3000);
            }
            client.close();
        } catch (MqttException e) {
            Log.w(TAG, "断开 ThingsBoard MQTT 连接时出错", e);
        } finally {
            client = null;
        }
    }

    /** @return 是否发布成功；未连接或发布异常都返回 false，不抛出，调用方决定是否入队补投。 */
    public boolean publish(String topic, String payload, int qos) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            Log.w(TAG, "未连接，跳过发布: " + topic);
            return false;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes("UTF-8"));
            message.setQos(qos);
            message.setRetained(false);
            c.publish(topic, message);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "发布失败: " + topic, e);
            return false;
        }
    }

    public boolean publishTelemetry(String payloadJson) {
        return publish(ThingsBoardTopics.TELEMETRY, payloadJson, QOS_AT_LEAST_ONCE);
    }

    public void publishRpcAck(String mqttRequestId, String payloadJson) {
        publish(ThingsBoardTopics.rpcResponse(mqttRequestId), payloadJson, QOS_AT_LEAST_ONCE);
    }

    public boolean publishAttributes(String payloadJson) {
        return publish(ThingsBoardTopics.ATTRIBUTES_PUBLISH, payloadJson, QOS_AT_LEAST_ONCE);
    }

    /**
     * 主动请求当前共享属性值——订阅 {@link ThingsBoardTopics#ATTRIBUTES_PUBLISH} 只能收到
     * *之后* 发生的变化推送，不会重放已经存在的值（ThingsBoard 设备 MQTT API 的既有行为，
     * 见 {@link AttributesListener} 类注释）。每次连接成功后调一次，应答从
     * {@link ThingsBoardTopics#ATTRIBUTES_RESPONSE_SUBSCRIBE} 回来，交给
     * {@link AttributesListener#onAttributesResponse}。
     *
     * @param requestId    调用方生成，用于跟应答 topic 里的 requestId 对上；这个 id 只是
     *                     "这次请求/应答配对"的本地标识，不需要全局唯一或持久化。
     * @param sharedKeysCsv 逗号分隔的共享属性 key 列表（ThingsBoard 官方 {@code sharedKeys}
     *                     参数格式），比如 {@code "reportIntervalSec"}。
     */
    public boolean publishAttributesRequest(int requestId, String sharedKeysCsv) {
        String payload = "{\"sharedKeys\":\"" + sharedKeysCsv + "\"}";
        return publish(ThingsBoardTopics.attributesRequest(requestId), payload, QOS_AT_LEAST_ONCE);
    }

    /**
     * 每次 {@link #connect} 成功新建一个客户端实例时，都会配一个新的 {@link InternalCallback}
     * 并把该实例本身绑进来（{@link #owner}）——{@link #connectionLost} 只能清空/关闭自己的
     * {@code owner}，不能盲目操作共享的 {@link ThingsBoardMqttConnection#client} 字段。
     * 之前是共享字段直接读写：一次连接失败（比如 connect 成功但 subscribe 失败）会在没有
     * 关闭旧客户端的情况下把它遗弃在后台继续跑，如果这个被遗弃的实例后来自己触发
     * connectionLost，会读到这时候已经指向"重试后新建的替换实例"的 client 字段，
     * 把这个仍然工作正常的替换连接关掉、并误触发一次不必要的重连（PR #38 Codex 第五轮
     * 审核 P2）。
     */
    private final class InternalCallback implements MqttCallback {

        private final MqttClient owner;

        InternalCallback(MqttClient owner) {
            this.owner = owner;
        }

        @Override
        public void connectionLost(Throwable cause) {
            Log.w(TAG, "ThingsBoard MQTT 连接断开", cause);
            boolean wasCurrent;
            synchronized (ThingsBoardMqttConnection.this) {
                wasCurrent = client == owner;
                if (wasCurrent) {
                    client = null;
                }
            }
            closeQuietly(owner);
            if (!wasCurrent) {
                // 这个回调属于一个已经被替换/废弃的旧客户端实例——它的断线跟当前正在用的
                // 连接无关，不通知重连监听器，避免对着一条本来好好的连接瞎重连一次。
                Log.w(TAG, "忽略已废弃客户端实例的 connectionLost 回调");
                return;
            }
            if (connectionLostListener != null) {
                connectionLostListener.onConnectionLost(cause);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 不需要额外处理：telemetry/ACK 的成功语义由业务层自己的重试/补投策略决定。
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String payload;
            try {
                payload = new String(message.getPayload(), "UTF-8");
            } catch (Exception e) {
                Log.e(TAG, "消息 payload 解码失败: " + topic, e);
                return;
            }

            String rpcRequestId = ThingsBoardTopics.extractRpcRequestId(topic);
            if (rpcRequestId != null) {
                if (rpcRequestListener != null) {
                    rpcRequestListener.onRpcRequest(rpcRequestId, payload);
                }
                return;
            }

            // ATTRIBUTES_RESPONSE 前缀判断必须先于 ATTRIBUTES_PUBLISH 的精确匹配——两者是
            // 不同的 topic 字符串（.../attributes 对 .../attributes/response/{id}），顺序其实
            // 不影响正确性，这里先判断更具体的形状纯粹是习惯。
            String attributesRequestId = ThingsBoardTopics.extractAttributesResponseRequestId(topic);
            if (attributesRequestId != null) {
                if (attributesListener != null) {
                    attributesListener.onAttributesResponse(attributesRequestId, payload);
                }
                return;
            }

            if (ThingsBoardTopics.ATTRIBUTES_PUBLISH.equals(topic)) {
                if (attributesListener != null) {
                    attributesListener.onSharedAttributesPush(payload);
                }
                return;
            }

            Log.w(TAG, "收到无法识别的 topic，丢弃: " + topic);
        }
    }
}
