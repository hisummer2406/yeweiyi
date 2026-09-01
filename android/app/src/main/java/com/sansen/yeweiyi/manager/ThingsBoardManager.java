package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.logic.thingsboard.AlarmEventBuilder;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;
import com.sansen.yeweiyi.logic.thingsboard.ReconnectBackoff;
import com.sansen.yeweiyi.logic.thingsboard.TelemetryScheduler;
import com.sansen.yeweiyi.logic.thingsboard.TelemetrySnapshotBuilder;
import com.sansen.yeweiyi.logic.thingsboard.rpc.ProcessedCommandStore;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcDispatcher;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodRegistry;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.HighRiskActionHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetAlarmThresholdHandler;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.NetUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ThingsBoard CE 接入的 Android 侧总装：把 {@code logic/thingsboard} 下的纯逻辑
 * （拓扑/幂等/调度/RPC 状态机）跟真实 MQTT 连接、文件持久化、Android 定时器接起来。
 * <p>
 * 角色类似 {@code InOilManager} 之于 {@code logic/inout}——业务/UI 代码（这里是
 * {@code BackService}）只调这个类，不直接碰 {@link ThingsBoardMqttConnection} 或
 * {@code logic/thingsboard} 包内部类型。
 * <p>
 * 不保留 Alink {@code sys/{productKey}/{deviceName}/...} topic、不保留全部设备共享一份
 * 的构建期 MQTT 账号、不做双协议开关——见 issue #37。"拉取历史记录"这组旧 Alink service
 * （卸油/报警/交班/侧漏/罐表/罐设置/探头设置的 request 类 topic）在这次迁移中**没有**
 * 重新实现为 RPC，功能上是随本次改动一起下线的，不是遗漏——RPC 框架已经证明可行
 * （{@link SetAlarmThresholdHandler} 是参考实现），照同样模式补回这些查询是后续独立工作。
 */
public final class ThingsBoardManager {

    private static final String TAG = "ThingsBoardManager";
    private static final long TELEMETRY_PERIOD_MILLIS = 30_000L;
    private static final long COMMAND_RETENTION_MILLIS = 24L * 60 * 60 * 1000; // 24 小时
    private static final int MAX_PENDING_ALARMS = 200;
    private static final long RECONNECT_INITIAL_DELAY_MILLIS = 2_000L;
    private static final long RECONNECT_MAX_DELAY_MILLIS = 60_000L;

    private static ThingsBoardManager instance;

    public static synchronized ThingsBoardManager getInstance(Context context) {
        if (instance == null) {
            instance = new ThingsBoardManager(context.getApplicationContext());
        }
        return instance;
    }

    private final ThingsBoardCredentialStore credentialStore;
    private final ThingsBoardMqttConnection connection;
    private final TelemetryScheduler telemetryScheduler;
    private final PendingAlarmStore pendingAlarmStore;
    private final ProcessedCommandStore commandStore;
    private final RpcDispatcher rpcDispatcher;
    private final ReconnectBackoff reconnectBackoff;
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tb-reconnect");
                t.setDaemon(true);
                return t;
            });
    private final Gson gson = new Gson();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private ThingsBoardManager(Context context) {
        this.credentialStore = new ThingsBoardCredentialStore(context);

        String scheme = ApiConfig.THINGSBOARD_USE_TLS ? "ssl://" : "tcp://";
        String brokerUri = scheme + SharePreManager.getIpBean().getIp() + ":" + SharePreManager.getIpBean().getPort();
        this.connection = new ThingsBoardMqttConnection(brokerUri, NetUtils.getMacAddr(),
                ApiConfig.THINGSBOARD_USE_TLS, this::onRpcRequest);

        this.pendingAlarmStore = new PendingAlarmStore(new PendingAlarmFilePersistence(context), MAX_PENDING_ALARMS);
        this.commandStore = new ProcessedCommandStore(new ProcessedCommandFilePersistence(context),
                COMMAND_RETENTION_MILLIS);
        this.reconnectBackoff = new ReconnectBackoff(RECONNECT_INITIAL_DELAY_MILLIS, RECONNECT_MAX_DELAY_MILLIS, 2.0);

        RpcMethodRegistry registry = new RpcMethodRegistry();
        registry.register(SetAlarmThresholdHandler.METHOD,
                new SetAlarmThresholdHandler(OilTankRepositoryImpl.getInstance()));
        HighRiskActionHandler highRiskActionHandler = new HighRiskActionHandler();
        registry.register(HighRiskActionHandler.METHOD_START_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_STOP_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_OPEN_VALVE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_CLOSE_VALVE, highRiskActionHandler);

        this.rpcDispatcher = new RpcDispatcher(registry, commandStore, this::publishRpcAck,
                System::currentTimeMillis);

        this.telemetryScheduler = new TelemetryScheduler(this::buildAndPublishSnapshot, TELEMETRY_PERIOD_MILLIS,
                error -> Log.e(TAG, "30 秒快照发布失败", error));
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        int recovered = commandStore.recoverAfterRestart(System.currentTimeMillis());
        if (recovered > 0) {
            Log.w(TAG, "重启恢复：" + recovered + " 条未完成命令按 FAILED 处理");
        }
        telemetryScheduler.start();
        scheduleConnectAttempt(0);
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        telemetryScheduler.stop();
        connection.disconnect();
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    /** 供 {@code BackService.onReloadMqtt}/网络变化回调触发一次立即重连，不等退避到期。 */
    public void reconnectNow() {
        reconnectExecutor.execute(() -> attemptConnect());
    }

    // ---- 报警立即上报 ----

    /**
     * @param recovered false=报警产生，true=报警恢复。
     *                  <p>目前唯一的调用方（{@code BackService.sendAlarms}）只在
     *                  报警产生时触发，还没有一条真正的"恢复"事件源——见本类顶部说明和
     *                  issue #36 的已知缺口：恢复检测需要改 {@code AlarmDetection} 现有的
     *                  报警状态机，这次没有动它。
     */
    public void reportAlarmEvent(OilMessage message, boolean recovered) {
        long now = System.currentTimeMillis();
        Map<String, Object> event = AlarmEventBuilder.build(message, recovered, now);
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, now);
        String json = gson.toJson(payload);

        if (!connection.publishTelemetry(json)) {
            pendingAlarmStore.enqueue(json);
        }
    }

    /**
     * 出入库等其它"立即上报"事件的通用出口（原 {@code BackService.sendUnloadOils}）。
     * 复用报警的补投队列——两者都是"必须送达，不能被下一次快照覆盖"的即时事件，
     * 目前量级都很小，没必要为此单独再起一个队列。
     */
    public void reportRawEvent(String telemetryKey, String rawValue) {
        long now = System.currentTimeMillis();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put(telemetryKey, rawValue);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, now);
        String json = gson.toJson(payload);
        if (!connection.publishTelemetry(json)) {
            pendingAlarmStore.enqueue(json);
        }
    }

    private void drainPendingAlarms() {
        List<String> pending = pendingAlarmStore.drainAll();
        if (pending.isEmpty()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            if (!connection.publishTelemetry(pending.get(i))) {
                pendingAlarmStore.requeue(pending.subList(i, pending.size()));
                return;
            }
        }
    }

    // ---- 30 秒快照 ----

    private void buildAndPublishSnapshot() {
        if (!connection.isConnected()) {
            // 明确策略：断线期间的普通快照直接丢弃，不补投——见 PendingAlarmStore 类注释,
            // 网络恢复后下一个 30 秒周期会带着最新状态重新上报。
            return;
        }
        List<NetOilCan> tanks = OilcanDataManager.getInstance(MyApplication.getInstance()).getNetOilcans();
        long now = System.currentTimeMillis();
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, now,
                connection.isConnected() ? "online" : "offline");
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, now);
        connection.publishTelemetry(gson.toJson(payload));
    }

    // ---- RPC ----

    private void onRpcRequest(String mqttRequestId, String payloadJson) {
        rpcDispatcher.dispatch(mqttRequestId, payloadJson);
    }

    private void publishRpcAck(String mqttRequestId, RpcResultStatus status, RpcErrorCode errorCode, String message) {
        Map<String, Object> ack = new java.util.LinkedHashMap<>();
        ack.put("status", status.name());
        ack.put("errorCode", errorCode.name());
        ack.put("message", message);
        connection.publishRpcAck(mqttRequestId, gson.toJson(ack));
    }

    // ---- 连接与退避重连 ----

    private void scheduleConnectAttempt(long delayMillis) {
        reconnectExecutor.schedule(this::attemptConnect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void attemptConnect() {
        if (!started.get()) {
            return;
        }
        DeviceCredentials credentials = credentialStore.load();
        boolean ok = connection.connect(credentials);
        if (ok) {
            reconnectBackoff.reset();
            drainPendingAlarms();
        } else {
            scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
        }
    }
}
