# ThingsBoard 接入：Android 业务逻辑详解与现状

**Android 依据代码**：分支 `feature/thingsboard-device-protocol`，commit `a7b48e4`（2026-09-19），**未合并**，对应 PR #38。
**云端依据代码**：`yeweiyi-cloud` `main@5ff243b`（仅作接口参照，不展开)。
**协议规范**：`yeweiyi-cloud/docs/protocol-v1.md`（草案，尚未正式冻结）。

> 第四版。云端部分压缩成一张速查表（方法/参数/topic/一句话作用），重点全部放在 **Android 这边每一条业务逻辑实际怎么跑、代码在哪、做到什么程度**。

---

## 1. 架构一句话

**两段通信，协议不一样，不要连成一条"MQTT 到底"：**

```
Android 设备 --MQTT/TLS-->  ThingsBoard CE  --REST + WebSocket-->  Go 后端  -->  PostgreSQL/Redis  -->  Web/小程序
```

- 设备到 ThingsBoard：本文档的主角，原生 MQTT，本文件全部内容围绕这一段。
- ThingsBoard 到 Go 后端：REST（大部分请求）+ WebSocket（`internal/tbclient.SubscribeTelemetry` 订阅每台设备的实时更新，报警才能不等轮询立刻推给操作员）。**这一段真实存在，只是不是第二条 MQTT**——`yeweiyi-cloud` 的 `go.mod` 里虽然有 `paho.mqtt.golang` 依赖，但生产后台（`cmd/server`/`internal/api`/`internal/control`/`internal/ingest`/`internal/tbclient`）没有一处 import 它；唯一的使用者是 `internal/devicesim`——那是模拟一台设备的测试/压测工具，不是云端后台本身。

四条数据流：①遥测（30秒快照）②即时事件（报警/出入库/命令结果）③属性（客户端↑/共享↓）④控制（RPC+ACK+异步结果）。下面的 Android 业务逻辑全部按这四条流组织。

---

## 2. 云端接口速查表（仅作参照）

| 接口 | 参数 | 关联 Topic / 数据源 | 一句话作用 |
|---|---|---|---|
| `POST /api/login` | username, password | — | 登录拿 JWT |
| `GET /api/devices/:device/telemetry/latest` | device(path) | `telemetry`: `tank{n}.*`/`tankCount`/`snapshotSeq` | 读设备最新遥测快照 |
| `GET /api/alarms` | device, 时间范围 | `telemetry`: `alarm` key | 查报警历史 |
| `GET /ws/alarms` | device, token(query) | 同上，WS 实时推送 | 报警实时弹窗 |
| `GET /api/oil-in-out` | device, 时间范围 | `telemetry`: `stockEvent` key | 查出入库历史 |
| `POST /api/devices/:device/commands` | method, args, reason | `rpc/request`→`rpc/response`(ACK) + `telemetry`: `cmdResult`(异步结果) | 发起远程控制命令 |
| `GET /api/devices/:device/commands` | device, 分页 | 业务库（不碰 MQTT） | 查命令状态 |
| `GET /api/audit` | device, 时间范围 | 业务库 | 查操作审计 |
| `GET /api/devices` | — | 业务库 | 列出当前用户绑定的设备 |
| `POST /api/devices/bind` | deviceName, pairingToken | 业务库 | 用户扫码绑定设备（跟设备联不联得上网无关，见 §5） |
| `POST /api/devices/unbind` | deviceName | 业务库 | 解绑 |
| `POST /api/devices/:device/pairing-secret` | device(path)，仅 platform_admin | 业务库 | 铸造一次性配对密钥（给二维码用） |

**没有端点读 client attribute**——Android 已经在发标定表，云端没有任何接口把它转发出来，这是云端一侧唯一值得记的缺口，后面不再展开。

---

## 3. Android 业务逻辑详解（重点）

### 3.1 遥测上报——30 秒快照

**业务场景**：设备每 30 秒把当前所有罐子的实时读数发一次，是云端"实时数据"页面的唯一数据源。

**完整链路**：
```
TelemetryScheduler（独立线程，scheduleWithFixedDelay 30s）
  → ThingsBoardManager.buildAndPublishSnapshot()
      → OilcanDataManager.getInstance(...).getNetOilcans()   // 业务数据从这里取，本身跟 ThingsBoard 无关
      → TelemetrySnapshotBuilder.buildValues(tanks, now, snapshotSeq++)
      → connection.publishTelemetry(json)                     // 断线直接丢弃，不补投
```

**每罐字段**（`TelemetrySnapshotBuilder.putTankFields`）：`oilType`/`oilLevelMm`/`waterLevelMm`/`tempC`/`oilVolumeL`/`waterVolumeL`/`oilWeightKg`/`nullVolumeL`/`oilRatioPct`/`waterRatioPct`/`probeConnected`，外加设备级 `tankCount`（取最大罐号，不是罐数）、`snapshotSeq`（单调递增，丢帧检测用）。

**业务规则**：
- 断线期间**直接丢弃当次快照**，不进补投队列——因为快照是"当前状态"，补发一条 30 秒前的旧值没有意义，等下一个周期自然会发最新的。
- 周期可以被云端下发的 `reportIntervalSec` 共享属性运行期覆盖（见 3.6），不需要重启设备。
- **不发 `tank{n}.alarmState`**：底层字段 `NetOilCan.setAlarmState()` 全代码库从未被真正调用过，宁可不发这个 key，也不能发一个恒定的假状态糊弄云端。

**现状**：✅ 完整实现，字段跟云端逐个核对一致。**唯一的业务缺口**是数据源本身的精度限制（`oilRatioPct` 等字段的小数精度、单位是否真的对应探针寄存器量纲——这两条不是代码问题，是硬件事实待确认，见第 4 节）。

---

### 3.2 报警上报——业务逻辑完整但只覆盖"产生"这一半

**业务场景**：油位/水位/温度异常时要立刻通知云端，不等 30 秒周期。

**完整链路（从阈值判断到上报)**：
```
AlarmDetection.oilHeightDection()/oilLowDection()/waterHeightDection()/tempHeightDection()
    // 每次探头轮询后，拿实时读数跟 OilCanSet 里配置的四档阈值比较
    → 阈值命中，构造 OilMessage(type, oilCanId, value=当前读数)
    → AlarmManager.addAlarmState(oilMessage)
        → AlarmCoordinator 的 MessageListener 回调（UI 层，MainActivity.initData() 里注册）
            → 更新首页告警条文案
            → SocketManager.getInstance(main).sendAlarm(GsonUtil.toJsonStr(state))   // 结构化 JSON，不是展示字符串
    → BackService.sendAlarms(msgInfo)
        → ThingsBoardManager.reportAlarmEvent(message, recovered=false)   // 硬编码 false
            → AlarmEventBuilder.build(...) 把 OilMessage 15 种类型码里的 7 种映射成云端封闭类型
            → 映射不到的类型（出入库/低温/网络故障/探头勘探等）直接跳过，不上报
            → 走 PendingAlarmStore 补投队列（断线会补发，跟遥测快照不同）
```

**业务规则**（这是真正的业务逻辑，不是协议格式问题）：
- **报警产生要发,报警恢复现在完全不发**——`recovered` 参数全代码库只有一处调用，恒为 `false`。`AlarmDetection.java` 里其实已经有"液位从超阈值变回正常"这个业务判断（`else` 分支里 `SoundLightAlarm[]`/`isLowAlarms[]` 这两个布尔数组做的边沿检测），但它现在**只用来关本地声光报警硬件**，没有被拿去调用 `alarmManager.addAlarmState(...)` 构造一条恢复事件。这意味着云端报警历史里，每条报警产生后永远显示"未恢复"，即使现场液位早就正常了。
- **`value` 字段现在是真实读数**（2026-09-17 修复前恒为 0，见第 4 节的历史修复记录），云端能看到触发报警那一刻的具体液位/水位/温度值。
- `severity`（CRITICAL/MAJOR）的判断规则是这轮改动自己定的——双阈值报警（高高/低低）判 CRITICAL，单阈值/水位/温度/探头断线判 MAJOR，没有跟产品/云端正式确认过。

**现状**：⚠️ 传输链路、幂等、离线补投都做完了；**业务上"报警恢复"这个功能完全没有**，需要重新设计 `AlarmDetection` 的状态机对接方式，且涉及真机验证（issue #34）。

---

### 3.3 出入库上报——两条业务路径已经合流

**业务场景**：油罐进油/出油，无论是系统自动判断出来的，还是人工事后补录的，都要让云端看得到。这是两条完全不同的业务触发路径，之前是分开的，现在统一到一条上报通道。

**路径一：自动检测**（`InOilManager`，`logic/inout` 状态机判定进出库事件后触发）：
```
InOilManager.handleEvent(event)
    → 取 pendingRecords 里的初始快照（如果服务重启导致丢失，用"现在"顶替真正的开始时间——已知的
       eventId 不稳定风险，见 StockEventMapper.defaultEventId 的类注释）
    → 组装 DetailInoutRecord
    → StockEventMapper.build(detailInoutRecord, isIn)   // source 字段不带，默认 AUTO
    → SocketManager.sendUnloadOil(GsonUtil.toJsonStr(...))
```

**路径二：人工补录**（`BuLuFragment`，断电/故障导致自动检测漏记后，操作员手动补一条）：
之前完全绕开上报通道，云端/小程序看不到任何补录记录。这次改动后复用**同一个** `StockEventMapper.build`，只是 `source` 字段带 `MANUAL`。

**两条路径最终走到同一个方法**：
```java
// InOilManager.java
String stockEventJson = GsonUtil.toJsonStr(StockEventMapper.build(detailInoutRecord, isIn));
SocketManager.getInstance(MyApplication.getInstance()).sendUnloadOil(stockEventJson);
```
```java
// BackService.java
private boolean sendUnloadOils(String msgInfo) {
    thingsBoardManager.reportRawEvent("stockEvent", msgInfo);
    return true;
}
```

**业务字段**：`tank`/`direction`(IN/OUT)/`oilType`/`begin*`/`end*`（液位、体积、水位、水量、重量、温度)/`netVolumeL`（罐子净变化,出库为负)/`duringDeliveryVolumeL`（卸油期间加油机仍发出的量,只有人工核对过才带这个字段)/`source`(AUTO/MANUAL)/`tsMs`。

**现状**：✅ 两条业务路径都已接入，字段结构跟云端对齐。

#### 3.3.1 `beginTcVolume`/`endTcVolume` 真实语义——已确认：不是"温度补偿体积"，是"空液量"

之前（含 `yeweiyi-cloud` 那边的猜测）都把 `TcVolume` 读成 Temperature Compensated Volume。真正站得住的证据是 `InOilManager`（**活的**、自动检测状态机）的赋值路径，`captureBeginSnapshot`/`handleEvent` 用 `oilCan.getSurPlus()` 填 `beginTcVolume`/`endTcVolume`，对应 `OilcanDataManager.java:439-440` 的公式注释：

```java
// surPlus 是剩余可加油体积（maxVolume - oilLitre - waterLitre，单位 L）
```

这条公式和这条赋值路径都在真实业务里跑，**语义按"空液量/剩余可加注体积"写进协议是站得住的**。

（`res/values/strings.xml` 里同样写着 `<string name="inoil_tcvolume">空液量</string>`，方向一致，可以当佐证——但这条字符串资源唯一的使用位置是 3.3.2 那套已经确认废弃、且这次已经清理掉的 UI，操作员从没在屏幕上见过它，权重只能算"辅助印证"，结论以 `OilcanDataManager` 的公式为准。）

**独立的疑点（不影响上面的语义结论，但一并记录）**：真机上拉到的 5 条历史记录里这两个字段恒等于 `maxVolume`（5000），跟同批 `beginVolume`/`endVolume` 恒为 0 一起看，疑似 `InOilManager` 捕获快照时读到的 `OilCan` 对象体积字段还没被探针数据刷新——值得后续单独排查，但排查的是"这条活路径有没有 bug"，跟"空液量该怎么理解"是两个问题。

**另一个独立发现：`getBeginTcVolume()`/`getEndTcVolume()` 这两个 getter，现在全代码库零调用点**——`赋值`是活的（`InOilManager` 会写），但`读取`不是：

```
$ grep -rn "getBeginTcVolume\|getEndTcVolume" app/src/main/java
app/src/main/java/com/sansen/yeweiyi/db/DetailInoutRecord.java:118:    public double getBeginTcVolume() {
app/src/main/java/com/sansen/yeweiyi/db/DetailInoutRecord.java:126:    public double getEndTcVolume() {
```
只剩方法定义本身，没有第三处调用。清理 3.3.2 那段死代码之前，这两个 getter 唯一的调用点就在那段死代码里（`detail_begin_tc_volume.setText(detailInoutRecord.getBeginTcVolume() + " L")` 之类），清理之后这两个 getter 彻底变成零调用。跟 `StockEventMapper` 类注释里"`beginTcVolume`/`endTcVolume` 刻意不进 V1 协议"的说法放在一起看：**这两个字段现在是纯粹的"只写不读"数据**——`InOilManager` 认真算出真实值、存进本地 SQLite，但没有任何界面会显示它，也不会被发送到云端，写进去的值实际上无人消费。

这跟 3.3.2 的"废弃功能"不是一回事，需要分开表述：**赋值路径（`InOilManager`）是活的、跑在真实业务里**，所以语义结论（"空液量"）依然可信；只是**消费端**（无论是本地显示还是上报云端）现在都不存在。写协议文档时如果要收这两个字段，要说清楚"设备侧算得出来、存得下来，但当前版本的 App 不会把它发给任何人"，不是"设备侧压根不知道怎么算"。

#### 3.3.2 `duringDeliveryVolumeL` 符号方向——已确认；产生它的功能已废弃，代码已清理

符号约定本身通过完整代码追踪确认无误：**操作员手动核对"边卸边卖"时，`duringDeliveryVolumeL` 恒为正数**（卸油期间被同时卖给别的客户的量），`saleVolume`（核对后）= 罐子净变化 + 这部分同时卖出的量 = 实际到货总量。跟云端 `netVolumeL + duringDeliveryVolumeL = deliveredVolumeL` 的假设完全一致。

**这个值原本靠一个"边卸边卖"人工核对对话框产生（`InOilFragment.onClick` 打开 `dialog_detail_inout.xml`）。查证后确认：这不是一条"接错线的 bug"，是已经废弃、不再使用的功能——`InOutAdapter.onBindViewHolder` 给列表行装的点击监听器本身就是空实现，只有长按才生效，而长按走的是完全不同的删除确认框（`initDialog`）：**

```java
// adapter/InOutAdapter.java:117-130，onBindViewHolder 里给列表行装监听器
viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        // 空方法体——点击这一行，什么都不会发生
    }
});
viewHolder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
    @Override
    public boolean onLongClick(View v) {
        mOnItemClickListener.onLongClick(position);   // 只有长按才会调回调，走删除确认框
        return false;
    }
});
```

云端如果拿"这个功能哪天会被修好"当前提去理解 `duringDeliveryVolumeL`，理解方向就错了——它不是"暂时坏了"，是"已经不用了"。`BuLuFragment`（人工补录出入库那条路径）也从来没有调用过 `setDuringDelivery`，进一步印证这不是遗漏，是这条业务路径本身已经不存在。

**已清理**：`InOilFragment.onClick(int position)` 原本 ~190 行的详情/核对/继续卸油/停止/保存逻辑，这次已经清空（方法体只保留空实现，满足 `InOutAdapter.OnItemClickListener` 接口签名，不能删掉整个方法——`onLongClick` 这个真正在用的入口需要同一个接口）；连带的三个 import（`StockEventMapper`/`SocketManager`/`GsonUtil`，只在这段死代码里用过）一并移除。改动已过 `assembleDebug` + 197 个单测验证。**`dialog_detail_inout.xml` 布局文件本身没有删**——资源删除需要单独的引用安全检查（见 CLAUDE.md 的资源删除注意事项），这次改动范围只清理 Java 逻辑，不动资源文件。

**结论**：`duringDeliveryVolumeL` 现在、以及在这个功能被重新设计并重新接入之前，**都不会出现在任何一条真实上报的 stockEvent 里**——`StockEventMapper` 里 `if (record.getDuringDelivery() != 0.0)` 这个 omitempty 判断永远是 false。这是设计/产品层面已经废弃的结果，不是代码缺陷，云端协议文档如果要处理这个字段，应该按"当前版本不会产生这个值"来对待，而不是"等一个 bug 修复"。

---

### 3.4 远程控制（RPC）——详细业务与逻辑

**业务场景**：操作员在云端/小程序点一个按钮（改报警阈值、启泵、消音……），设备要执行并汇报结果。这是七条业务逻辑里状态最复杂的一条，因为它是唯一"云端主动发起、设备被动响应"的方向（其余六条都是设备主动上报）。

#### 3.4.1 一条命令的完整生命周期

```
云端 POST /api/rpc/twoway/{deviceId}  ──ThingsBoard 转成 MQTT──▶  v1/devices/me/rpc/request/{requestId}
                                                                          │
                                                          ThingsBoardMqttConnection.messageArrived
                                                                          │ 提取 requestId（这是 MQTT 层的一次性 ID，
                                                                          │ 不是业务 commandId，见下面 3.4.2）
                                                                          ▼
                                                          onRpcRequest(requestId, payload)
                                                                          │ 挪到独立线程池（见 3.4.5），
                                                                          │ 不在 Paho 回调线程上跑
                                                                          ▼
                                                          RpcDispatcher.dispatch(requestId, payload)
```

`RpcDispatcher.dispatch` 内部按顺序做 8 件事,每一步都可能提前终止：

```java
public void dispatch(String mqttRequestId, String payload) {
    // ① 解析 —— 连 commandId 都拿不到的畸形请求，直接拒绝，不记录（没有 key 可以记）
    RpcRequestEnvelope request = RpcRequestEnvelope.parse(mqttRequestId, payload);

    // ② 幂等查重 —— 命中已有终态就原样回放，不重新执行 handler
    ProcessedCommandStore.Record existing = commandStore.find(request.getCommandId());
    if (existing != null && isTerminal(existing.status)) {
        ackPublisher.publishAck(mqttRequestId, request.getCommandId(), existing.status,
                existing.errorCode, existing.message, /*duplicate=*/true, now);
        return;
    }

    // ③ 协议版本校验 —— 版本号缺失/非法/不匹配，一律当"不支持"处理，不能默认放行
    if (!request.isProtocolVersionSupported()) { ...REJECTED, PROTOCOL_VERSION_UNSUPPORTED... }

    // ④ 有效期校验 —— 设备用自己的时钟比对 expiresAtMs，不看云端发的时候是否过期
    if (request.isExpired(now)) { ...EXPIRED... }

    // ⑤ 方法查找 —— 不在注册表里，UNKNOWN_METHOD
    RpcMethodHandler handler = registry.find(request.getMethod());
    if (handler == null) { ...REJECTED, UNKNOWN_METHOD... }

    // ⑥ 正式受理：先落盘 RECEIVED 再执行 —— 落盘失败就拒绝执行，见 3.4.4
    if (!commandStore.record(request.getCommandId(), RECEIVED, ...)) {
        ...REJECTED, INTERNAL_ERROR, "去重记录写盘失败，为避免重启后重复执行，本次命令未执行"...
        return;
    }
    ackPublisher.publishAck(mqttRequestId, request.getCommandId(), RECEIVED, NONE, "已受理", false, now);

    // ⑦ 真正执行业务逻辑
    RpcResult result;
    try {
        result = handler.handle(request);
    } catch (Exception e) {
        result = RpcResult.failed(INTERNAL_ERROR, "handler 执行异常: " + e.getMessage());
    }

    // ⑧ 终态落盘 + 异步上报（走 cmdResult telemetry，不是 ACK topic，见 3.4.3）
    commandStore.record(request.getCommandId(), result.getStatus(), result.getErrorCode(), result.getMessage(), finishedAt);
    resultPublisher.publishResult(request.getCommandId(), result.getStatus(), ..., finishedAt);
}
```

#### 3.4.2 一条命令上的两个 ID——本模块最容易搞混的地方

| | `mqttRequestId` | `commandId` |
|---|---|---|
| 谁生成 | ThingsBoard 平台（MQTT 层） | 云端 Go 后端，业务层 |
| 出现在哪 | topic 路径 `rpc/request/{这个}` 里 | 消息体 `params.commandId` 字段 |
| 生命周期 | 只活这一次请求-响应，用完即弃，不落盘 | 落盘进 `ProcessedCommandStore`，幂等去重靠它 |
| 断线重推会不会变 | 会——ThingsBoard 每次重试都换一个新的 | **不会**——同一次命令，重推 3 次也是同一个 |

`RpcDispatcher.dispatch(mqttRequestId, payload)` 两个参数都收，但幂等查重只用 `commandId`（`commandStore.find(request.getCommandId())`）——如果拿 `mqttRequestId` 去重，网络抖动导致的重试在设备眼里会变成两条互不相干的命令，幂等直接失效。

#### 3.4.3 ACK 和"命令执行结果"是两条完全独立的业务通道

| | ACK（`v1/devices/me/rpc/response/{mqttRequestId}`） | 执行结果（`telemetry` 里的 `cmdResult`） |
|---|---|---|
| 业务含义 | "命令收到了，格式对/不对" | "泵到底转没转" |
| 时机 | 同步，立刻返回 | 异步，可能要等很久（比如重启要等设备真的重启完） |
| 取值范围 | 只有 `received`/`rejected`（或重复投递时回放的历史终态，带 `duplicate=true`） | `executing`/`succeeded`/`failed` |
| 发布者 | `RpcAckPublisher` | `RpcResultPublisher`（复用 `PendingAlarmStore` 离线补投队列） |

**为什么必须拆成两条**：MQTT publish 成功只代表"消息发出去了"，不代表命令真的被执行了。如果 ACK 和执行结果混在一条通道上，"设备接受了这次请求"和"设备真的把泵启动了"在云端看来会分不清——这正是协议设计要解决的核心问题（`RpcDispatcher` 类注释原话："云端只能拿到这里发出的 ACK/CmdResult 作为设备执行结果"）。**协议版本不对、过期、方法不认识、受理阶段写盘失败**这几种"根本没跑到 handler 就已经确定"的情况，统一在 ACK 上报 `rejected`（内部去重表仍然按真实语义记录 `EXPIRED`/`FAILED`，只是对外的 ACK 状态收口成一个值）。

#### 3.4.4 幂等与断电恢复——两条业务规则

**幂等**：同一个 `commandId` 重复投递（网络问题导致云端重推），不会重复执行——直接回放上一次的结果。这条规则对 `setAlarmThresholds` 这种"改配置"类操作尤其重要：没有这条保护，云端超时重试一次可能把同一次改动应用两次；对将来会开放的泵阀类操作更危险——重复执行可能是真实的物理动作。

**受理阶段先落盘再执行,是有意为之的保守设计**：`RpcDispatcher` 在跑 `handler.handle()` 之前，先把 `RECEIVED` 状态落盘（`commandStore.record(...)`），**落盘失败就直接拒绝执行,不会"先干了再说"**。原因：这条落盘记录是"进程如果在执行期间被杀掉，重启后不会把同一条命令再执行一遍"这个保证的唯一载体。如果只在内存里记、不落盘，设备在 handler 执行期间掉电，重启后同一个 `commandId` 会被当成一条全新命令重新执行——对泵阀类动作这是真实的安全风险。宁可这次不执行、明确回一个 `FAILED`（云端可以换个 `commandId` 重新下发），也不能落进"执行了但说不清有没有做过"的糊涂账。

进程重启后的业务规则（`ProcessedCommandStore.recoverAfterRestart`，`ThingsBoardManager.start()` 里调一次）：任何停在 `RECEIVED`/`EXECUTING`（非终态）的记录，一律强制改判 `FAILED`——**没有办法确认它到底有没有执行完，只能保守判失败，不能假装成功，也不能悬而不决**。24 小时后自动清理旧记录，避免这张去重表无限增长。

#### 3.4.5 一个纯技术但影响业务可用性的细节：RPC 处理不能跑在 MQTT 回调线程上

项目里的 Paho（2013 年版）官方明确警示：不能在消息到达的回调里做阻塞的客户端操作。而 `RpcDispatcher.dispatch` 内部会同步发一次 `RECEIVED` ACK（`MqttClient.publish()` 阻塞等 PUBACK）——如果直接在回调线程上跑,有死锁风险,而且回调线程被卡住期间,**这条 MQTT 连接收不到任何新消息**,包括下一条报警、下一条 RPC 请求。业务后果是：一条命令处理慢，可能连带把报警上报也堵住。修复是把 `dispatch` 挪到独立单线程池（`rpcDispatchExecutor`，`ThingsBoardManager.onRpcRequest`），回调线程立刻返回去处理下一条消息；用单线程而不是线程池，是为了保留"同一时刻只有一条 RPC 在处理"这个 `ProcessedCommandStore` 去重记录原本依赖的隐含串行顺序。

#### 3.4.6 七个已注册方法的业务含义与现状

| 方法 | 业务动作 | 当前真实行为 |
|---|---|---|
| `config.setAlarmThresholds` | 改某个罐子的高高/高/低/低低四档报警阈值 | ✅ 真实执行：校验合法性（非负、四档必须严格递减）→ 同步写库 → 刷新 `OilcanDataManager` 缓存，让 `AlarmDetection` 立刻用上新阈值 |
| `pump.start`/`pump.stop` | 启/停指定油泵 | 恒拒绝（`FEATURE_DISABLED`），无真实业务逻辑 |
| `valve.open`/`valve.close` | 开/关指定阀门 | 同上 |
| `alarm.mute` | 现场消音，不改变报警状态本身 | 同上——但这是非物理动作，云端装机验收工具专门测它，优先级最高 |
| `alarm.clear` | 清除已恢复的报警 | 同上 |
| `device.reboot` | 远程重启设备 | 同上 |

`config.setTank`/`config.setProbe` 两个方法**没有注册**——不是漏了，是双方都没定参数形状，云端发了会收到 `UNKNOWN_METHOD`。

`config.setTank` 的候选字段有真实证据：`SetFragment` 的设置菜单第一项（`GridView` 的 `onItemClick`，真实接线，不是空实现）直接跳转 `OilCanSetFragment`，这是能在真机上截到图的活屏幕——罐号/传感器ID/传感器长度/储罐类型/介质类型/颜色/介质密度/液位水位温度三项修正/储罐高度/储罐容量，共 13+ 字段，见 3.4.6 表格上方。

`config.setProbe` 的候选字段**证据比预想的弱**：查 `SetFragment.java` 发现菜单第二项（`case 1`）本该跳转一个专门的"探针设置"（`ProbeSetFragment`），但**这一整块菜单项代码本身是被注释掉的**，而且顺着类名去找——**`ProbeSetFragment.java` 这个文件在整个代码库里根本不存在**（`find` 找不到，不是"存在但没接线"，是从来没被写出来过）。这跟 3.3.2 的死代码不是一回事：3.3.2 是类存在、UI 也画了，只是点击监听器接错线；这里是连类本身都没有。目前能找到的、跟"探针配置"沾边的字段，只有 `OilCanSetFragment` 那张活屏幕上的"传感器ID"/"传感器长度"两项——`config.setProbe` 如果要定参数形状，现有代码能提供的候选字段就这两个，其余（比如探针型号、通信参数）没有任何界面或数据表能作为依据。

**`setAlarmThresholds` 的业务校验规则**（唯一能看出"一条真实业务规则长什么样"的例子）：四个阈值都不能为负；且必须满足"高高 > 高 > 低 > 低低"——防止操作员或云端下发一套自相矛盾的阈值，导致 `AlarmDetection` 的判断逻辑出现死区或者永远触发。写库用同步方法（`updateOilCanSetSync`）而不是原来 fire-and-forget 的版本：这个 handler 的返回值会被 `RpcDispatcher` 当终态缓存，写盘真正失败但异步接口不报错的话，会记一条 `SUCCEEDED`，之后同一个 `commandId` 重投直接回放"成功"，云端和现场配置从此永久不一致，没有任何机会再纠正。写库成功后还必须调 `onThresholdsUpdated` 回调刷新 `OilcanDataManager` 缓存——`AlarmDetection` 读的是这份缓存不是数据库，不刷新的话新阈值要等下一次无关的刷新/重启才生效。

#### 3.4.7 完整错误码与触发场景

| 错误码 | 触发场景 |
|---|---|
| `UNKNOWN_METHOD` | 方法名不在注册表里（比如 `config.setTank`） |
| `PROTOCOL_VERSION_UNSUPPORTED` | `protocolVersion` 缺失、非数字，或跟设备当前版本不一致 |
| `MALFORMED_PARAMS` | 连 `commandId` 都解析不出来的畸形 JSON |
| `INVALID_PARAMS` | 参数能解析但业务上不合法（比如 `setAlarmThresholds` 阈值顺序不对） |
| `EXPIRED` | 设备收到时已经超过 `expiresAtMs`（设备用自己的时钟判断，不信云端发的时间） |
| `FEATURE_DISABLED` | 高风险动作默认关闭（issue #34 未完成前） |
| `INTERNAL_ERROR` | handler 执行抛异常，或去重记录写盘失败 |
| `INTERLOCK_BLOCKED`/`BUSY`/`NOT_SUPPORTED`/`HARDWARE_FAULT`/`LOCAL_MODE_LOCKED` | 预留，issue #34 联锁补齐前不会被真正触发 |

**现状**：⚠️ 状态机、幂等、断电恢复、ACK/结果分离、线程模型全部做完并有 34 个 JVM 测试覆盖；6/7 个方法没有真实业务逻辑，卡在 issue #34（联锁 + 真机验证）。

---

### 3.5 Client Attribute——目前唯一的业务内容是标定表

**业务场景**：把设备的静态配置（不是实时数据）同步给云端，方便远程查看/核对装机参数。

**当前实现**（`TankCalibrationAttributeBuilder`）：把每个启用罐位的"高度→体积"标定表发上去，连接成功后发一次。**业务上这是新增能力**——之前安卓端只在本地持久化这张表（`CreateOilTableFragment` 生成），云端和小程序完全没有数据源可查；现在发了，但云端还没有接口把它转发出去（见第 2 节）。

**触发时机的业务含义**：只在"连接成功后"触发一次，跟"标定表被修改"这个业务动作本身没有绑定——现场改完标定表，要等设备下次重连/重启，云端才能看到新值。装机场景下这个滞后可以接受（标定本来就是一次性动作）。

**现状**：⚠️ 已发，云端未接。协议文档要求的另外 7 个身份/配置类属性（`deviceModel`/`hardwareVersion`/`tankCount`/`localControlMode`/`probeAddress`/`probeType`/`capacityL`）都还没做——其中 `probeAddress`/`probeType`/`capacityL` 有现成数据源，是纯粹的搬运工作；`deviceModel`/`hardwareVersion` 没有任何数据源，需要先有人定义这两个值该是什么。

---

### 3.6 Shared Attribute——运行期调整快照周期

**业务场景**：云端管理员想把 30 秒的上报周期临时调成别的值（比如现场调试时调短，正常运行调长省流量），不需要重新刷固件或让操作员去设备上改配置。

**完整链路**：
```
每次连接成功 → ThingsBoardManager.requestSharedAttributes()   // 主动问一次"现在的值是多少"
    （只订阅推送 topic 收不到"连接前就已经设置好"的值，必须主动请求）
共享属性变化推送 或 请求的应答 → SharedAttributesParser 解析出 reportIntervalSec
    → TelemetryScheduler.updatePeriod(newPeriodMillis)   // 不需要重启设备就生效
```

**业务规则**：非法值（缺失/非数字/非正数）一律忽略，保持当前周期不变——避免一条畸形消息把周期改成 0 或负数。

**现状**：✅ `reportIntervalSec` 这一半已实现。**`alarmThresholds` 这个共享属性没做**——如果云端想通过这条通道（而不是 RPC）批量下发报警阈值，现在收不到，因为协议没定义具体字段形状，猜错了会把现场阈值覆盖成错误值。

---

### 3.7 配对联网——业务上最大的缺口

**业务场景**：一台新设备从出厂到现场能正常工作，中间需要两件事都完成：设备本身要能连上 ThingsBoard；用户要能在小程序里看到/管理这台设备。**这两件事是独立的业务流程，Android 端现在都没有对应的用户可操作界面。**

- **设备联网**：`ThingsBoardCredentialStore.save()`（把 ThingsBoard access token 写进本地存储的唯一入口）全代码库零调用点。没有任何配网界面、没有扫码获取 token 的流程。新设备开机后，`DeviceCredentials.isValid()` 永远是 `false`，MQTT 连接**从不会真正尝试建立**。
- **broker 地址**同样没有配置界面，硬编码兜底到一个旧 IP（`39.98.62.79:1883`，看着像 Alink 时代遗留的测试服务器，不是 ThingsBoard 的真实地址）。
- **用户绑定设备**（云端二维码配对那套业务流程，见第 2 节 `pairing-secret`/`bind`）：Android 端没有任何扫码页面或配对密钥输入界面。

**这三个缺口的业务后果**：即使前面 3.1-3.6 的所有业务逻辑代码都是对的，**现在这套代码在现场装一台新设备,连 MQTT 连接都建立不起来**——这是比任何一条具体业务逻辑都更优先的缺口。

#### 3.7.1 `DeviceCredentials` 应该怎么拿到——设计建议（不是已实现方案，需要产品/安全确认）

先说清楚现在为什么拿不到，再说应该怎么拿。

**现在为什么拿不到**：`DeviceCredentials{deviceId, accessToken}` 要真正存在，前提是云端已经用 `tbclient.EnsureDevice`（第 2 节表格里那条，不是业务库的同名方法）在 ThingsBoard 里建好了这台设备、并读出了它的 access token。**这一步现在只有 `cmd/tbdevicesim`/`cmd/loadtest`/`cmd/smoke` 三个开发者命令行工具能做,云端没有任何 REST 接口暴露这个能力**——这是线 A 缺失的根子,不是 Android 一侧的问题。所以完整方案要分两半：云端先补一个"给真实设备铸造 ThingsBoard 身份"的接口，Android 再补"怎么把这个结果灌进设备"的流程。

**第一步（云端要做的）**：参照 `mintPairingSecretHandler` 的权限模型（`platform_admin` 专属，一次性返回明文，之后只能查、不能再看原文），新增一个接口，内部调 `tbclient.EnsureDevice(deviceName, DefaultDeviceProfile)` + `GetDeviceCredentials`，返回 `{deviceId, accessToken}`。这个返回值比配对密钥（线 B）更敏感——它**直接就是**设备的 MQTT 身份，泄露的后果是任何人能伪造这台设备的遥测/报警/命令结果（正是 `bindDeviceHandler` 那段历史注释里描述的、已经被否掉一次的错误方案，见上一版文档"线 B"小节）。

**第二步（物理怎么把 token 送到设备上）——这是真正需要拍板的地方，列出候选方案，不替产品/安全负责人做决定**：

| 方案 | 做法 | 优缺点 |
|---|---|---|
| **A. 局域网推送**（跟现有架构最贴合） | 装机人员的电脑/平板（跟设备同一局域网）运行装机工具，登录云端拿到第一步的 `{deviceId, accessToken}`，再 POST 给设备本地已有的 AndServer HTTP 服务器（新增一个 handler，模式完全照抄现有 `LoginHandler`），设备收到后调 `ThingsBoardCredentialStore.save()` | 不需要新起一套通信机制，设备本来就在跑本地服务器；**必须给这个新端点加访问控制**（否则同一局域网任何设备都能篡改凭据）——可以复用现有登录密码机制，或者设备开机时屏幕显示一次性 PIN 码要求装机工具带上，两者都要产品/安全确认 |
| **B. 设置界面手动填** | 新增一个"ThingsBoard 配网"设置页，装机人员手抄或粘贴 `deviceId`/`accessToken` | 零新增基础设施，但 access token 是长随机字符串，手抄易错；要求装机人员随身能访问云端后台，现场可行性存疑 |
| **C. 设备生成二维码，装机工具扫描** | 设备本地生成含配对信息的二维码 | **不可行**——我确认过 `AndroidManifest.xml` 没有声明摄像头权限，代码里也没有 Camera/zxing 相关引用，这台设备大概率没有摄像头 |
| **D. 云端/小程序生成二维码，设备扫描** | 反过来，装机工具生成含 token 的二维码，设备摄像头读取 | 同样卡在没摄像头这一点，C/D 两个方案本质是同一个硬件前提不满足 |

**倾向 A**，因为工作量最小（复用现有本地服务器基础设施）、不依赖不存在的硬件（摄像头）。但**本地端点的认证方式是一个真实的安全决策，不是实现细节**——用什么身份证明"这次调用真的是装机人员、不是同一 WiFi 下的另一台设备"，需要产品/安全负责人拍板，我这里只列选项不替他们定。

**第三步（不管哪个方案都要遵守的安全原则，抄自线 B 已经踩过的坑）**：这个凭据必须跟"用户绑定设备"的配对密钥（线 B）保持独立，不能合并成一套——线 B 的历史正是"曾经把 ThingsBoard token 当配对凭据用，被否掉"的真实案例，线 A 的设计不能重蹈覆辙。另外，`SharePrefUtil` 目前明文存储 `TB_DEVICE_ID`/`TB_ACCESS_TOKEN`，如果这次一并做配网界面，值得同时评估要不要换成 Android Keystore 加密存储。

---

## 4. 全部 MQTT Topic 一览

设备到 ThingsBoard 全程只用 6 个 topic，`ThingsBoardTopics.java` 里的常量是唯一真源。连接参数：`cleanSession=true`，全部 QoS 1（`QOS_AT_LEAST_ONCE`）——协议本身靠 `commandId`/`eventId`/`snapshotSeq` 做幂等，QoS 1 允许的重复投递本来就无害，不需要 QoS 2。

| # | Topic | 方向 | 何时用 | Payload 形状（示例） | Android 代码 | 云端怎么消费 |
|---|---|---|---|---|---|---|
| 1 | `v1/devices/me/telemetry` | 设备→云,发布 | 30s 快照 + 报警/出入库/命令结果三类即时事件，共用这一个 topic，靠 `values` 里的 key 名区分 | `{"ts":1735689600000,"values":{"tank1.oilLevelMm":1234.5,...}}` / `{"ts":...,"values":{"alarm":{...}}}` / `{"ts":...,"values":{"stockEvent":{...}}}` / `{"ts":...,"values":{"cmdResult":{...}}}` | `ThingsBoardManager.buildAndPublishSnapshot`（快照）/ `reportAlarmEvent`（报警）/ `reportRawEvent`（出入库）/ `publishRpcResult`（命令结果） | ThingsBoard 存成 timeseries；Go 端 `tbclient.SubscribeTelemetry`（WS）实时收，`internal/ingest.HandleUpdate` 按 key 名分流：`alarm`/`cmdResult` 单独处理，其余（`tank{n}.*`/`tankCount`/`snapshotSeq`/`stockEvent`）routine key 不落业务库，只在被 REST 查询时读 |
| 2 | `v1/devices/me/attributes` | **双向**：设备发布 client attribute；设备也订阅这个 topic 收共享属性推送 | 发布标定表：`{"tank1.calibrationHeightMm":[100,200,...],"tank1.calibrationVolumeL":[50,120,...]}`；收到共享属性变化推送：`{"reportIntervalSec":60}` | 发布：`ThingsBoardMqttConnection.publishAttributes`（调用方 `TankCalibrationAttributeBuilder`）；订阅收：`AttributesListener.onSharedAttributesPush` → `SharedAttributesParser.parsePushedReportIntervalMillis` | client attribute 云端目前**没有任何接口读**（第 2 节已提过）；共享属性目前只能靠 ThingsBoard 控制台手工改，云端 Go 代码没有下发它的业务入口 |
| 3 | `v1/devices/me/attributes/request/{requestId}` | 设备→云,发布 | 每次连接成功后主动问一次"现在的共享属性值是多少"——只订阅 #2 收不到连接前已经设置好的值 | `{"sharedKeys":"reportIntervalSec"}` | `ThingsBoardManager.requestSharedAttributes` → `connection.publishAttributesRequest` | ThingsBoard 内置处理，不经过本项目 Go 代码 |
| 4 | `v1/devices/me/attributes/response/{requestId}` | 云→设备,设备订阅（通配符 `+`） | 对 #3 请求的应答 | `{"shared":{"reportIntervalSec":60},"client":{...}}` | `AttributesListener.onAttributesResponse` → `SharedAttributesParser.parseResponseReportIntervalMillis` | 同上 |
| 5 | `v1/devices/me/rpc/request/{requestId}` | 云→设备,设备订阅（通配符 `+`） | 云端发起一条控制命令 | `{"method":"config.setAlarmThresholds","params":{"commandId":"...","protocolVersion":1,"expiresAtMs":...,"issuedAtMs":...,"operator":"...","reason":"...","args":{...}}}` | `RpcRequestListener.onRpcRequest`（`ThingsBoardMqttConnection.messageArrived` 里按 topic 前缀路由过来） | `control.Issue` → `tbclient.SendCommand` → `POST /api/rpc/twoway/{deviceId}`；**ThingsBoard 在内部把这次 HTTP 调用转成这条 MQTT 消息，Go 代码从头到尾看不到这个 topic 字符串本身**——这正是"MQTT 上的 RPC 是 topic 配对模拟出来的"这句话在云端视角的印证 |
| 6 | `v1/devices/me/rpc/response/{requestId}` | 设备→云,发布 | 设备对 RPC 的 ACK 应答（只表示 received/rejected，不是执行结果，见 3.4.3） | `{"commandId":"...","status":"received","tsMs":...}` | `RpcAckPublisher.publishAck` | **作为 `POST /api/rpc/twoway` 那次调用的同步 HTTP 响应体，直接返回给发起调用的 Go 代码**——不是被单独订阅收到的，Go 端从来不会主动订阅这个 topic |

**注意 #5 和 #6 合起来就是完整的"RPC"**：一个云→设备的请求 + 一个设备→云的响应，两条独立的 pub/sub，中间靠 `{requestId}` 这段 topic 路径人为配对——MQTT broker 自己完全不知道这是"一次调用"。ThingsBoard 把这套机制包装成了一个看起来很普通的同步 HTTP 接口（`POST /api/rpc/twoway`），云端 Go 代码因此感知不到底层的 MQTT 细节，但设备侧（`ThingsBoardMqttConnection`/`ThingsBoardTopics`）是直接跟这两个 topic 打交道的。

---

## 5. Android 待实现业务逻辑清单

| 分类 | 具体内容 |
|---|---|
| **卡配网界面（比真机验证更优先）** | 设备凭据写入界面、broker 地址配置界面、用户扫码绑定界面 |
| **卡真机（issue #34）** | `pump.*`/`valve.*`/`alarm.mute`/`alarm.clear`/`device.reboot` 真实业务逻辑、报警恢复事件源接入 `AlarmDetection` |
| **卡产品/跨仓库决策** | `alarmThresholds` 共享属性字段形状（真机截图已给出完整候选字段，来自可达的 `OilCanSetFragment` 活屏幕，但要不要全收进去需要产品确认）、`config.setTank` 参数形状（同一张活屏幕，13+ 字段候选）、`config.setProbe` 参数形状（**证据不足**：对应的"探针设置"界面 `ProbeSetFragment` 在代码库里根本不存在，只能找到两个候选字段）、`localControlMode`/单位精度等协议文档 §12 老问题 |
| **✅ 已确认，可以直接写进协议（但注意消费端现状）** | `beginTcVolume`/`endTcVolume` = "空液量"（剩余可加注体积）——赋值路径（`InOilManager`）是活的，但 `getBeginTcVolume()`/`getEndTcVolume()` 这两个 getter 现在全代码库零调用，本地不显示、`StockEventMapper` 也刻意不发送，是"设备算得出、存得下，但没人读"的只写字段；`duringDeliveryVolumeL` 符号方向 = 罐净变化 + 期间同时卖出量。均见 3.3.1/3.3.2 |
| **✅ 已确认为废弃功能并清理（不是 bug，不需要修）** | 产生 `duringDeliveryVolumeL` 的"边卸边卖"人工核对对话框（`InOilFragment.onClick`）从未被任何手势触发过——`InOutAdapter.onBindViewHolder` 给列表行装的点击监听器本身就是空实现，长按走的是另一条删除确认框。已确认是废弃功能，这次已把 `InOilFragment.onClick` 里的死代码清空（保留空方法体满足接口签名），`duringDeliveryVolumeL` 因此不会出现在真实 stockEvent 里，见 3.3.2 |
| **纯技术缺口，可直接做** | `probeAddress`/`probeType`/`capacityL`/`tankCount`/`protocolVersion` 作为 client attribute 补发（数据源已存在，真机截图确认"储罐容量"字段就是 `capacityL` 的现成来源） |
| **完全没开始** | OTA 客户端、交班上行通道（产品已决定不做） |

---

## 6. 测试现状

`logic/thingsboard/` 包 116 个测试，全模块 197 个测试，0 失败。**零真机验证**——没连过真实 ThingsBoard broker，没连过真实探针/继电器硬件；第 3.7 节的配网缺口意味着，即使现在有真机，这套代码也没有路径能装上去连通。

---

## 7. 关键文件索引

```
logic/thingsboard/
  TelemetrySnapshotBuilder.java / AlarmEventBuilder.java / StockEventMapper.java   三条即时/周期业务事件的 payload 构造
  TankCalibrationAttributeBuilder.java / SharedAttributesParser.java              属性双向
  ThingsBoardTopics.java / ReconnectBackoff.java / PendingAlarmStore.java / BrokerTls.java   连接层基础设施
  DeviceCredentials.java / DeviceCredentialStore.java                            凭据值对象（save() 无调用点）
  rpc/  RpcRequestEnvelope / RpcDispatcher / ProcessedCommandStore / handler/SetAlarmThresholdHandler(真实现) / handler/HighRiskActionHandler(6个恒拒绝)

manager/
  ThingsBoardManager.java          总装，业务事件的统一入口
  ThingsBoardMqttConnection.java   Paho 封装 + topic 路由
  ThingsBoardCredentialStore.java  凭据存取实现（无生产调用点）
  AlarmDetection.java              报警产生/恢复的真正判断逻辑
  InOilManager.java                出入库事件产生

sharepre/manager/SharePreManager.java   broker 地址来源（硬编码兜底，无配置界面）
activity/AlarmCoordinator.java          报警 UI + 上报触发点
service/BackService.java                AIDL 外壳
```
