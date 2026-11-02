# ThingsBoard 接入：Android 业务逻辑详解与现状

**Android 依据代码**：分支 `feature/thingsboard-device-protocol`，commit `be435b1`（2026-10-04，已合入 `main@2a2e0ee`）**加上工作区尚未提交的 V1 改动**（V1-T1～T4、T6，见 `docs/ThingsBoard两版迭代执行方案.md`），**未合并到 main**，对应 PR #38。
**云端依据代码**：`yeweiyi-cloud` `main@26fb6b6`（协议代码与 `5ff243b` 相同；仅作接口参照，不展开)。
**协议规范**：`yeweiyi-cloud/docs/protocol-v1.md`（草案，尚未正式冻结）。

> 第六版。云端部分压缩成一张速查表（方法/参数/topic/一句话作用），重点全部放在 **Android 这边每一条业务逻辑实际怎么跑、代码在哪、做到什么程度**。
>
> 第六版变更（2026-10-04，V1 数据上报迭代）：§3.1 开始发 `alarmState`、`oilRatioPct` 改为一位小数、`waterRatioPct` 停发；§3.2 报警恢复已接通，上报时机跟本地报警逻辑完全一致；§3.3 `source` 结论为不需要；§3.5 客户端属性补齐有数据来源的部分；离线队列满时成对丢弃报警（"恢复"从不单独丢）；即时事件按产生顺序到达；删除/停用罐的报警自动恢复；契约测试改用云端共享样例。（含 2026-10-05 review 修复，见执行方案 §3.11）§5/§6/§7 同步更新。
>
> 第六版补充（2026-10-06）：即时事件外层 `ts` 去重，避免在 ThingsBoard 里互相覆盖（§3.2、§3.3）。
>
> 第六版补充（2026-10-07）：§3.7 配网改为手输编号 + ThingsBoard Provisioning 自动领 token。
>
> 第五版变更（2026-10-04）：§3.7 配网已有现场手动录入入口（`9225274`）。

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

**没有端点读 client attribute**——Android 已经在发设备属性和标定表（§3.5），云端没有任何接口把它转发出来，这是云端一侧值得记的缺口，后面不再展开。另外 `/api/oil-in-out` 的返回里没有起止水位/水体积（协议结构里有、Android 也在发），属于云端的取舍。

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

**每罐字段**（`TelemetrySnapshotBuilder.putTankFields`）：`oilType`/`oilLevelMm`/`waterLevelMm`/`tempC`/`oilVolumeL`/`waterVolumeL`/`oilWeightKg`/`nullVolumeL`/`oilRatioPct`/`alarmState`/`probeConnected`，外加设备级 `tankCount`（取最大罐号，不是罐数）、`snapshotSeq`（单调递增，丢帧检测用）。

**业务规则**：
- 断线期间**直接丢弃当次快照**，不进补投队列——因为快照是"当前状态"，补发一条 30 秒前的旧值没有意义，等下一个周期自然会发最新的。
- 周期可以被云端下发的 `reportIntervalSec` 共享属性运行期覆盖（见 3.6），不需要重启设备。
- **`alarmState`**（NORMAL/ALARM/FAULT）：跟报警事件同一个来源（`CloudAlarmReporter` 记录的当前报警集合，见 3.2）。探头断线报警在 → `FAULT`，其它报警在 → `ALARM`，否则 `NORMAL`。
- **`oilRatioPct`**：沿用本机含义——液位 ÷ 罐高 × 100（本机 `OilCan.oilRatio` 只用来画罐体图，界面不显示百分比数字），上报时保留一位小数、向下截断、超过 100 按 100，罐高未配置时不发。
- **`waterRatioPct` 不发**：真实模式下 `OilCan.waterRatio` 从未被计算（只有演示模式赋值），恒为 0。

**各字段数据质量（2026-10-04 核对）**：

| 字段 | 情况 |
|---|---|
| 液位、水位 | 探针读数截到 0.1 mm 再加修正值，跟屏幕一致 |
| 温度 | 只有整数位（`ZhongChengCompany.java:215` 的 `Math.floor`），已登记、暂缓修 |
| 油体积、油重量 | 按油面高度查标定表得到的**总液体体积（含底部的水）**，重量 = 它 × 密度，跟屏幕一致；含义需告知云端 |
| 空容量 | 容量 − 上面的总体积 |
| 水位 `waterLevelMm` | 按本机逻辑上报：中盛协议解析出真实水位；标准 Modbus、4-20mA 两个解析器不写水位，上报的是 `OilCan` 里的值（通常为 0），跟屏幕一致 |
| **水体积 `waterVolumeL`** | ⚠️ 真实模式下**恒为 0**（三个探针解析器都不计算，只有演示模式赋值）。本地在用（罐卡片可选显示项、出入库记录、VR350 对外协议、通讯录制），按"本地在用就照常上报"的规则**继续上报**（2026-10-04 用户确认）；恒为 0 是本地问题，不在 MQTT 范围内改 |
| `probeConnected` | 失败 1 次即 `false`，而探头断线报警要连续失败 20 次，两者不一致（已登记） |

**现状**：✅ 字段跟云端共享样例逐 key 核对（`ProtocolV1ContractTest`），已知差异只剩 `waterRatioPct` 不发。

---

### 3.2 报警上报——产生和恢复都已接通，时机跟本地报警逻辑完全一致

**业务场景**：油位/水位/温度异常、探头断线时要立刻通知云端，不等 30 秒周期；恢复时也要通知。

**完整链路**：
```
产生：AlarmDetection 6 种报警 / SerialPortManager 探头断线
        → AlarmManager.addAlarmState(oilMessage)       // 本地所有报警都经过这里（原有）
            → CloudAlarmReporter.onRaised(oilMessage)   // 新增的一行
恢复：AlarmDetection 里"条件不再成立"的 else 分支（跟本地标志位复位同一个判断）
      SerialPortManager 通讯恢复（失败计数清零）处
            → CloudAlarmReporter.onRecovered(罐号, 类型, 当前读数)
CloudAlarmReporter
    → ActiveAlarmTracker（纯 Java）：去重、按次生成 alarmId、持久化当前报警集合、汇总 alarmState
    → ThingsBoardManager.reportAlarmTransition(...)
        → AlarmEventBuilder.build(...)：OilMessage 类型码里的 7 种映射成云端封闭类型，其余跳过
        → ThingsBoardManager.publishEventInOrder：队列里还有更早的事件时排队尾按序补发，断线时进补投队列
CloudAlarmReporter.onTanksReloaded（启动时 + 罐配置重新加载后）
    → 已删除/停用罐的 ACTIVE 报警全部上报恢复并清除
```

**业务规则**：
- **上报时机跟本地完全一致，不另做阈值判断**（用户 2026-10-04 确认）。本地什么时候报，云端就什么时候收到 ACTIVE；本地判定条件不再成立，云端就收到 CLEARED。本地判断、声光、继电器逻辑没有改动。
- **只对发过 ACTIVE 的发 CLEARED**；同一罐同一类型已经 ACTIVE 时不重复发（探头断线每次轮询都会经过 `addAlarmState`，靠这一条去重）。
- **高液位和高高液位可以同时 ACTIVE**，降下来各自恢复，跟本地一样；不加防抖。
- **外层 `ts` 不重复**：批量恢复的报警原来取同一时刻，在 ThingsBoard 里互相覆盖；现由 `UniqueTimestampAllocator` 依次加 1 毫秒（跟出入库共用）。
- **`alarmId` = `类型-罐号-产生时 tsMs`**，每次报警换一个，CLEARED 复用。当前报警集合写在 `filesDir/thingsboard_active_alarms.json`，App 重启后读数恢复时仍能补发 CLEARED。
- **`text`** 用本地 `OilMessage.toString()`（跟随界面语言），CLEARED 复用 ACTIVE 时的文案。探头断线文案 `oil_connect_error` 曾被当作不可达删除，已恢复（中/英/泰取自 git 历史，西语为 AI 翻译待核对）。
- **`value`** 是真实读数：产生时是触发时的读数，恢复时是条件不再成立那一刻的读数；探头断线没有读数，填 0。
- `severity`（CRITICAL/MAJOR）：双阈值报警（高高/低低）判 CRITICAL，其余判 MAJOR，没有跟产品/云端正式确认过。
- **演示模式不上报**（`TEST_DATA` 打开时）。
- 旧路径 `AlarmCoordinator → SocketManager.sendAlarm → BackService.sendAlarms` 已停用，避免重复上报。

**本地报警逻辑的已知缺陷会原样体现在云端**（登记在执行方案 §3.10，以后单独修）：开机时读数已在报警区域不会触发报警；低低液位复位设错了标志位；高高液位没有声光；探头断线判定两处不一致。

**现状**：✅ 代码完成，有 JVM 单测（`ActiveAlarmTrackerTest`、契约测试）；⚠️ 未经真机验证。

---

### 3.3 出入库上报——两条业务路径已经合流

**业务场景**：油罐进油/出油，无论是系统自动判断出来的，还是人工事后补录的，都要让云端看得到。这是两条完全不同的业务触发路径，之前是分开的，现在统一到一条上报通道。

**路径一：自动检测**（`InOilManager`，`logic/inout` 状态机判定进出库事件后触发）：
```
InOilManager.handleEvent(event)
    → 取 pendingRecords 里的初始快照（如果服务重启导致丢失，用"现在"顶替真正的开始时间——已知的
       eventId 不稳定风险，见 StockEventMapper.defaultEventId 的类注释）
    → 组装 DetailInoutRecord（起止时间到秒）
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

**业务字段**：`tank`/`direction`(IN/OUT)/`oilType`/`begin*`/`end*`（液位、体积、水位、水量、重量、温度)/`netVolumeL`（罐子净变化,出库为负)/`duringDeliveryVolumeL`（卸油期间加油机仍发出的量,只有人工核对过才带这个字段)/`tsMs`。

**时间与去重**：
- 外层 `ts` = 事件生成时刻，经 `UniqueTimestampAllocator` 去重。原来取只到分钟的 `tsMs`，同一分钟结束的两笔在 ThingsBoard 里互相覆盖。
- 自动检测的起止时间精确到秒（`DateUtils.getYYYMMddHHmmssWithSeconds()`），`eventId` 不再因同一分钟开始而相同；人工补录仍只到分钟。
- Android 不会用同一 `eventId` 重发修正版，待回复云端 B-9。

**`source` 字段——结论：不需要**（2026-10-04 用户确认）。云端用 `direction` 区分进油/出油，并据此计算实际到货/发出量 `deliveredVolumeL`；`source`（`AUTO` 自动检测 / `MANUAL` 人工补录）是另一个维度，云端协议没有这个字段、前端也不区分，不推动云端添加。Android 现状不改：补录事件目前仍带 `"source":"MANUAL"`，云端解析时直接忽略，无影响。

**现状**：✅ 两条业务路径都已接入，字段结构跟云端共享样例逐 key 一致。待真机核实快照取值疑点（见 3.3.1 末尾）。

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
| `config.setTankSettings` | 改某个罐的报警阈值（不含低温）、修正值、密度、油品序号、容量，字段名同客户端属性 | ✅ 2026-10-08 新增：白名单 + 范围校验（全部通过才写，否则整条拒绝）→ 同步写库 → 刷新缓存 → 客户端属性自动回显。清单见 `docs/远程配置下发-字段清单.md`。**云端未对齐、未经真机验证** |
| `config.setDeviceSettings` | 改出入库高度/时间阈值 | ✅ 2026-10-08 新增：校验 → 写 SharedPreferences → 刷新 `Singleton` → 回显。**云端未对齐、未经真机验证** |
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

### 3.5 Client Attribute——设备配置 + 标定表

**业务场景**：把设备的静态配置（不是实时数据）同步给云端，方便远程查看/核对装机参数。

**当前实现**（一条 attributes 消息，四部分合并；后两部分 2026-10-08 新增，key 名和单位是本仓库自定，云端尚未对齐）：
- `ClientAttributesBuilder`（协议 §5.1）：`protocolVersion`（恒为 1）、`tankCount`（最大罐号，跟快照同一语义）、每罐 `probeAddress`（`OilCan.getProbeAdr()`）、`probeType`、`capacityL`（罐设置最大容量）。
- `TankCalibrationAttributeBuilder`：每个启用罐的"高度→体积"标定表（`tank{n}.calibrationHeightMm`/`calibrationVolumeL`）。之前只在本地持久化，现在发了，但云端没有接口转发（见第 2 节）。体积为 NaN/Infinity 的点连同高度一起去掉（`EllipseTable` 生成的罐表末点曾为 NaN，导致整条属性发不出去、保存罐配置时崩溃）。
- `TankSettingsAttributeBuilder`：罐设置页各项，每个启用罐 `tank{n}.` + `tankHeightMm`、`sensorLengthMm`、`tankShape`（储罐类型：0 = 卧罐，1 = 立罐，对应 `array.xml` 的 `guan_type`）、`oilType`（名称，同遥测，随界面语言变化）、`oilTypeIndex`（油品序号，云端读写以它为准）、`alarmHighHighLevelMm`/`alarmHighLevelMm`/`alarmLowLevelMm`/`alarmLowLowLevelMm`/`alarmHighWaterMm`（毫米）、`alarmHighTempC`/`alarmLowTempC`（℃）、`levelCorrectionMm`/`waterCorrectionMm`/`tempCorrectionC`、`density`（原样上报：设置页标 kg/m³，库里存 0.56 这类值，单位待对齐）。报警阈值 0 = 未启用。颜色、`densVar` 不报。
- `DeviceSettingsAttributeBuilder`：基础设置页出入库阈值 `inStockHeightThresholdMm`/`inStockTimeThresholdSec`/`outStockHeightThresholdMm`/`outStockTimeThresholdSec`（0 = 未配置）。

**`probeType`**：上报通讯协议名，字符串跟串口设置页一致：`ZHONGCHENG`（中盛探针协议）/`MODBUS`（标准 Modbus 协议）/`4-20MA`（4-20mA），不是云端样例里的"磁致伸缩"（测量原理），取值待跟云端约定。来源是 `OilCan.getProbeType()`——它**不读 `probeType` 字段，而是返回 `Singleton.ChuanKou2`**，即"高级设置 → 串口设置 → 串口 2"的选择：0 = 中盛探针协议（`ZhongChengCompany`）、1 = 标准 Modbus 协议（`LDCompany`）、2 = 4-20mA（`LD2Company`）。这是**整机一个设置**，同一台设备所有罐的 `probeType` 相同。（2026-10-04 更正：此前误以为 `setProbeType()` 无调用方即"恒为 0"。）

**不发的**：`deviceModel`/`hardwareVersion`（没人定义过取值；候选来源有 `Build.MODEL` = `rk3562_t`、厂商接口 `XzjhSystemManager.xzjhGetSerialNo()`/`xzjhGetDeviceKernelInfo()`）、`localControlMode`（设备上没有这个状态）。不编造值。

**触发时机**：连接成功后立即发；设置变化后经 `ThingsBoardManager.requestClientAttributesPublish()` 防抖 2 秒重发——罐设置写库完成（`OilTankRepository.setOilCanSetChangedListener`，覆盖设置页、下拉框、复制、报警开关、RPC 全部入口；写库是异步的，所以等写完再读）、基础设置页保存、罐配置重新加载（`OilcanDataManager.refresh()`）。标定表保存不触发，要等下次重连或设置变化。发布异常只记日志，不影响后续的共享属性请求，也不会让 app 崩溃。

**现状**：✅ 已发，云端未接。跟共享样例逐 key 比对，已知差异：上面三个不发的字段、`probeType` 取值。

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

### 3.7 配对联网——设备联网改为手输编号 + 自动领 token，用户绑定仍缺

**业务场景**：一台新设备从出厂到现场能正常工作，中间需要两件事都完成：设备本身要能连上 ThingsBoard；用户要能在小程序里看到/管理这台设备。这两件事是独立的业务流程。

- **设备联网——✅ 手输编号 + ThingsBoard Provisioning 自动领 token**（`95f93a3`；2026-10-07 真机实测（rk3562_t，Android 13）：编号 ZCT000001 领取成功，经 TLS 连上 `8.154.38.176:8883`，token 以 `enc:` 加密落盘；云端确认遥测每 30 秒更新、`provisionState = provisioned`）：高级设置 → 云平台设置（`fragment/ThingsBoardSetFragment`），装机人员填 broker 地址、端口和设备贴纸上的编号，访问令牌留空即自动领取，手填令牌是兜底。流程、失败处理、云端准备见 `docs/设备配网流程-编号与Provisioning.md`。
  - `accessToken` 用 Android Keystore AES/GCM 密钥加密后落盘；`deviceId` 只是 MQTT client ID，保持明文。
  - **入口暂时只在中文界面显示**（`SetSeniorFragment` 里用 `LanguageUtil.isChinese()` 判断）——新文案只加在 `values/strings.xml`，en/es 待设计稿。
  - 从没保存过时，`SharePreManager.getIpBean()` 兜底到 ThingsBoard broker `8.154.38.176:8883`（2026-10-07 起），但凭据为空，所以**新设备不进这个页面填编号就连不上**——这是预期行为，不是缺陷。
- **用户绑定设备**（云端二维码配对那套业务流程，见第 2 节 `pairing-secret`/`bind`）：❌ Android 端仍然没有任何扫码页面或配对密钥输入界面。
- **云端铸造设备身份**：改由 ThingsBoard 自带的 Provisioning 完成（Device Profile 开启、预注册策略、批量导入编号），❌ 云端这些准备还没做。

#### 3.7.1 `DeviceCredentials` 应该怎么拿到——设计建议（需要产品/安全确认）

> 已定方案（2026-10-07）：手输编号 + ThingsBoard Provisioning（见上文），本节 A–D 不再采用，保留作参考。

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

**第三步（不管哪个方案都要遵守的安全原则，抄自线 B 已经踩过的坑）**：这个凭据必须跟"用户绑定设备"的配对密钥（线 B）保持独立，不能合并成一套——线 B 的历史正是"曾经把 ThingsBoard token 当配对凭据用，被否掉"的真实案例，线 A 的设计不能重蹈覆辙。（`TB_ACCESS_TOKEN` 明文存储的问题已在 `9225274` 改为 Android Keystore 加密存储。）

---

## 4. 全部 MQTT Topic 一览

设备到 ThingsBoard 全程只用 6 个 topic，`ThingsBoardTopics.java` 里的常量是唯一真源。连接参数：`cleanSession=true`，全部 QoS 1（`QOS_AT_LEAST_ONCE`）——协议本身靠 `commandId`/`eventId`/`snapshotSeq` 做幂等，QoS 1 允许的重复投递本来就无害，不需要 QoS 2。

| # | Topic | 方向 | 何时用 | Payload 形状（示例） | Android 代码 | 云端怎么消费 |
|---|---|---|---|---|---|---|
| 1 | `v1/devices/me/telemetry` | 设备→云,发布 | 30s 快照 + 报警/出入库/命令结果三类即时事件，共用这一个 topic，靠 `values` 里的 key 名区分 | `{"ts":1735689600000,"values":{"tank1.oilLevelMm":1234.5,...}}` / `{"ts":...,"values":{"alarm":{...}}}` / `{"ts":...,"values":{"stockEvent":{...}}}` / `{"ts":...,"values":{"cmdResult":{...}}}` | `ThingsBoardManager.buildAndPublishSnapshot`（快照）/ `reportAlarmTransition`（报警产生/恢复）/ `reportRawEvent`（出入库）/ `publishRpcResult`（命令结果） | ThingsBoard 存成 timeseries；Go 端 `tbclient.SubscribeTelemetry`（WS）实时收，`internal/ingest.HandleUpdate` 按 key 名分流：`alarm`/`cmdResult` 单独处理，其余（`tank{n}.*`/`tankCount`/`snapshotSeq`/`stockEvent`）routine key 不落业务库，只在被 REST 查询时读 |
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
| **卡配网界面** | 用户扫码绑定界面；云端 Provisioning 准备（见 3.7）；云平台设置页多语言文案（目前只在中文界面开放）。设备端手输编号 + 自动领 token 已实现并真机验证成功路径（`ThingsBoardSetFragment`） |
| **待决定** | `probeType` 跟云端的取值约定（Android 已按设置页命名）、`deviceModel`/`hardwareVersion` 取值定义（见 3.5）；"油体积含水"的含义需告知云端 |
| **本地问题，只记录不在 MQTT 范围内改** | 水体积 `waterVolume` 真实模式下恒为 0（三个解析器都不计算），影响罐卡片"水体积"显示项、出入库记录起止水体积、VR350 对外协议、MQTT 上报——按"本地在用就照常上报"的规则，MQTT 继续上报（见 3.1） |
| **V1 剩余（真机）** | 搭 ThingsBoard 环境做真机验收（执行方案 V1-T7）：报警产生/恢复、`alarmState`、断网补发、出入库快照取值疑点 |
| **卡真机（issue #34）** | `pump.*`/`valve.*`/`alarm.mute`/`alarm.clear`/`device.reboot` 真实业务逻辑（V2） |
| **卡产品/跨仓库决策** | `alarmThresholds` 共享属性字段形状（真机截图已给出完整候选字段，来自可达的 `OilCanSetFragment` 活屏幕，但要不要全收进去需要产品确认）、`config.setTank` 参数形状（同一张活屏幕，13+ 字段候选）、`config.setProbe` 参数形状（**证据不足**：对应的"探针设置"界面 `ProbeSetFragment` 在代码库里根本不存在，只能找到两个候选字段）、`localControlMode`/单位精度等协议文档 §12 老问题 |
| **✅ 已确认，可以直接写进协议（但注意消费端现状）** | `beginTcVolume`/`endTcVolume` = "空液量"（剩余可加注体积）——赋值路径（`InOilManager`）是活的，但 `getBeginTcVolume()`/`getEndTcVolume()` 这两个 getter 现在全代码库零调用，本地不显示、`StockEventMapper` 也刻意不发送，是"设备算得出、存得下，但没人读"的只写字段；`duringDeliveryVolumeL` 符号方向 = 罐净变化 + 期间同时卖出量。均见 3.3.1/3.3.2 |
| **✅ 已确认为废弃功能并清理（不是 bug，不需要修）** | 产生 `duringDeliveryVolumeL` 的"边卸边卖"人工核对对话框（`InOilFragment.onClick`）从未被任何手势触发过——`InOutAdapter.onBindViewHolder` 给列表行装的点击监听器本身就是空实现，长按走的是另一条删除确认框。已确认是废弃功能，这次已把 `InOilFragment.onClick` 里的死代码清空（保留空方法体满足接口签名），`duringDeliveryVolumeL` 因此不会出现在真实 stockEvent 里，见 3.3.2 |
| **review 遗留（低，暂不修）** | 探头断线通讯恢复前后可能来回跳、演示模式期间开始的真实报警退出后不上报、恢复事件沿用产生文案、补发队列淘汰时重复解析 JSON、轮询时多次读演示模式设置、三份文件持久化代码重复、打开混淆后 Gson 持久化类会出错——详见执行方案 §3.11 |
| **✅ 已结论为不需要** | 出入库 `source` 字段（云端用 `direction` 区分进出，不需要区分自动/补录，见 3.3） |
| **完全没开始** | OTA 客户端、交班上行通道（产品已决定不做） |

---

## 6. 测试现状

全模块 284 个 JVM 测试，0 失败（2026-10-07）。其中 `ProtocolV1ContractTest` 用云端 `fixtures/protocol/v1/` 的原样副本（`app/src/test/resources/protocol/v1/`，同步方法见该目录 README）对遥测、报警、设备属性、RPC ACK/命令结果做逐 key 比对，已知差异各用一个 `knownGap_*` 测试固定。**真机验证只覆盖配网与连接**（2026-10-07，见 3.7）：领取 token、TLS 连接、凭据加密落盘、遥测快照写入云端已确认；报警上报、RPC、断线补投等其余链路仍未在真机上验证，也没连过真实探针/继电器硬件。

---

## 7. 关键文件索引

```
logic/thingsboard/
  TelemetrySnapshotBuilder.java / AlarmEventBuilder.java / StockEventMapper.java   三条即时/周期业务事件的 payload 构造
  ActiveAlarmTracker.java                                                        当前报警集合：去重、按次 alarmId、alarmState 汇总
  ClientAttributesBuilder.java / TankCalibrationAttributeBuilder.java / SharedAttributesParser.java   属性双向
  ThingsBoardTopics.java / ReconnectBackoff.java / PendingAlarmStore.java / BrokerTls.java   连接层基础设施（补发队列满时成对丢弃报警，"恢复"不单独丢）
  UniqueTimestampAllocator.java                                                  即时事件外层 ts 去重
  DeviceCredentials.java / DeviceCredentialStore.java                            凭据值对象 / 存取接口
  rpc/  RpcRequestEnvelope / RpcDispatcher / RpcWirePayloads(ACK/cmdResult 报文) / ProcessedCommandStore / handler/SetAlarmThresholdHandler(真实现) / handler/HighRiskActionHandler(7个恒拒绝)

manager/
  ThingsBoardManager.java          总装，业务事件的统一入口
  ThingsBoardMqttConnection.java   Paho 封装 + topic 路由
  ThingsBoardCredentialStore.java  凭据存取实现（accessToken Keystore 加密；调用方 ThingsBoardSetFragment）
  CloudAlarmReporter.java          本地报警 → 云端报警的胶水层（产生/恢复）
  ActiveAlarmFilePersistence.java  当前报警集合落盘
  AlarmDetection.java              本地报警判断（未改动逻辑，只在恢复分支加了上报调用）
  AlarmManager.java                addAlarmState：报警产生的上报点
  InOilManager.java                出入库事件产生

fragment/ThingsBoardSetFragment.java   云平台设置页（broker 地址/端口/deviceId/accessToken 现场录入，仅中文界面显示）
fragment/SetSeniorFragment.java        高级设置，云平台设置入口所在页
sharepre/manager/SharePreManager.java   broker 地址存取（未配置时兜底 8.154.38.176:8883）
activity/AlarmCoordinator.java          报警 UI（不再负责上报）
service/BackService.java                AIDL 外壳（sendAlarm 已停用）
app/src/test/resources/protocol/v1/     云端共享 fixture 副本 + 同步说明
```
