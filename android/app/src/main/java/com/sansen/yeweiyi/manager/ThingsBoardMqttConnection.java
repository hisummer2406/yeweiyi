package com.sansen.yeweiyi.manager;

import android.util.Log;

import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.logic.thingsboard.ThingsBoardTopics;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLSocketFactory;

/**
 * ThingsBoard CE 设备端 MQTT 连接：ThingsBoard 官方固定 topic + 每台设备独立 access token
 * 作为 MQTT 用户名，不再是 {@code BackService} 旧代码里全部设备共享一份构建期账号连
 * 阿里云 Alink topic 那种连法。
 * <p>
 * 复用项目里已经在用的 {@code libs/mqtt-client-0.4.0.jar}（Eclipse Paho），走
 * {@code ssl://} scheme 时该客户端支持 {@link MqttConnectOptions#setSocketFactory}，
 * 用平台默认信任库校验 broker 证书——证书链有问题时 {@code connect()} 直接抛
 * {@link MqttException} 失败，不会静默退化成不校验证书或退回明文连接。
 * <p>
 * 私有化部署自签 CA 的信任库加载（issue #32 完全离线私有化那条线）目前还没做，
 * 那属于单独的部署形态，这里先只覆盖公网 CA 签发证书这条最常见路径。
 */
public final class ThingsBoardMqttConnection {

    private static final String TAG = "TBMqttConnection";
    private static final int QOS_AT_LEAST_ONCE = 1;
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int KEEP_ALIVE_SECONDS = 30;

    public interface RpcRequestListener {
        void onRpcRequest(String mqttRequestId, String payloadJson);
    }

    private final String brokerUri;
    private final String clientId;
    private final boolean useTls;
    private final RpcRequestListener rpcRequestListener;

    private volatile MqttClient client;

    public ThingsBoardMqttConnection(String brokerUri, String clientId, boolean useTls,
                                      RpcRequestListener rpcRequestListener) {
        this.brokerUri = brokerUri;
        this.clientId = clientId;
        this.useTls = useTls;
        this.rpcRequestListener = rpcRequestListener;
    }

    /** @return 连接并订阅 RPC 请求是否成功；失败原因已记日志，调用方按 {@link com.sansen.yeweiyi.logic.thingsboard.ReconnectBackoff} 排下一次重试。 */
    public synchronized boolean connect(DeviceCredentials credentials) {
        if (!credentials.isValid()) {
            Log.e(TAG, "设备未配网（缺 deviceId/accessToken），本次不发起连接");
            return false;
        }
        try {
            if (client != null && client.isConnected()) {
                return true;
            }
            client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            client.setCallback(new InternalCallback());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            // ThingsBoard 设备 MQTT 认证约定：用户名=access token，不需要密码。
            options.setUserName(credentials.getAccessToken());
            options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
            options.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
            if (useTls) {
                options.setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
            }

            client.connect(options);
            client.subscribe(ThingsBoardTopics.RPC_REQUEST_SUBSCRIBE, QOS_AT_LEAST_ONCE);
            Log.i(TAG, "ThingsBoard MQTT 连接成功: " + brokerUri);
            return true;
        } catch (Exception e) {
            // 覆盖证书校验失败、凭据被吊销（broker 拒绝连接）、DNS/网络不通等——
            // 统一记日志、返回 false，交给调用方走退避重连，不在这里吞掉细节。
            Log.e(TAG, "ThingsBoard MQTT 连接失败: broker=" + brokerUri + " clientId=" + clientId, e);
            client = null;
            return false;
        }
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
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

    private final class InternalCallback implements MqttCallback {

        @Override
        public void connectionLost(Throwable cause) {
            Log.w(TAG, "ThingsBoard MQTT 连接断开", cause);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 不需要额外处理：telemetry/ACK 的成功语义由业务层自己的重试/补投策略决定。
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String requestId = ThingsBoardTopics.extractRpcRequestId(topic);
            if (requestId == null) {
                Log.w(TAG, "收到无法识别的 topic，丢弃: " + topic);
                return;
            }
            String payload;
            try {
                payload = new String(message.getPayload(), "UTF-8");
            } catch (Exception e) {
                Log.e(TAG, "RPC 请求 payload 解码失败: " + topic, e);
                return;
            }
            if (rpcRequestListener != null) {
                rpcRequestListener.onRpcRequest(requestId, payload);
            }
        }
    }
}
