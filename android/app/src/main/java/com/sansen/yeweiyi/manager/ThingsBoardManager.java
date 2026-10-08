package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.google.gson.Gson;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.logic.thingsboard.ActiveAlarmTracker;
import com.sansen.yeweiyi.logic.thingsboard.AlarmEventBuilder;
import com.sansen.yeweiyi.logic.thingsboard.ClientAttributesBuilder;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionMessages;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionResult;
import com.sansen.yeweiyi.logic.thingsboard.ReconnectBackoff;
import com.sansen.yeweiyi.logic.thingsboard.SharedAttributesParser;
import com.sansen.yeweiyi.logic.thingsboard.TankCalibrationAttributeBuilder;
import com.sansen.yeweiyi.logic.thingsboard.TelemetryScheduler;
import com.sansen.yeweiyi.logic.thingsboard.TelemetrySnapshotBuilder;
import com.sansen.yeweiyi.logic.thingsboard.UniqueTimestampAllocator;
import com.sansen.yeweiyi.logic.thingsboard.rpc.ProcessedCommandStore;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcDispatcher;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodRegistry;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcWirePayloads;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.HighRiskActionHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetAlarmThresholdHandler;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private static final long PROVISION_TIMEOUT_MILLIS = 15_000L;

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
    /**
     * 连接尝试的唯一排队入口（{@link #scheduleConnectAttempt}/{@link #reconnectNow}）的状态：
     * 同一时间最多排一个。每次排队分配一个新的代号，{@link #attemptConnect(long)} 发现代号已经不是最新的
     * 就直接返回——这样被 {@link #reconnectNow} 取代的旧任务即使已经在执行器队列里，也不会再派生出
     * 第二条重连链（代码审核 M2：之前 reconnectNow 绕过排队检查，每次网络抖动都多出一条链）。
     */
    private final Object connectLock = new Object();
    private ScheduledFuture<?> pendingConnect;
    private long connectGeneration;
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
     * {@link #reportAlarmTransition}/{@link #reportRawEvent} 之前直接在调用方线程上跑，最终会走到
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
    /**
     * 即时事件（报警、出入库）的外层 ts 不能重复：ThingsBoard 时序库里"设备 + key + ts"相同的点
     * 后写覆盖先写。只在 {@link #outboundEventExecutor} 上调用。
     */
    private final UniqueTimestampAllocator eventTsAllocator = new UniqueTimestampAllocator();
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 只在 tb-reconnect 线程上写，配网页读。 */
    private volatile ProvisionStatus provisionStatus = new ProvisionStatus(ProvisionState.NONE, null);
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

        // 队列满时先成对丢报警（"产生"连同它的"恢复"），"恢复"从不单独丢；保住出入库和命令结果
        this.pendingAlarmStore = new PendingAlarmStore(new PendingAlarmFilePersistence(context), MAX_PENDING_ALARMS,
                AlarmEventBuilder.EVICTION_POLICY);
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
        // 罐配置变更（保存罐设置、RPC 改阈值等都会 refresh）后：清理已删除/停用罐的报警，并立即重发
        // 客户端属性，不等下次重连
        OilcanDataManager.getInstance(MyApplication.getInstance()).setConfigChangedListener(this::onTankConfigReloaded);
        // 启动时也清理一次：App 没运行期间罐可能已经被删除或停用
        CloudAlarmReporter.getInstance().onTanksReloaded(currentEnabledTankIds());
        scheduleConnectAttempt(0);
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        OilcanDataManager.getInstance(MyApplication.getInstance()).setConfigChangedListener(null);
        telemetryScheduler.stop();
        synchronized (connectLock) {
            if (pendingConnect != null) {
                pendingConnect.cancel(false);
                pendingConnect = null;
            }
            connectGeneration++;
        }
        // 挪到重连线程上断开：stop() 由 BackService.onDestroy 在主线程调用，而 disconnect() 跟 connect()
        // 共用一把锁，后台正在持锁连接时（最长 10 秒超时）会卡住主线程（代码审核 H8）
        reconnectExecutor.execute(connection::disconnect);
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * 供 {@code BackService} 网络变化回调、配网保存后触发一次立即重连，不等退避到期。已经排着的那次
     * （可能还要等几十秒）被取消并取代，不会多出一条重连链。
     */
    public void reconnectNow() {
        synchronized (connectLock) {
            if (pendingConnect != null) {
                pendingConnect.cancel(false);
            }
            long generation = ++connectGeneration;
            pendingConnect = reconnectExecutor.schedule(() -> attemptConnect(generation), 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 后台线程池任务的保护层：未捕获的异常会经全局崩溃处理把整个应用带崩，上报出错不能影响本地业务。
     */
    private static Runnable guarded(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, what + "失败", e);
            }
        };
    }

    private static String buildBrokerUri(String host, int port) {
        if (port == 8883 && !ApiConfig.THINGSBOARD_USE_TLS) {
            // 连接失败时界面看不出原因，留一条日志方便现场排查
            Log.w(TAG, "broker 端口 8883 通常只接受 TLS，但本构建未开启 sansen.thingsboardUseTls，将以明文连接，大概率失败");
        }
        String scheme = ApiConfig.THINGSBOARD_USE_TLS ? "ssl://" : "tcp://";
        return scheme + host + ":" + port;
    }

    /**
     * {@code ThingsBoardSetFragment} 保存新的 broker 地址/deviceId/accessToken 后调用——
     * 这三样之前都不存在任何写入入口（PR #38 人工审核 item 6 + broker 地址硬编码在
     * {@code SharePreManager.getIpBean()} 默认分支，从未真正可配置）。
     * <p>
     * 调用方已经把新的凭据写进 {@link #credentialStore}（{@code DeviceCredentials} 下次
     * {@link #attemptConnect(long)} 会自动重新 {@code load()}），这里只需要处理 broker 地址——
     * 它被 {@link ThingsBoardMqttConnection} 缓存在字段里，不会自己重新读
     * {@code SharePreManager}。主动断开旧连接（哪怕旧地址还连着），保证新地址/新凭据立即生效，
     * 而不是等到下一次意外断线才用上——凭据变更后应该主动重新认证，不能让设备继续用旧 session
     * "假装"已配网（同一条人工审核意见）。
     */
    public void applyUpdatedConnectionSettings(String host, int port) {
        String brokerUri = buildBrokerUri(host, port);
        // 全部挪到重连线程上执行，调用方（配网页的保存按钮，UI 线程）立即返回：disconnect() 跟 connect()
        // 共用一把锁，现场通常正是"连不上"才来改地址，这时后台多半正在持锁重连（最长 10 秒超时），
        // 直接在 UI 线程上 disconnect 会卡住界面甚至 ANR（代码审核 H8）。
        reconnectExecutor.execute(() -> applyUpdatedConnectionSettingsOnReconnectThread(brokerUri));
    }

    private void applyUpdatedConnectionSettingsOnReconnectThread(String brokerUri) {
        // 重新保存即解除"云端拒绝后停止注册"的状态
        setProvisionStatus(ProvisionState.NONE, null);
        connection.updateBrokerUri(brokerUri);
        connection.disconnect();
        reconnectBackoff.reset();
        if (started.get()) {
            reconnectNow();
        }
    }

    // ---- 报警立即上报 ----

    /**
     * 报警产生/恢复的上报出口，唯一调用方是 {@link CloudAlarmReporter}（它决定什么时候算产生、
     * 什么时候算恢复，并保证 ACTIVE/CLEARED 成对、alarmId 按次生成）。
     * <p>
     * 实际发布挪到 {@link #outboundEventExecutor} 上异步执行，本方法立刻返回——见该字段上的
     * 注释（PR #38 Codex 第四轮审核 P1）。时间戳用 {@code transition.tsMs}，是在调用方线程上
     * 状态变化发生那一刻取的，不在任务真正执行时才取——前面排队的发布（比如一次卡住的 QoS 1
     * publish）攒起来的延迟不能算到这条报警的 {@code ts}/{@code alarm.tsMs} 上
     * （PR #38 Codex 第五轮审核 P2）。
     */
    public void reportAlarmTransition(ActiveAlarmTracker.Transition transition) {
        outboundEventExecutor.execute(guarded("报警上报", () -> doReportAlarmTransition(transition)));
    }

    private void doReportAlarmTransition(ActiveAlarmTracker.Transition transition) {
        ActiveAlarmTracker.Entry entry = transition.entry;
        // 删罐/停用罐时一次恢复多条报警，它们的 tsMs 是同一个时刻；外层 ts 相同、key 都是 alarm，
        // 在 ThingsBoard 时序库里会互相覆盖，云端报警历史只剩一条恢复。这里保证互不相同，
        // alarm.tsMs 跟外层 ts 保持一致（共享 fixture 里两者相等）。
        long now = eventTsAllocator.allocate(transition.tsMs);
        Map<String, Object> event = AlarmEventBuilder.build(entry.type, entry.tank, entry.alarmId,
                transition.recovered, entry.text, transition.value, now);
        if (event == null) {
            // ActiveAlarmTracker 只接收可上报类型，这里正常不会走到。
            Log.w(TAG, "reportAlarmTransition: type=" + entry.type + " 没有对应的云端报警类型，跳过上报");
            return;
        }
        Map<String, Object> payload = AlarmEventBuilder.wrapAsTelemetry(event, now);
        publishEventInOrder(gson.toJson(payload));
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
     * 而不是一层双重转义的字符串——对齐 {@link AlarmEventBuilder}/{@link #reportAlarmTransition}
     * 已经在用的"直接嵌对象"做法。解析失败（理论上不会发生，调用方现在只传合法 JSON）时
     * 退化为按字符串塞入，不丢事件，只是云端那一侧仍旧解析不了。
     * <p>
     * 实际发布挪到 {@link #outboundEventExecutor} 上异步执行，本方法立刻返回
     * （PR #38 Codex 第四轮审核 P1）。
     * <p>
     * 外层 {@code ts} 是事件生成的时刻（在调用方线程上取，不算排队延迟），并经
     * {@link #eventTsAllocator} 保证互不相同。不能用事件自带的 {@code tsMs}：它来自
     * {@code DetailInoutRecord} 的时间字符串，只精确到分钟，两个罐在同一分钟内结束出入库时
     * 外层 ts 相同，ThingsBoard 时序库里后一笔会覆盖前一笔，云端只能看到一笔。事件发生的时刻
     * 仍然在 {@code tsMs} 里，云端按它归入查询窗口；补投时发送的是已经序列化好的报文，
     * 外层 ts 原样保留。
     */
    public void reportRawEvent(String telemetryKey, String rawValue) {
        long generatedAt = System.currentTimeMillis();
        outboundEventExecutor.execute(guarded("事件上报", () -> doReportRawEvent(telemetryKey, rawValue, generatedAt)));
    }

    private void doReportRawEvent(String telemetryKey, String rawValue, long generatedAt) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        Object value;
        try {
            value = new com.google.gson.JsonParser().parse(rawValue);
        } catch (com.google.gson.JsonSyntaxException e) {
            value = rawValue;
        }
        long tsMillis = eventTsAllocator.allocate(generatedAt);
        values.put(telemetryKey, value);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
        publishEventInOrder(gson.toJson(payload));
    }

    /**
     * 即时事件（报警/出入库/命令结果）的统一发送出口，保证按产生顺序到达云端：补发队列里还有更早的
     * 事件没发出去时，新事件排到队尾、按序补发，不抢在前面实时发出。否则断网恢复后、补发还没完成时，
     * 一条"恢复"可能比队列里它自己的"产生"先到，云端那条报警就永远停在 ACTIVE。
     */
    private void publishEventInOrder(String json) {
        if (pendingAlarmStore.size() == 0 && connection.publishTelemetry(json)) {
            return;
        }
        pendingAlarmStore.enqueue(json);
        if (connection.isConnected()) {
            drainPendingAlarms();
        }
    }

    private void drainPendingAlarms() {
        pendingAlarmStore.drainAndSend(connection::publishTelemetry);
    }

    /**
     * 客户端属性：协议 §5.1 的设备配置（{@link ClientAttributesBuilder}）+ 罐体标定表
     * （{@link TankCalibrationAttributeBuilder}，zhongchengyeweiyi#42 待办1），合成一条发出。
     * <p>
     * 不进 30 秒快照，也不走补投队列：这是静态配置，不是"必须送达的事件"，断线时这次没发出去，
     * 下次连上会带着最新值重发。触发点两个：连接建立成功之后；罐配置重新加载
     * （{@code OilcanDataManager.refresh()}，保存罐设置、RPC 改阈值都会触发）之后。
     * 标定表的保存不一定走 refresh，改了标定表可能要等下次重连才更新——标定是安装阶段的一次性
     * 动作，这个滞后可以接受。
     */
    private void publishClientAttributes() {
        // 连接成功后紧跟着要请求共享属性；这里抛异常会让那一步被跳过，所以就地吞掉并记日志
        try {
            Map<String, Object> attributes = new java.util.LinkedHashMap<>(
                    ClientAttributesBuilder.build(currentTankAttributes()));
            List<com.sansen.yeweiyi.db.OilCanSet> oilCanSets = OilTankRepositoryImpl.getInstance().findAllOilCanSet();
            List<com.sansen.yeweiyi.db.OilCanTable> tables = OilTankRepositoryImpl.getInstance().findAllOilCanTable();
            attributes.putAll(TankCalibrationAttributeBuilder.build(oilCanSets, tables));
            connection.publishAttributes(gson.toJson(attributes));
        } catch (RuntimeException e) {
            Log.e(TAG, "客户端属性发布失败", e);
        }
    }

    private void onTankConfigReloaded() {
        CloudAlarmReporter.getInstance().onTanksReloaded(currentEnabledTankIds());
        outboundEventExecutor.execute(guarded("客户端属性上报", this::publishClientAttributes));
    }

    /** OilcanDataManager 里当前加载的罐号——它只加载启用的罐（setProbeData 按 enable==1 过滤）。 */
    private java.util.Set<Integer> currentEnabledTankIds() {
        SparseArray<OilCan> oilCans = OilcanDataManager.getInstance(MyApplication.getInstance()).getData();
        java.util.Set<Integer> tanks = new java.util.HashSet<>();
        for (int i = 0; i < oilCans.size(); i++) {
            tanks.add(oilCans.keyAt(i));
        }
        return tanks;
    }

    /** 跟遥测快照同一个数据来源：OilcanDataManager 里当前加载的各罐。 */
    private List<ClientAttributesBuilder.Tank> currentTankAttributes() {
        SparseArray<OilCan> oilCans = OilcanDataManager.getInstance(MyApplication.getInstance()).getData();
        List<ClientAttributesBuilder.Tank> tanks = new java.util.ArrayList<>();
        for (int i = 0; i < oilCans.size(); i++) {
            OilCan oilCan = oilCans.valueAt(i);
            tanks.add(new ClientAttributesBuilder.Tank(oilCan.getOilCanPos(), oilCan.getProbeAdr(),
                    oilCan.getProbeType(), oilCan.getMaxVolume()));
        }
        return tanks;
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
        rpcDispatchExecutor.execute(guarded("RPC 处理", () -> rpcDispatcher.dispatch(mqttRequestId, payloadJson)));
    }

    /**
     * 发到 {@code v1/devices/me/rpc/response/{mqttRequestId}}：字段形状跟云端
     * {@code internal/protocol.Ack} 对齐（issue #39）——{@code status} 用小写 wire 值，
     * {@code errorCode}/{@code reason} 在没有内容时省略而不是发 {@code "NONE"}/空串，
     * {@code duplicate} 只在真为 true 时才带上。
     */
    private void publishRpcAck(String mqttRequestId, String commandId, RpcResultStatus status,
                                RpcErrorCode errorCode, String message, boolean duplicate, long tsMillis) {
        Map<String, Object> ack = RpcWirePayloads.ack(commandId, status, errorCode, message, duplicate, tsMillis);
        connection.publishRpcAck(mqttRequestId, gson.toJson(ack));
    }

    /**
     * 命令真正执行结果的异步上报：{@code {"cmdResult": {...}}} telemetry，跟报警事件同属
     * "必须送达、不能被下一次快照覆盖"的即时事件，复用同一条离线补投队列
     * （{@link PendingAlarmStore}），不单独再起一个。
     */
    private void publishRpcResult(String commandId, RpcResultStatus status, RpcErrorCode errorCode, String message,
                                   Map<String, Object> data, long tsMillis) {
        Map<String, Object> cmdResult = RpcWirePayloads.cmdResult(commandId, status, errorCode, message, data, tsMillis);

        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("cmdResult", cmdResult);
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, tsMillis);
        publishEventInOrder(gson.toJson(payload));
    }

    // ---- 连接与退避重连 ----

    /**
     * 连接建立后意外断线：{@link ThingsBoardMqttConnection.InternalCallback#connectionLost}
     * 只负责记日志和释放旧客户端，重连调度收口到这里——跟 {@link #attemptConnect(long)} 失败分支
     * 走同一条退避路径，避免两处各自维护重连节奏。
     */
    private void onConnectionLost(Throwable cause) {
        if (!started.get()) {
            return;
        }
        scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
    }

    private void scheduleConnectAttempt(long delayMillis) {
        synchronized (connectLock) {
            if (pendingConnect != null) {
                // 已有一次重连在排队（比如 attemptConnect 失败分支和 connectionLost 前后脚触发），
                // 不重复排队，避免短时间内堆出多个连接尝试。
                return;
            }
            long generation = ++connectGeneration;
            pendingConnect = reconnectExecutor.schedule(() -> attemptConnect(generation), delayMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void attemptConnect(long generation) {
        synchronized (connectLock) {
            if (generation != connectGeneration) {
                return; // 已被更新的一次排队取代（reconnectNow/stop），不再执行，也不再派生重试
            }
            pendingConnect = null;
        }
        if (!started.get()) {
            return;
        }
        DeviceCredentials credentials = credentialStore.load();
        if (!credentials.isValid() && notEmpty(credentials.getDeviceId())) {
            credentials = provisionAccessToken(credentials.getDeviceId());
            if (credentials == null) {
                return; // provisionAccessToken 已决定退避重试或停下等重新保存
            }
        }
        boolean ok = connection.connect(credentials);
        if (ok) {
            reconnectBackoff.reset();
            drainPendingAlarms();
            publishClientAttributes();
            requestSharedAttributes();
        } else {
            scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
        }
    }

    // ---- 设备自助注册（Provisioning）----

    /**
     * 有编号、无 token 时向 ThingsBoard 领 token。成功返回新凭据；云端拒绝或没配 key/secret 时停下，
     * 等配网页重新保存（{@link #applyUpdatedConnectionSettings}）再试；网络类失败按退避重试。
     */
    private DeviceCredentials provisionAccessToken(String deviceId) {
        if (!notEmpty(ApiConfig.PROVISION_DEVICE_KEY) || !notEmpty(ApiConfig.PROVISION_DEVICE_SECRET)) {
            Log.w(TAG, "未配置 provision key/secret，无法自动领取 token，只能手填");
            setProvisionStatus(ProvisionState.NOT_CONFIGURED, null);
            return null;
        }
        if (provisionStatus.state == ProvisionState.REJECTED) {
            return null;
        }
        setProvisionStatus(ProvisionState.IN_PROGRESS, null);
        String request = ProvisionMessages.buildRequest(deviceId, ApiConfig.PROVISION_DEVICE_KEY,
                ApiConfig.PROVISION_DEVICE_SECRET);
        ProvisionResult result = connection.provision(deviceId, request, PROVISION_TIMEOUT_MILLIS);
        Log.i(TAG, "设备注册结果: deviceId=" + deviceId + " " + result);
        switch (result.getOutcome()) {
            case SUCCESS:
                DeviceCredentials provisioned = new DeviceCredentials(deviceId, result.getAccessToken());
                if (!credentialStore.saveIfAwaitingToken(provisioned)) {
                    // 云端已把这个编号标记为已领取，之后再领会被拒，需云端重置
                    Log.w(TAG, "领取期间配网页改了编号或手填了 token，编号 " + deviceId
                            + " 领到的 token 已丢弃；该编号在云端已被占用，需云端重置后才能再领");
                    return null;
                }
                setProvisionStatus(ProvisionState.SUCCESS, null);
                return provisioned;
            case REJECTED:
                setProvisionStatus(ProvisionState.REJECTED, result.getErrorMessage());
                return null;
            default:
                setProvisionStatus(ProvisionState.NETWORK_ERROR, result.getErrorMessage());
                scheduleConnectAttempt(reconnectBackoff.nextDelayMillis());
                return null;
        }
    }

    private void setProvisionStatus(ProvisionState state, String errorMessage) {
        provisionStatus = new ProvisionStatus(state, errorMessage);
    }

    /** 最近一次自动领 token 的状态，供配网页显示。 */
    public ProvisionStatus getProvisionStatus() {
        return provisionStatus;
    }

    public enum ProvisionState {
        NONE, IN_PROGRESS, SUCCESS, REJECTED, NETWORK_ERROR, NOT_CONFIGURED
    }

    public static final class ProvisionStatus {
        public final ProvisionState state;
        /** 云端 errorMsg 或本地失败原因，可能为 null。 */
        public final String errorMessage;

        ProvisionStatus(ProvisionState state, String errorMessage) {
            this.state = state;
            this.errorMessage = errorMessage;
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
