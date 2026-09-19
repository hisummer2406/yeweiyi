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
import com.sansen.yeweiyi.logic.thingsboard.SharedAttributesParser;
import com.sansen.yeweiyi.logic.thingsboard.TankCalibrationAttributeBuilder;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
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
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tb-reconnect");
                t.setDaemon(true);
                return t;
            });
    /**
     * {@link #onRpcRequest} 把 dispatch 挪到这个独立单线程池上跑（PR #38 Codex 第三轮审核 P1）——
     * 见该方法上的注释。单线程而不是线程池，是为了保留"同一时刻只有一条 RPC 在处理"这个
     * {@link ProcessedCommandStore} 去重记录原本依赖的隐含串行顺序。
     */
    private final ExecutorService rpcDispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tb-rpc-dispatch");
        t.setDaemon(true);
        return t;
    });
    /**
     * {@link #reportAlarmEvent}/{@link #reportRawEvent} 之前直接在调用方线程上跑，最终会走到
     * {@link ThingsBoardMqttConnection#publish} 里同步阻塞的 {@code MqttClient.publish()}——
     * 这两个方法目前所有调用路径都从 UI 线程的保存按钮点击事件发起（{@code InOilFragment}/
     * {@code BuLuFragment}，经 {@code SocketManager} 同进程直连 {@code BackService}），
     * broker 慢或连接半开时会一直卡到 PUBACK/异常返回，足以触发 ANR（PR #38 Codex 第四轮审核 P1）。
     * 挪到独立线程池上跑，调用方（点击回调）立刻返回。单线程而不是线程池：这两类事件共用同一个
     * {@link #pendingAlarmStore}（内部方法已经是 {@code synchronized}，可以跨线程调用），单线程
     * 只是保留它们原本"逐个发生、顺序处理"的隐含顺序，不是为了避免并发损坏数据。
     */
    private final ExecutorService outboundEventExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tb-outbound-event");
        t.setDaemon(true);
        return t;
    });
    private final Gson gson = new Gson();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong snapshotSeq = new java.util.concurrent.atomic.AtomicLong(0);
    /**
     * {@link #requestSharedAttributes()} 每次连接成功后调一次，用来把这次请求跟
     * {@code attributes/response/{id}} 应答对上。只是本地请求/应答配对用的序号，不需要全局
     * 唯一或跨进程重启保持，重启后从 0 重新计数完全没问题。
     */
    private final java.util.concurrent.atomic.AtomicInteger attributesRequestId =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private ThingsBoardManager(Context context) {
        this.credentialStore = new ThingsBoardCredentialStore(context);

        String brokerUri = buildBrokerUri(SharePreManager.getIpBean().getIp(), SharePreManager.getIpBean().getPort());
        this.connection = new ThingsBoardMqttConnection(brokerUri,
                ApiConfig.THINGSBOARD_USE_TLS, new ThingsBoardCaStore(context),
                this::onRpcRequest, new SharedAttributesListener(), this::onConnectionLost);

        this.pendingAlarmStore = new PendingAlarmStore(new PendingAlarmFilePersistence(context), MAX_PENDING_ALARMS);
        this.commandStore = new ProcessedCommandStore(new ProcessedCommandFilePersistence(context),
                COMMAND_RETENTION_MILLIS);
        this.reconnectBackoff = new ReconnectBackoff(RECONNECT_INITIAL_DELAY_MILLIS, RECONNECT_MAX_DELAY_MILLIS, 2.0);

        RpcMethodRegistry registry = new RpcMethodRegistry();
        registry.register(SetAlarmThresholdHandler.METHOD,
                new SetAlarmThresholdHandler(OilTankRepositoryImpl.getInstance(),
                        () -> OilcanDataManager.getInstance(MyApplication.getInstance()).refresh()));
        HighRiskActionHandler highRiskActionHandler = new HighRiskActionHandler();
        registry.register(HighRiskActionHandler.METHOD_START_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_STOP_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_OPEN_VALVE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_CLOSE_VALVE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_ALARM_MUTE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_ALARM_CLEAR, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_DEVICE_REBOOT, highRiskActionHandler);

        this.rpcDispatcher = new RpcDispatcher(registry, commandStore, this::publishRpcAck, this::publishRpcResult,
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

    private static String buildBrokerUri(String host, int port) {
        String scheme = ApiConfig.THINGSBOARD_USE_TLS ? "ssl://" : "tcp://";
        return scheme + host + ":" + port;
    }

    /**
     * {@code ThingsBoardSetFragment} 保存新的 broker 地址/deviceId/accessToken 后调用——
     * 这三样之前都不存在任何写入入口（PR #38 人工审核 item 6 + broker 地址硬编码在
     * {@code SharePreManager.getIpBean()} 默认分支，从未真正可配置）。
     * <p>
     * 调用方已经把新的凭据写进 {@link #credentialStore}（{@code DeviceCredentials} 下次
     * {@link #attemptConnect()} 会自动重新 {@code load()}），这里只需要处理 broker 地址——
     * 它被 {@link ThingsBoardMqttConnection} 缓存在字段里，不会自己重新读
     * {@code SharePreManager}。主动断开旧连接（哪怕旧地址还连着），保证新地址/新凭据立即生效，
     * 而不是等到下一次意外断线才用上——凭据变更后应该主动重新认证，不能让设备继续用旧 session
     * "假装"已配网（同一条人工审核意见）。
     */
    public void applyUpdatedConnectionSettings(String host, int port) {
        connection.updateBrokerUri(buildBrokerUri(host, port));
        connection.disconnect();
        reconnectBackoff.reset();
        if (started.get()) {
            reconnectNow();
        }
    }

    // ---- 报警立即上报 ----

    /**
     * @param recovered false=报警产生，true=报警恢复。
     *                  <p>目前唯一的调用方（{@code BackService.sendAlarms}）只在
     *                  报警产生时触发，还没有一条真正的"恢复"事件源——见本类顶部说明和
     *                  issue #36 的已知缺口：恢复检测需要改 {@code AlarmDetection} 现有的
     *                  报警状态机，这次没有动它。
     *                  <p>实际发布挪到 {@link #outboundEventExecutor} 上异步执行，本方法立刻
     *                  返回——见该字段上的注释（PR #38 Codex 第四轮审核 P1）。时间戳在提交任务
     *                  之前、还在调用方线程上就取好并传给后台任务，不在任务真正执行时才取——
     *                  报警事件不像 stockEvent 那样自带一份可以在发布时用来纠正的独立时间戳，
     *                  前面排队的发布（比如一次卡住的 QoS 1 publish）攒起来的延迟会直接算到
     *                  这条报警的 {@code ts}/{@code alarm.tsMs} 上，把它在时序历史里错位
     *                  （PR #38 Codex 第五轮审核 P2）。
     */
    public void reportAlarmEvent(OilMessage message, boolean recovered) {
        long now = System.currentTimeMillis();
        outboundEventExecutor.execute(() -> doReportAlarmEvent(message, recovered, now));
    }

    private void doReportAlarmEvent(OilMessage message, boolean recovered, long now) {
        Map<String, Object> event = AlarmEventBuilder.build(message, recovered, message.toString(), now);
        if (event == null) {
            // message.getType() 不在云端封闭报警类型集合里（出入库/低温/网络断线等）——
            // 这个通道本来就不是给它们用的，不发不是丢事件，见 AlarmEventBuilder 类注释。
            Log.d(TAG, "reportAlarmEvent: type=" + message.getType() + " 没有对应的云端报警类型，跳过上报");
            return;
        }
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
     * <p>
     * {@code rawValue} 现在必须是 {@link StockEventMapper} 产出的 JSON（issue #42 待办3，
     * 之前这里直接透传 {@code DetailInoutRecord.toString()} 调试字符串，云端完全解析不了）。
     * 这里把它解析回 {@link com.google.gson.JsonElement} 再塞进 telemetry 的 {@code values}，
     * 使 {@code values.stockEvent} 在最终 payload 里是一个结构化对象（跟 fixture 形状一致），
     * 而不是一层双重转义的字符串——对齐 {@link AlarmEventBuilder}/{@code reportAlarmEvent}
     * 已经在用的"直接嵌对象"做法。解析失败（理论上不会发生，调用方现在只传合法 JSON）时
     * 退化为按字符串塞入，不丢事件，只是云端那一侧仍旧解析不了。
     * <p>
     * 实际发布挪到 {@link #outboundEventExecutor} 上异步执行，本方法立刻返回
     * （PR #38 Codex 第四轮审核 P1）。
     */
    public void reportRawEvent(String telemetryKey, String rawValue) {
        outboundEventExecutor.execute(() -> doReportRawEvent(telemetryKey, rawValue));
    }

    private void doReportRawEvent(String telemetryKey, String rawValue) {
        long now = System.currentTimeMillis();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        Object value;
        long tsMillis = now;
        try {
            com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(rawValue);
            value = parsed;
            // 事件自带的时间戳（stockEvent.tsMs）优先于"现在"——一次核对后的补发/覆盖如果外层
            // ts 用发送时刻，会在 ThingsBoard 时序库里落成新的一个点，而不是覆盖原来那个点
            // （PR #38 Codex 第四轮审核 P2）。
            tsMillis = TelemetrySnapshotBuilder.resolveEventTimestamp(parsed, now);
        } catch (com.google.gson.JsonSyntaxException e) {
            value = rawValue;
        }
        values.put(telemetryKey, value);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
        String json = gson.toJson(payload);
        if (!connection.publishTelemetry(json)) {
            pendingAlarmStore.enqueue(json);
        }
    }

    private void drainPendingAlarms() {
        pendingAlarmStore.drainAndSend(connection::publishTelemetry);
    }

    /**
     * 罐体标定表（zhongchengyeweiyi#42 待办1）——不进 30 秒快照，也不走补投队列：
     * 这是静态配置，不是"必须送达的事件"，下次连上（下次重连/重启）自然会带着最新值
     * 重发一次，断线期间丢一次没有实际影响。目前唯一的触发点是连接建立成功之后，
     * 跟"标定表被改" 这个动作本身没有绑定——{@code CreateOilTableFragment} 保存新标定表
     * 时不会立即推送，要等下一次重连才会带上新值。设备一旦部署通常长期在线，这意味着
     * 现场改标定表后云端可能要等到下次重启才能看到——已知的滞后，不是本次要解决的范围
     * （标定本来就是安装阶段一次性动作，重启一次拿到最新值可以接受）。
     */
    private void publishTankCalibrationAttributes() {
        List<com.sansen.yeweiyi.db.OilCanSet> oilCanSets = OilTankRepositoryImpl.getInstance().findAllOilCanSet();
        List<com.sansen.yeweiyi.db.OilCanTable> tables = OilTankRepositoryImpl.getInstance().findAllOilCanTable();
        Map<String, Object> attributes = TankCalibrationAttributeBuilder.build(oilCanSets, tables);
        if (attributes.isEmpty()) {
            return;
        }
        connection.publishAttributes(gson.toJson(attributes));
    }

    // ---- 共享属性（服务端→设备下发，issue #39/#40 P1-4）----

    /**
     * 每次连接成功后调一次，主动拉当前的 {@code reportIntervalSec} 值——只订阅
     * {@link ThingsBoardTopics#ATTRIBUTES_PUBLISH} 收不到"已经存在、这次连接之前就设置好"的值，
     * 只能收到之后的变化（见 {@link ThingsBoardMqttConnection.AttributesListener} 类注释）。
     * {@code alarmThresholds} 故意不在请求的 {@code sharedKeys} 里——{@link SharedAttributesParser}
     * 同样故意不解析它，两处理由一致，见该类类注释。
     */
    private void requestSharedAttributes() {
        connection.publishAttributesRequest(attributesRequestId.incrementAndGet(), "reportIntervalSec");
    }

    /**
     * {@code newPeriodMillis} 为 {@code null}（缺失/非法/非正）时什么都不做，保持当前周期——
     * 由 {@link SharedAttributesParser} 保证只有合法的正数才会走到这里。
     */
    private void applySharedAttributes(Long newPeriodMillis) {
        if (newPeriodMillis == null) {
            return;
        }
        Log.i(TAG, "共享属性下发 reportIntervalSec，快照周期改为 " + newPeriodMillis + "ms");
        telemetryScheduler.updatePeriod(newPeriodMillis);
    }

    /**
     * {@link ThingsBoardMqttConnection.AttributesListener} 有两个方法，不是函数式接口，
     * 拆成具名内部类而不是就地匿名类，单纯是为了这段代码在构造函数里不至于太长。
     * 两个入口最终都汇到同一个 {@link #applySharedAttributes}，区别只在解析载荷形状不同
     * （推送是根对象本身，应答包一层 {@code shared}），交给 {@link SharedAttributesParser}
     * 的两个不同入口处理。
     */
    private final class SharedAttributesListener implements ThingsBoardMqttConnection.AttributesListener {
        @Override
        public void onSharedAttributesPush(String payloadJson) {
            applySharedAttributes(SharedAttributesParser.parsePushedReportIntervalMillis(payloadJson));
        }

        @Override
        public void onAttributesResponse(String requestId, String payloadJson) {
            applySharedAttributes(SharedAttributesParser.parseResponseReportIntervalMillis(payloadJson));
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
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, now, snapshotSeq.incrementAndGet());
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, now);
        connection.publishTelemetry(gson.toJson(payload));
    }

    // ---- RPC ----

    /**
     * 这个方法是 {@link ThingsBoardMqttConnection.InternalCallback#messageArrived} 直接同步调进来的，
     * 跑在 Paho 的 MQTT 回调线程上。{@link RpcDispatcher#dispatch} 会在执行 handler 之前先同步发一次
     * RECEIVED ACK（{@code MqttClient.publish()} 阻塞等 PUBACK 完成）——Paho 官方明确警示不能在
     * 消息回调里做阻塞的客户端操作，会有死锁风险。挪到独立单线程池上跑，回调线程立刻返回去处理
     * 下一条 MQTT 消息（PR #38 Codex 第三轮审核 P1）。
     */
    private void onRpcRequest(String mqttRequestId, String payloadJson) {
        rpcDispatchExecutor.execute(() -> rpcDispatcher.dispatch(mqttRequestId, payloadJson));
    }

    /**
     * 发到 {@code v1/devices/me/rpc/response/{mqttRequestId}}：字段形状跟云端
     * {@code internal/protocol.Ack} 对齐（issue #39）——{@code status} 用小写 wire 值，
     * {@code errorCode}/{@code reason} 在没有内容时省略而不是发 {@code "NONE"}/空串，
     * {@code duplicate} 只在真为 true 时才带上。
     */
    private void publishRpcAck(String mqttRequestId, String commandId, RpcResultStatus status,
                                RpcErrorCode errorCode, String message, boolean duplicate, long tsMillis) {
        Map<String, Object> ack = new java.util.LinkedHashMap<>();
        ack.put("commandId", commandId);
        ack.put("status", status.wireValue());
        if (errorCode != RpcErrorCode.NONE) {
            ack.put("errorCode", errorCode.name());
        }
        if (message != null && !message.isEmpty()) {
            ack.put("reason", message);
        }
        if (duplicate) {
            ack.put("duplicate", true);
        }
        ack.put("tsMs", tsMillis);
        connection.publishRpcAck(mqttRequestId, gson.toJson(ack));
    }

    /**
     * 命令真正执行结果的异步上报：{@code {"cmdResult": {...}}} telemetry，跟报警事件同属
     * "必须送达、不能被下一次快照覆盖"的即时事件，复用同一条离线补投队列
     * （{@link PendingAlarmStore}），不单独再起一个。
     */
    private void publishRpcResult(String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message,
                                   Map<String, Object> data, long tsMillis) {
        Map<String, Object> cmdResult = new java.util.LinkedHashMap<>();
        cmdResult.put("commandId", commandId);
        cmdResult.put("status", status.wireValue());
        if (errorCode != RpcErrorCode.NONE) {
            cmdResult.put("errorCode", errorCode.name());
        }
        if (message != null && !message.isEmpty()) {
            cmdResult.put("reason", message);
        }
        if (data != null && !data.isEmpty()) {
            cmdResult.put("detail", data);
        }
        cmdResult.put("tsMs", tsMillis);

        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("cmdResult", cmdResult);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
        String json = gson.toJson(payload);
        if (!connection.publishTelemetry(json)) {
            pendingAlarmStore.enqueue(json);
        }
    }

    // ---- 连接与退避重连 ----

    /**
     * 连接建立后意外断线：{@link ThingsBoardMqttConnection.InternalCallback#connectionLost}
     * 只负责记日志和释放旧客户端，重连调度收口到这里——跟 {@link #attemptConnect()} 失败分支
     * 走同一条退避路径，避免两处各自维护重连节奏。
     */
    private void onConnectionLost(Throwable cause) {
        if (!started.get()) {
            return;
        }
        scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
    }

    private void scheduleConnectAttempt(long delayMillis) {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            // 已有一次重连在排队（比如 attemptConnect 失败分支和 connectionLost 前后脚触发），
            // 不重复排队，避免短时间内堆出多个连接尝试。
            return;
        }
        reconnectExecutor.schedule(this::attemptConnect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void attemptConnect() {
        reconnectScheduled.set(false);
        if (!started.get()) {
            return;
        }
        DeviceCredentials credentials = credentialStore.load();
        boolean ok = connection.connect(credentials);
        if (ok) {
            reconnectBackoff.reset();
            drainPendingAlarms();
            publishTankCalibrationAttributes();
            requestSharedAttributes();
        } else {
            scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
        }
    }
}
