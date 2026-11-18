package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.google.gson.Gson;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.logic.thingsboard.ActiveAlarmTracker;
import com.sansen.yeweiyi.logic.thingsboard.AlarmEventBuilder;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.thingsboard.ClientAttributesBuilder;
import com.sansen.yeweiyi.logic.thingsboard.DeviceSettingsAttributeBuilder;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.logic.thingsboard.IdentityScope;
import com.sansen.yeweiyi.logic.thingsboard.PendingAlarmStore;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionMessages;
import com.sansen.yeweiyi.logic.thingsboard.ProvisionResult;
import com.sansen.yeweiyi.logic.thingsboard.ReconnectBackoff;
import com.sansen.yeweiyi.logic.thingsboard.SharedAttributesParser;
import com.sansen.yeweiyi.logic.thingsboard.SnapshotTrigger;
import com.sansen.yeweiyi.logic.thingsboard.TankCalibrationAttributeBuilder;
import com.sansen.yeweiyi.logic.thingsboard.TankSettingsAttributeBuilder;
import com.sansen.yeweiyi.logic.thingsboard.TelemetryScheduler;
import com.sansen.yeweiyi.logic.thingsboard.TelemetrySnapshotBuilder;
import com.sansen.yeweiyi.logic.thingsboard.UniqueTimestampAllocator;
import com.sansen.yeweiyi.applog.CloudCmdLog;
import com.sansen.yeweiyi.logic.thingsboard.rpc.ProcessedCommandStore;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcDispatcher;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodRegistry;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus;
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcWirePayloads;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.HighRiskActionHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetDeviceSettingsHandler;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetTankSettingsHandler;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetTankTableHandler;
import com.sansen.yeweiyi.factory.EllipseTable;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SharePrefCustomOilNameStore;
import com.sansen.yeweiyi.logic.thingsboard.rpc.handler.SetAlarmThresholdHandler;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.logic.thingsboard.RecordOutbox;
import com.sansen.yeweiyi.logic.thingsboard.ShiftEventMapper;
import com.sansen.yeweiyi.logic.thingsboard.StockRecordMapper;
import com.sansen.yeweiyi.repository.ShiftRepositoryImpl;
import com.sansen.yeweiyi.utils.Singleton;

import java.util.List;
import java.util.TimeZone;
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
    /** 每秒检查一次是否该发快照（保底周期或液位变化），见 {@link SnapshotTrigger}。 */
    private static final long SNAPSHOT_CHECK_MILLIS = 1_000L;
    private static final long COMMAND_RETENTION_MILLIS = 24L * 60 * 60 * 1000; // 24 小时
    private static final int MAX_PENDING_ALARMS = 200;
    /** 交班/库存上报通道容量：16 罐每小时一条库存，离线约 40 天才会开始丢最旧的。 */
    private static final int MAX_RECORD_OUTBOX = 1000;
    private static final long RECONNECT_INITIAL_DELAY_MILLIS = 2_000L;
    private static final long RECONNECT_MAX_DELAY_MILLIS = 60_000L;
    private static final long PROVISION_TIMEOUT_MILLIS = 15_000L;
    /** 罐设置保存是逐字段异步写库，等这么久再读库，同时把连续多次保存合并成一次上报。 */
    private static final long ATTRIBUTES_DEBOUNCE_MILLIS = 2_000L;

    private static ThingsBoardManager instance;

    public static synchronized ThingsBoardManager getInstance(Context context) {
        if (instance == null) {
            instance = new ThingsBoardManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final ThingsBoardCredentialStore credentialStore;
    /**
     * 待补投队列、ACTIVE 报警、命令去重记录当前绑定的设备身份（出厂编码）。这些状态按身份分文件，
     * 身份一换（新编码）就切到新身份名下的存储，旧身份的原样留在盘上、不发送——见 {@link IdentityScope}。
     */
    private final Object identityLock = new Object();
    private String boundDeviceId;
    private final ThingsBoardMqttConnection connection;
    private final TelemetryScheduler telemetryScheduler;
    private final SnapshotTrigger snapshotTrigger = new SnapshotTrigger(TELEMETRY_PERIOD_MILLIS);
    private final PendingAlarmStore pendingAlarmStore;
    private final RecordOutbox recordOutbox;
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
    private final Object attributesLock = new Object();
    private ScheduledFuture<?> pendingAttributesPublish;
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
        this.appContext = context;
        this.credentialStore = new ThingsBoardCredentialStore(context);
        this.boundDeviceId = IdentityScope.normalize(credentialStore.load().getDeviceId());

        String brokerUri = buildBrokerUri(SharePreManager.getIpBean().getIp(), SharePreManager.getIpBean().getPort());
        this.connection = new ThingsBoardMqttConnection(brokerUri,
                ApiConfig.THINGSBOARD_USE_TLS, new ThingsBoardCaStore(context),
                this::onRpcRequest, new SharedAttributesListener(), this::onConnectionLost);

        // 队列满时先成对丢报警（"产生"连同它的"恢复"），"恢复"从不单独丢；保住出入库和命令结果
        this.pendingAlarmStore = new PendingAlarmStore(new PendingAlarmFilePersistence(context, boundDeviceId), MAX_PENDING_ALARMS,
                AlarmEventBuilder.EVICTION_POLICY);
        this.recordOutbox = new RecordOutbox(new RecordOutboxFilePersistence(context, boundDeviceId), MAX_RECORD_OUTBOX);
        this.commandStore = new ProcessedCommandStore(new ProcessedCommandFilePersistence(context, boundDeviceId),
                COMMAND_RETENTION_MILLIS);
        this.reconnectBackoff = new ReconnectBackoff(RECONNECT_INITIAL_DELAY_MILLIS, RECONNECT_MAX_DELAY_MILLIS, 2.0);

        RpcMethodRegistry registry = new RpcMethodRegistry();
        registry.register(SetAlarmThresholdHandler.METHOD,
                new SetAlarmThresholdHandler(OilTankRepositoryImpl.getInstance(),
                        () -> OilcanDataManager.getInstance(MyApplication.getInstance()).refresh()));
        registry.register(SetTankSettingsHandler.METHOD,
                new SetTankSettingsHandler(OilTankRepositoryImpl.getInstance(),
                        () -> OilcanDataManager.getInstance(MyApplication.getInstance()).refresh(),
                        new SharePrefCustomOilNameStore(MyApplication.getInstance()),
                        hex -> SystemUtils.getRsStr(R.string.rpc_oil_color_not_in_palette, hex)));
        registry.register(SetDeviceSettingsHandler.METHOD, new SetDeviceSettingsHandler(this::saveDeviceSettings));
        registry.register(SetTankTableHandler.METHOD,
                new SetTankTableHandler(OilTankRepositoryImpl.getInstance(), new EllipseTable(),
                        () -> OilcanDataManager.getInstance(MyApplication.getInstance()).refresh(),
                        DateUtils::getYYYMMddHHmmss));
        HighRiskActionHandler highRiskActionHandler = new HighRiskActionHandler();
        registry.register(HighRiskActionHandler.METHOD_START_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_STOP_PUMP, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_OPEN_VALVE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_CLOSE_VALVE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_ALARM_MUTE, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_ALARM_CLEAR, highRiskActionHandler);
        registry.register(HighRiskActionHandler.METHOD_DEVICE_REBOOT, highRiskActionHandler);

        this.rpcDispatcher = new RpcDispatcher(registry, commandStore, this::publishRpcAck, this::publishRpcResult,
                System::currentTimeMillis, CloudCmdLog.INSTANCE);

        this.telemetryScheduler = new TelemetryScheduler(this::buildAndPublishSnapshot, SNAPSHOT_CHECK_MILLIS,
                error -> Log.e(TAG, "遥测快照发布失败", error));
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        int recovered = commandStore.recoverAfterRestart(System.currentTimeMillis());
        if (recovered > 0) {
            CloudCmdLog.onRecoveredAfterRestart(recovered);
        }
        telemetryScheduler.start();
        // 罐配置变更（保存罐设置、RPC 改阈值等都会 refresh）后：清理已删除/停用罐的报警，并立即重发
        // 客户端属性，不等下次重连
        OilcanDataManager.getInstance(MyApplication.getInstance()).setConfigChangedListener(this::onTankConfigReloaded);
        OilTankRepositoryImpl.getInstance().setOilCanSetChangedListener(this::requestClientAttributesPublish);
        ShiftRepositoryImpl.getInstance().setShiftChangedListener(this::reportShiftChanged);
        // 启动时也清理一次：App 没运行期间罐可能已经被删除或停用
        CloudAlarmReporter.getInstance().onTanksReloaded(currentEnabledTankIds());
        scheduleConnectAttempt(0);
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        OilcanDataManager.getInstance(MyApplication.getInstance()).setConfigChangedListener(null);
        OilTankRepositoryImpl.getInstance().setOilCanSetChangedListener(null);
        ShiftRepositoryImpl.getInstance().setShiftChangedListener(null);
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
        // 先断开再切身份：连接还挂着旧身份时，新身份名下的队列不能被它发出去
        connection.disconnect();
        bindIdentity(credentialStore.load().getDeviceId());
        reconnectBackoff.reset();
        if (started.get()) {
            reconnectNow();
        }
    }

    /**
     * {@code config.setDeviceSettings} 的落盘：写基础设置页同一份 SharedPreferences，按 commit() 结果确认
     * 写盘，再刷新 {@link Singleton}（出入库检测读它），最后重发客户端属性回显新值。
     */
    /**
     * 整机设置落盘：全部字段一次 commit（{@link SharePrefUtil#saveIntsChecked}），要么全写入要么都不改（#9）。
     * 写入成功后才刷新 Singleton 并重发客户端属性；失败时什么都没改，回执 FAILED。
     */
    private void saveDeviceSettings(Map<String, Integer> values) {
        Context context = MyApplication.getInstance().getApplicationContext();
        Map<String, Integer> prefs = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String prefKey = DEVICE_SETTING_PREF_KEYS.get(entry.getKey());
            if (prefKey == null) {
                throw new IllegalStateException("没有对应的本地配置项: " + entry.getKey());
            }
            prefs.put(prefKey, entry.getValue());
        }
        if (!SharePrefUtil.saveIntsChecked(context, prefs)) {
            throw new IllegalStateException("写入配置失败，未修改任何字段");
        }
        for (String prefKey : prefs.keySet()) {
            Singleton.getInstance().getSetData(prefKey);
        }
        requestClientAttributesPublish();
    }

    /** 下发字段名 → 基础设置页使用的 SharedPreferences 键（{@code Constans}）。 */
    private static final Map<String, String> DEVICE_SETTING_PREF_KEYS = new java.util.HashMap<>();

    static {
        DEVICE_SETTING_PREF_KEYS.put(DeviceSettingsAttributeBuilder.KEY_IN_HEIGHT_MM, Constans.InHight);
        DEVICE_SETTING_PREF_KEYS.put(DeviceSettingsAttributeBuilder.KEY_IN_TIME_SEC, Constans.InTime);
        DEVICE_SETTING_PREF_KEYS.put(DeviceSettingsAttributeBuilder.KEY_OUT_HEIGHT_MM, Constans.OutHight);
        DEVICE_SETTING_PREF_KEYS.put(DeviceSettingsAttributeBuilder.KEY_OUT_TIME_SEC, Constans.OutTime);
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
                transition.recovered, entry.text, transition.value, now, entry.oilType, entry.oilTypeIndex);
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

    // ---- 交班/库存记录（zhongchengyeweiyi#49，契约 shift-stock-records-contract §2.3）----
    // 走独立的 RecordOutbox，不进上面的报警补投队列；与报警/出入库之间不保证先后。

    /** 交班新增/结束/手工修改已落库：整班按库里最新内容（重新）上报。演示模式不报。 */
    public void reportShiftChanged(int localId) {
        if (isDemoMode()) {
            return;
        }
        long generatedAt = System.currentTimeMillis();
        outboundEventExecutor.execute(guarded("交班上报", () -> {
            recordOutbox.enqueueShift(localId, eventTsAllocator.allocate(generatedAt));
            drainRecordOutbox();
        }));
    }

    /** 一次库存保存（全部罐一条）。报文在入队时生成，库存记录保存后不再修改。演示模式不报。 */
    public void reportStockRecord(String trigger, long tsMs, List<StockRecordMapper.Tank> tanks) {
        if (isDemoMode()) {
            return;
        }
        outboundEventExecutor.execute(guarded("库存上报", () -> {
            long ts = eventTsAllocator.allocate(tsMs);
            Map<String, Object> payload = StockRecordMapper.wrapAsTelemetry(
                    StockRecordMapper.build(trigger, tsMs, tanks), ts);
            recordOutbox.enqueueStock(gson.toJson(payload), ts);
            drainRecordOutbox();
        }));
    }

    /** 只在 {@link #outboundEventExecutor} 上调用。 */
    private void drainRecordOutbox() {
        if (connection.isConnected()) {
            recordOutbox.drainAndSend(this::resolveShiftTelemetry, connection::publishTelemetry);
        }
    }

    /** 按本地 id 回读交班行并组装报文；行已被本地删除时返回 null（不同步删除，丢弃该条）。 */
    private String resolveShiftTelemetry(int localId, long ts) {
        CommitWork row = ShiftRepositoryImpl.getInstance().findById(localId);
        if (row == null) {
            return null;
        }
        Map<String, Object> event = ShiftEventMapper.build(row, TimeZone.getDefault());
        if (event == null) {
            Log.w(TAG, "交班 id=" + localId + " 开始时间无法解析，跳过上报");
            return null;
        }
        return gson.toJson(ShiftEventMapper.wrapAsTelemetry(event, ts));
    }

    private boolean isDemoMode() {
        return SharePrefUtil.getBoolean(appContext, SharePrefUtil.TEST_DATA, false);
    }

    /**
     * 客户端属性合成一条发出：协议 §5.1 设备配置 + 标定表 + 罐设置页各项 + 出入库阈值。
     * 静态配置，不走补投队列，断线时没发出去下次连上会重发。触发：连接成功后；设置变化后经
     * {@link #requestClientAttributesPublish()} 防抖重发（罐设置和罐表写库都会触发，见 OilTankRepositoryImpl）。
     */
    private void publishClientAttributes() {
        // 连接成功后紧跟着要请求共享属性；这里抛异常会让那一步被跳过，所以就地吞掉并记日志
        try {
            Map<String, Object> attributes = new java.util.LinkedHashMap<>(
                    ClientAttributesBuilder.build(currentTankAttributes()));
            List<com.sansen.yeweiyi.db.OilCanSet> oilCanSets = OilTankRepositoryImpl.getInstance().findAllOilCanSet();
            List<com.sansen.yeweiyi.db.OilCanTable> tables = OilTankRepositoryImpl.getInstance().findAllOilCanTable();
            attributes.putAll(TankCalibrationAttributeBuilder.build(oilCanSets, tables));
            attributes.putAll(TankSettingsAttributeBuilder.build(oilCanSets, Const::getOilType));
            Singleton settings = Singleton.getInstance();
            attributes.putAll(DeviceSettingsAttributeBuilder.build(
                    settings.InHight, settings.InTime, settings.OutHight, settings.OutTime));
            attributes.put(DeviceSettingsAttributeBuilder.KEY_CUSTOM_MEDIA_SYNC, 1);
            attributes.put(DeviceSettingsAttributeBuilder.KEY_OIL_COLOR_SYNC, 1);
            connection.publishAttributes(gson.toJson(attributes));
        } catch (RuntimeException e) {
            Log.e(TAG, "客户端属性发布失败", e);
        }
    }

    private void onTankConfigReloaded() {
        CloudAlarmReporter.getInstance().onTanksReloaded(currentEnabledTankIds());
        requestClientAttributesPublish();
    }

    /**
     * 设置变了之后重发客户端属性：罐设置写库完成（仓库层回调）、基础设置页保存、罐配置重新加载时调用。
     * 等 {@link #ATTRIBUTES_DEBOUNCE_MILLIS} 再发，期间再次调用只会推迟，不会多发。未连接时发送会失败，
     * 下次连上会带最新值重发。
     */
    public void requestClientAttributesPublish() {
        synchronized (attributesLock) {
            if (pendingAttributesPublish != null) {
                pendingAttributesPublish.cancel(false);
            }
            pendingAttributesPublish = reconnectExecutor.schedule(
                    () -> outboundEventExecutor.execute(guarded("客户端属性上报", this::publishClientAttributes)),
                    ATTRIBUTES_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
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
    private void applySharedAttributes(Long newPeriodMillis, String source) {
        if (newPeriodMillis == null) {
            return;
        }
        long oldPeriodMillis = snapshotTrigger.getPeriodMillis();
        if (oldPeriodMillis != newPeriodMillis) {
            // 每次重连都会拉一次，值没变不记
            CloudCmdLog.onSharedAttributeApplied("reportIntervalSec", oldPeriodMillis + "ms", newPeriodMillis + "ms", source);
        }
        snapshotTrigger.setPeriodMillis(newPeriodMillis);
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
            applySharedAttributes(SharedAttributesParser.parsePushedReportIntervalMillis(payloadJson), "push");
        }

        @Override
        public void onAttributesResponse(String requestId, String payloadJson) {
            applySharedAttributes(SharedAttributesParser.parseResponseReportIntervalMillis(payloadJson), "response");
        }
    }

    // ---- 遥测快照 ----

    private void buildAndPublishSnapshot() {
        if (!connection.isConnected()) {
            // 断线期间的普通快照直接丢弃、不补投（见 PendingAlarmStore 类注释），重连后下一次检查即上报最新状态。
            return;
        }
        OilcanDataManager dataManager = OilcanDataManager.getInstance(MyApplication.getInstance());
        Map<Integer, SnapshotTrigger.TankLevel> levels = currentLevels(dataManager.getData());
        long monotonicNow = SystemClock.elapsedRealtime();
        if (!snapshotTrigger.shouldPublish(monotonicNow, levels)) {
            return;
        }
        List<NetOilCan> tanks = dataManager.getNetOilcans();
        long now = System.currentTimeMillis();
        Map<String, Object> values = TelemetrySnapshotBuilder.buildValues(tanks, now, snapshotSeq.incrementAndGet());
        Map<String, Object> payload = TelemetrySnapshotBuilder.wrapWithTimestamp(values, now);
        if (connection.publishTelemetry(gson.toJson(payload))) {
            snapshotTrigger.onPublished(monotonicNow, levels);
        }
    }

    /** 每秒调用，只读液位，不走 {@code getNetOilcans()}（它每次都反序列化 SharedPreferences）。 */
    private static Map<Integer, SnapshotTrigger.TankLevel> currentLevels(SparseArray<OilCan> data) {
        Map<Integer, SnapshotTrigger.TankLevel> levels = new java.util.HashMap<>();
        for (int i = 0; i < data.size(); i++) {
            OilCan oilCan = data.valueAt(i);
            if (oilCan != null) {
                levels.put(oilCan.getOilCanPos(),
                        new SnapshotTrigger.TankLevel(oilCan.getOilLevel(), oilCan.getWaterLevel()));
            }
        }
        return levels;
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
        rpcDispatchExecutor.execute(() -> {
            try {
                rpcDispatcher.dispatch(mqttRequestId, payloadJson);
            } catch (RuntimeException e) {
                CloudCmdLog.onDispatchCrashed(mqttRequestId, e);
            }
        });
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
        if (!connection.publishRpcAck(mqttRequestId, gson.toJson(ack))) {
            CloudCmdLog.onAckPublishFailed(mqttRequestId, commandId, status, errorCode);
        }
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
        // 连接和补投之前必须先对齐身份：恢复备份/复制配置/改编号后，队列要落在当前编码名下
        bindIdentity(credentials.getDeviceId());
        if (!credentials.isValid() && notEmpty(credentials.getDeviceId())
                && credentialStore.hasUnreadableToken()) {
            // 编码在、token 密文在但解不开：配置是从别的硬件/备份带过来的。不拿这个编码自动重新领取，
            // 否则新硬件会冒用旧编码的身份；等现场在配网页重新保存（回显确认）后再继续。
            Log.w(TAG, "已保存的 token 无法解密，不自动领取，需在配网页重新确认编号: " + credentials.getDeviceId());
            setProvisionStatus(ProvisionState.TOKEN_UNREADABLE, null);
            return;
        }
        if (!credentials.isValid() && notEmpty(credentials.getDeviceId())) {
            credentials = provisionAccessToken(credentials.getDeviceId());
            if (credentials == null) {
                return; // provisionAccessToken 已决定退避重试或停下等重新保存
            }
        }
        boolean ok = connection.connect(credentials);
        if (ok) {
            credentialStore.markConnected(credentials.getDeviceId());
            reconnectBackoff.reset();
            drainPendingAlarms();
            outboundEventExecutor.execute(guarded("交班/库存补发", this::drainRecordOutbox));
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

    /**
     * 把待补投队列、ACTIVE 报警、命令去重记录切到 {@code deviceId} 名下。编码没变（同一设备重装、
     * 断网重连、重复保存）什么都不做；变了就换文件，旧身份的文件不删不发，也不并入新身份。
     */
    private void bindIdentity(String deviceId) {
        String id = IdentityScope.normalize(deviceId);
        synchronized (identityLock) {
            if (id.equals(boundDeviceId)) {
                return;
            }
            Log.w(TAG, "设备身份变更: " + (boundDeviceId.isEmpty() ? "(未配置)" : boundDeviceId) + " -> "
                    + (id.isEmpty() ? "(未配置)" : id) + "，待补投/报警状态切换到新身份名下，旧身份数据保留不发送");
            pendingAlarmStore.switchPersistence(new PendingAlarmFilePersistence(appContext, id));
            recordOutbox.switchPersistence(new RecordOutboxFilePersistence(appContext, id));
            commandStore.switchPersistence(new ProcessedCommandFilePersistence(appContext, id));
            CloudAlarmReporter.getInstance().rebindIdentity(id);
            boundDeviceId = id;
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
        NONE, IN_PROGRESS, SUCCESS, REJECTED, NETWORK_ERROR, NOT_CONFIGURED, TOKEN_UNREADABLE
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
