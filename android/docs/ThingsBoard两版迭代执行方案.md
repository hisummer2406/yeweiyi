# ThingsBoard 接入：两版迭代执行方案

**编写日期**：2026-10-04
**Android 依据**：`feature/thingsboard-device-protocol@be435b1`（已合入 `main@2a2e0ee`）
**云端依据**：`yeweiyi-cloud@26fb6b6`（协议代码与 `5ff243b` 相同，之后只有文档提交）
**协议规范**：`yeweiyi-cloud/docs/protocol-v1.md`（草案，未冻结）
**配套文档**：`docs/ThingsBoard接入现状与待办.md`（逐条业务逻辑详解）

---

## 0. 迭代划分

| 版本 | 范围 | 一句话目标 |
|---|---|---|
| **V1 数据上报** | 设备 → 云：遥测快照、报警（产生 + 恢复）、出入库、客户端属性 | 云端 API 能展示的每一项数据，Android 都如实上报，且离线不丢关键事件 |
| **V2 指令下发** | 云 → 设备：RPC 命令执行 + 结果回报 | 云端下发的命令在设备上真正执行，结果准确回报 |

V2 内部再按风险拆两步：**V2a** 非物理动作（改配置、消音、清除、重启），**V2b** 物理动作（泵、阀）。V2b 卡在 issue #34（联锁 + 真机验证），不应跟 V2a 绑在一起发布。

**拆分依据**：RPC 框架（分发、去重、断电恢复、ACK 与结果分离）已经做完并有测试覆盖，V2 的工作量主要在各个 handler 的业务实现，跟 V1 几乎不共享代码。唯一的依赖是：V2 的 `alarm.clear` 要用到 V1 新增的报警状态跟踪器（V1-T1）。

---

## 1. 云端"需要展示的数据"清单

云端仓库里没有前端，所以"需要展示"以 Go 后台对外的 REST/WS 接口为准。每个接口读的是哪些设备上报的数据：

| 云端接口 | 读的数据 | 返回给前端的字段 | Android 现状 |
|---|---|---|---|
| `GET /api/devices/:device/telemetry/latest` | 遥测快照 | `snapshotSeq`、`tanks[]`（每罐 12 个字段，**含 `alarmState`**） | 11/12 已发，**`alarmState` 缺** |
| `GET /api/alarms`、`GET /ws/alarms` | telemetry 的 `alarm` key | `alarmId`、`tank`、`type`、`severity`、`status`、`text`、`value`、`time` | 只发 `ACTIVE`，**`CLEARED` 缺**；`alarmId` 不符合协议要求（见 V1-T1） |
| `GET /api/oil-in-out` | telemetry 的 `stockEvent` key | 起止时间、液位、体积、温度、重量，`netVolumeL`、`duringDeliveryVolumeL`、`deliveredVolumeL`（云端计算） | ✅ 字段齐全 |
| `GET /api/devices/:device/commands` | ACK + `cmdResult` | 命令状态机 | ✅ 框架齐全（V2 范围） |
| （无接口） | 客户端属性 | — | 协议 §5.1 定义了 8 项，Android 一项没发；云端目前也没有接口读 |

**结论**：V1 必须做的是 `alarmState` 和报警恢复（这两项云端已经在读，缺了前端就显示不全）；客户端属性云端还没有消费方，但协议要求、数据现成，一并在 V1 做掉，避免 V2 时再回头补。

---

## 2. 现状总览

### 2.1 已经完成、V1/V2 都不用再动的

| 能力 | 代码位置 | 测试 |
|---|---|---|
| MQTT 连接、TLS（私有 CA、SNI、主机名校验）、指数退避重连（2s–60s + 抖动） | `ThingsBoardMqttConnection`、`BrokerTls`、`ReconnectBackoff` | `BrokerTlsTest` 14、`ReconnectBackoffTest` 6 |
| 30s 遥测调度、共享属性 `reportIntervalSec` 运行时生效 | `TelemetryScheduler`、`SharedAttributesParser` | 7 + 9 |
| 出入库事件（自动 + 人工补录） | `StockEventMapper`、`InOilManager`、`BuLuFragment` | 8 |
| 离线补发队列（报警/出入库/命令结果共用） | `PendingAlarmStore` | 6 |
| RPC 分发、去重、断电恢复、ACK/结果分离、独立线程 | `logic/thingsboard/rpc/` | 13 + 9 + 6 |
| `config.setAlarmThresholds` 真实执行 | `SetAlarmThresholdHandler` | 9 |
| 现场配网入口（仅中文界面） | `ThingsBoardSetFragment` | 无（依赖 Android） |

### 2.2 待补齐（按版本归属）

| 编号 | 内容 | 版本 | 是否需要跨仓库/产品确认 |
|---|---|---|---|
| V1-T1 | 报警状态跟踪器：报警恢复、按次生成 `alarmId`、`tank{n}.alarmState` | V1 | 部分（见 §3.1 决策点） |
| V1-T2 | 离线队列被探头断线报警挤占的风险 | V1 | 容量数字需产品确认，修复本身不需要 |
| V1-T3 | 客户端属性 | V1 | `deviceModel`/`hardwareVersion`/`localControlMode` 需要 |
| V1-T4 | 遥测数据精度（液位/水位百分比取整、温度取整） | V1 | 温度一项用户已决定暂缓 |
| V1-T5 | 出入库：快照取值疑点真机核实（`source` 已结论为不需要） | V1 | 否 |
| V1-T6 | 契约测试：改用云端同一批样例文件 | V1 | 否 |
| V1-T7 | 真机联调环境 + 验收 | V1 | 否 |
| V2-T1 | 错误码对齐 | V2a | 需要 |
| V2-T2 | `config.setAlarmThresholds` 参数结构写进协议 | V2a | 需要 |
| V2-T3 | `alarm.mute` | V2a | 否 |
| V2-T4 | `alarm.clear` | V2a | 语义需要产品定义 |
| V2-T5 | `device.reboot` | V2a | 否 |
| V2-T6 | `config.setTank` / `config.setProbe` | V2a | 参数结构需要 |
| V2-T7 | 共享属性 `alarmThresholds` | V2a | 需要（或决定不做） |
| V2-T8 | `pump.*` / `valve.*` | V2b | 联锁规则需要产品+现场确认 |
| — | OTA 客户端 | 单独立项 | — |

---

## 3. V1：数据上报

### 3.1 V1-T1 报警状态跟踪器（✅ 2026-10-04 代码完成，待真机验证）

**完成情况**：
- 纯 Java `logic/thingsboard/ActiveAlarmTracker`（ACTIVE 集合、去重、按次 alarmId、`alarmState` 汇总，`ActiveAlarmTrackerTest` 9 个用例）+ 文件持久化 `manager/ActiveAlarmFilePersistence` + 胶水层 `manager/CloudAlarmReporter`。
- 产生：`AlarmManager.addAlarmState` 一处；恢复：`AlarmDetection` 6 处"条件不再成立"分支 + `SerialPortManager` 通讯恢复处。本地判断/声光/继电器逻辑未改。
- `AlarmEventBuilder` 改为由调用方传入 alarmId（`类型-罐号-产生时 tsMs`）；`ThingsBoardManager.reportAlarmEvent` 换成 `reportAlarmTransition`；`AlarmCoordinator` 不再调 `SocketManager.sendAlarm`，`BackService.sendAlarms` 停用。
- 遥测快照开始发 `tank{n}.alarmState`。契约测试里 `alarmState`、`alarmId` 两个已知缺口已关闭，改为逐 key 比对。
- 删除 Android 自有的 `alarm_event_raised/recovered.json`（已由共享 fixture 覆盖）。
- 探头断线报警的 `text`：main 的 `3ac76d4` 曾把 `oil_connect_error` 当作不可达删除，导致 `text` 为空。已从 git 历史恢复中文、英文、泰文，西语为 AI 翻译（`values-es` 里已注明，待设计稿核对）。`NET_ERR_CONN` 复核后确认无人设置，其文案 `net_connect_err` 保持删除。

**要解决的三个问题**

1. **报警恢复从不上报**。`ThingsBoardManager.reportAlarmEvent(..., recovered)` 唯一的调用方 `BackService.sendAlarms` 硬编码 `false`。
2. **`alarmId` 不符合协议**。现在是 `类型-罐号`（`AlarmEventBuilder.defaultAlarmId`），同一个罐同一类报警每次发生都用同一个 ID。协议 §4.1 明确要求"同一次报警的 ACTIVE 与 CLEARED 用同一个 `alarmId`"，并指出"仅靠 (tank, type) 配对是不够的——断网补传时两次重叠的同类报警会配错"。现在没有 CLEARED，这个问题还没暴露；一旦加上恢复，就必须同时修。
3. **`tank{n}.alarmState` 不发**。`NetOilCan.alarmState` 从来没被赋值过。

**方案（2026-10-04 用户确认）：上报跟现有本地报警逻辑完全一致，不另做阈值判断**

在 `AlarmDetection` 现有的"产生"和"恢复"位置旁路上报，本地判断、声光、继电器行为一行不改：

```
产生：AlarmManager.addAlarmState(oilMessage)   （现有；AlarmDetection 6 处 + SerialPortManager 探头断线都经过这里）
        → CloudAlarmReporter.onRaised(oilMessage)
恢复：AlarmDetection.beginDection / oilHeightDection / oilLowDection 里"条件不再成立"的 else 分支，
      以及 SerialPortManager 通讯恢复（失败计数清零）处
        → CloudAlarmReporter.onRecovered(罐号, 类型, 当前读数)
CloudAlarmReporter（胶水层）→ ActiveAlarmTracker（纯 Java，JVM 单测）→ ThingsBoardManager 上报
```

- **产生**：跟本地完全一致。本地什么时候报（包括"高高液位没有声光但会报"），云端就什么时候收到 ACTIVE。同一罐同一类型已经 ACTIVE 时不重复发（探头断线每次轮询都会经过 `addAlarmState`，靠这一条去重）。
- **恢复**：跟本地"条件不再成立"是同一时刻：液位 ≤ 高高阈值、液位 ≤ 高阈值、液位 ≥ 低阈值、液位 ≥ 低低阈值、水位 ≤ 高水位阈值、温度 ≤ 高温阈值、探头通讯恢复。只有之前发过 ACTIVE 的才发 CLEARED。阈值被改成 0（不启用）时，本地也走同一个分支，云端随之 CLEARED。
- **高液位与高高液位可以同时 ACTIVE**：本地就是这样（先报高、再报高高，降下来各自恢复），不做"同一维度只报最严重一级"。低与低低同理。
- **不加防抖**：本地没有。
- **`alarmId`**：`{类型}-{罐号}-{产生时 tsMs}`，每次产生换一个；CLEARED 复用 ACTIVE 的 ID。
- **持久化**：当前 ACTIVE 集合写文件（写临时文件再改名）。进程重启后读回，读数恢复时仍能用原 ID 补发 CLEARED。
- **`alarmState` 汇总**：探头断线报警 ACTIVE → `FAULT`；其它任一 ACTIVE → `ALARM`；否则 `NORMAL`。
- **`text`**：沿用本地 `OilMessage.toString()`（跟随界面语言），CLEARED 复用 ACTIVE 时的文案。没有"报警恢复"的多语言文案资源，不自行编写。
- **停用旧上报路径**：`AlarmCoordinator` 不再调用 `SocketManager.sendAlarm`，避免重复上报。
- **演示模式**：`TEST_DATA` 打开时不上报，跟现状一致。
- **本地报警逻辑的现有缺陷会原样体现在云端**，见 §3.10，不在 V1 修。

### 3.2 V1-T2 离线补发队列被挤占（✅ 2026-10-04 完成）

**完成情况**：
- 第 1 条：V1-T1 的去重已经解决探头断线反复入队的根源。
- 第 2 条：容量按用户决定**暂时保持 200**，不调整。
- 第 3 条：`PendingAlarmStore` 新增可注入的淘汰策略 `EvictionPolicy`。**2026-10-05 按 review 修正**：只有报警"产生"是低优先级，丢一条"产生"时把同一 alarmId 的"恢复"一起丢；"恢复"从不单独被丢（它的"产生"可能已送达，丢了会让云端报警永远停在 ACTIVE）；没有"产生"可丢时才丢最旧的一条。`ThingsBoardManager` 传入 `AlarmEventBuilder.EVICTION_POLICY`。出入库和命令结果不会被报警挤掉。

**问题**：`PendingAlarmStore` 容量 200（`ThingsBoardManager.MAX_PENDING_ALARMS`），报警、出入库、命令结果三者共用，满了丢最旧。探头断线报警在 `SerialPortManager.java:517` 每次轮询失败都会入队，`AlarmManager` 每 3 秒取出一条交给上报。断网时这些都进补发队列，约 10 分钟占满 200 个位置，更早的出入库事件被挤掉、永久丢失。

**修复**：
1. V1-T1 完成后，探头断线也走跟踪器，只在状态翻转时产生一条事件，重复入队的根源就没了。
2. 容量调大。协议 §9 建议 10,000 条 / 30 天（标注待产品确认）。每条报警约 235 字节、出入库约 600 字节，10,000 条约 6 MB，对设备存储没有压力。**建议先调到 10,000**，产品有不同意见再改。
3. 可选：出入库事件优先级高于报警（报警是状态，补发的价值低于出入库这种记录），队列满时先丢报警。需要改 `PendingAlarmStore` 的淘汰策略，有现成单测可以扩展。

### 3.3 V1-T3 客户端属性（✅ 2026-10-04 完成）

**完成情况**：
- 纯 Java `logic/thingsboard/ClientAttributesBuilder`：发 `protocolVersion`、`tankCount`（最大罐号，跟快照同一语义）、每罐 `probeAddress`/`probeType`/`capacityL`。`deviceModel`/`hardwareVersion`/`localControlMode` 不发。
- `probeType` 发通讯协议名，字符串跟串口设置页一致：`ZHONGCHENG`/`MODBUS`/`4-20MA`（2026-10-04 用户确认，原为类名 `ZHONGCHENG`/`LD`/`LD2`）。跟云端样例的"磁致伸缩"（测量原理）不同，列入 §5 待确认。**来源更正（2026-10-04）**：`OilCan.getProbeType()` 返回的是 `Singleton.ChuanKou2`（串口设置页"串口 2"：0 中盛 / 1 标准 Modbus / 2 4-20mA），整机一个设置，可在设置页切换；此前"`setProbeType()` 无调用方、恒为 0"的说法是误读。
- 跟标定表合成一条 attributes 消息。触发：连接成功后 + `OilcanDataManager.refresh()`（罐配置重新加载）之后，经新增的 `setConfigChangedListener` 回调，不再只等下次重连。
- 契约测试改为逐 key 比对，无来源字段和 `probeType` 取值各用一个 `knownGap_*` 测试固定；新增 `ClientAttributesBuilderTest` 4 个用例。

协议 §5.1 的 8 项，按数据来源分类：

| key | 数据来源 | 状态 |
|---|---|---|
| `protocolVersion` | 常量 `1` | 直接做 |
| `tankCount` | 跟快照保持同一语义（见 §3.4 第 3 条） | 直接做 |
| `tank{n}.probeAddress` | `OilCan.getProbeAdr()` | 直接做 |
| `tank{n}.probeType` | `OilCan.getProbeType()`：0 = 中盛（`ZhongChengCompany`）、1 = `LDCompany`、2 = `LD2Company`，上报为字符串名称 | 直接做；字符串取值需跟云端约定 |
| `tank{n}.capacityL` | `OilCanSet.maxVolume`（升） | 直接做 |
| `deviceModel` | 候选：`Build.MODEL`（本机为 `rk3562_t`），或厂商 `XzjhSystemManager.xzjhGetSerialNo()` | **需要定义**：上报的是板卡型号还是产品型号 |
| `hardwareVersion` | 候选：`XzjhSystemManager.xzjhGetDeviceKernelInfo()` | **需要定义** |
| `localControlMode` | 设备上没有"现场手动模式"这个状态 | **不发**，等 V2b 定义联锁时再决定；不能编一个恒定的 `false` |

**触发时机**：现在标定表只在连接成功后发一次。改成"连接成功 + 配置变更"都发：在 `OilCanSetFragment` 的 4 处 `updateOilCanSet` 和标定表保存处调用 `ThingsBoardManager.publishClientAttributes()`。

**工作量**：约 1 天（`TankCalibrationAttributeBuilder` 扩展为通用的 `ClientAttributesBuilder` + 测试）。

### 3.4 V1-T4 遥测数据质量

1. **液位/水位百分比**（✅ 2026-10-04 完成）。核对后更正原方案：`OilCan.oilRatio` 是**按高度**算的整数（`(int)(液位÷罐高×100)`），只用来画本机罐体图，界面上不显示百分比数字；原方案写的"按 `oilVolume / maxVolume` 重算"会改变含义，作废。按用户决定：
   - `oilRatioPct` 沿用本地含义（液位÷罐高×100），保留一位小数、向下截断、超过 100 按 100；罐高未配置时不发。计算在 `TelemetrySnapshotBuilder.heightRatioPct()`（BigDecimal，避免浮点误差），由 `getNetOilcans()` 填入新字段 `NetOilCan.oilRatioPct`。不改 `OilCan` 和本机画图。
   - `waterRatioPct` **暂不上报**：真实模式下 `OilCan.waterRatio` 从未被计算（只有演示模式赋值），恒为 0。
   - 需要回复云端 §12.2：`oilRatioPct` 的含义是"按高度"，`waterRatioPct` 暂不提供。
2. **温度小数被截掉**：`ZhongChengCompany.java:215` 的 `Math.floor` 截掉了探针的真实小数。用户 2026-08-28 已决定暂缓修改，这里只登记，不排进 V1，除非重新决定。
3. **其余遥测字段核对结果（2026-10-04）**：液位、水位、体积、重量、空容量、油品、探头通讯状态都按本机同一数据上报，跟屏幕一致。两处值得注意：
   - `waterVolumeL` **真实模式下恒为 0**：跟 `waterRatio` 一样，三个探针解析器都不计算水体积，只有演示模式赋值；本机屏幕上的水体积同样显示 0。**结论（2026-10-04）**：本地在用（罐卡片可选显示项、出入库记录、VR350 对外协议），按"本地在用就照常上报"继续上报，不标 deprecated；恒为 0 是本地问题，不在 MQTT 范围内改。`waterLevelMm` 按本机逻辑照常上报（只有中盛协议解析水位）。
   - `oilVolumeL` 是按油面高度查标定表得到的**总液体体积（含底部的水）**，`oilWeightKg` = 它 × 密度；`nullVolumeL`（空容量）= 容量 − 这个总体积。跟本机显示一致，但含义上"油体积"包含了水，需要告知云端。
4. **`tankCount` 语义**：协议写"本次快照的油罐数"，Android 发的是最大罐号。云端按 `1..tankCount` 读取，所以 Android 的做法反而能读全；罐号不连续时，按"罐数"会漏掉后面的罐。**建议请云端把协议文档改成"最大罐号"**，Android 不改。

### 3.5 V1-T5 出入库

1. **`source` 字段——结论（2026-10-04 用户确认）：不需要，不推动云端添加。** 云端已经用 `direction`（`IN`/`OUT`）区分进油与出油，并据此计算 `deliveredVolumeL`；`source`（`AUTO` 自动检测 / `MANUAL` 人工补录）是另一个维度，云端协议没有、前端也不区分。补录事件跟自动检测事件以同样形状上报，云端照常收录。Android 现状不改：补录事件目前仍会带 `"source":"MANUAL"`，云端解析时直接忽略，无影响。
2. **快照取值疑点**：之前在真机上拉到的 5 条历史记录里，`beginVolume`/`endVolume` 都是 0、`TcVolume` 都等于 `maxVolume`，怀疑 `InOilManager` 记录开始快照时 `OilCan` 体积还没刷新。V1 联调时用两个真实探针做一次进油、一次出油，核对 `stockEvent` 里的体积和重量。main 刚合入的 `2a2e0ee` 修的是本地 `OilInOut` 表的重量字段，不影响 `stockEvent`，但同样要顺带核对。

### 3.6 V1-T6 契约测试共用云端样例（✅ 2026-10-04 完成）

协议 §10 要求 Android 直接用 `yeweiyi-cloud/fixtures/protocol/v1/` 的同一批文件做测试。现在 Android 用的是自己的副本（`app/src/test/resources/thingsboard/`），其中 `stock_event_in/out.json` 只是 `_comment` 不同、数据已同步，`telemetry_snapshot.json` 是 Android 自己的单罐样例、跟云端内容不同，且缺少 `alarm_cleared`、`client_attributes`、`cmd_result_*`、`rpc_ack_*`、`telemetry_snapshot_forward_compat`。

**做法**：把云端的 14 个样例拷进 `app/src/test/resources/protocol/v1/`（文件头注明来源 commit），对每个样例做"解码得到期望值 + 编码结果与样例逐 key 相等"的双向测试。以后协议有改动，两边同步拷一次即可。约 1 天。

**完成情况**：
- 14 个云端样例原样拷到 `app/src/test/resources/protocol/v1/`（来源 `yeweiyi-cloud@26fb6b6`，同步方法见该目录 `README.md`）。
- 新增 `ProtocolV1ContractTest`（13 个用例）：遥测、报警、ACK、命令结果做编码比对，RPC 请求做解码比对。出入库沿用 `StockEventMapperTest`，改为读共享副本，删掉了 `thingsboard/` 下的两份重复文件。
- 为了能在 JVM 上测，把 ACK 和命令结果的报文组装从 `ThingsBoardManager` 抽成纯 Java 的 `rpc/RpcWirePayloads`，行为不变。
- 已知缺口（`alarmState`、百分比精度、`alarmId` 按次生成、客户端属性）从逐 key 比对里排除，各用一个 `knownGap_*` 测试固定当前行为：缺口补上后测试会变红，提醒把字段加回比对。
- 遥测的 `forward_compat`、`rest` 两个样例不适用：Android 只发不收遥测，没有遥测解析器。

### 3.7 V1-T7 真机联调与验收

**环境**：
1. 在 Mac 上用 `yeweiyi-cloud/docker-compose.thingsboard.yml` 起 ThingsBoard CE（明文 MQTT 端口 1884）。
2. 用云端的 `cmd/tbdevicesim` 或 ThingsBoard 控制台建设备，拿到 `deviceId` 和 `accessToken`。按协议 §1，正式开通应走 `tbclient.EnsureDevice`，联调阶段可以手工建。
3. 构建时 `sansen.thingsboardUseTls=false`，安装到设备（`192.168.0.111`，Android 13，系统自动对时已开、时钟与 Mac 误差 1 秒内）。安装前先确认设备上现有包的签名，签名不同需要卸载重装，会清空本地配置。
4. 设备上"高级设置 → 云平台设置"（中文界面）填入 broker `Mac 的局域网 IP:1884` 和凭据。
5. 再起 Go 后台（`cmd/server`），用它的 REST 接口做验收，而不是只看 ThingsBoard 控制台——这样验证的是"云端能展示"，不只是"数据到了 ThingsBoard"。

**验收清单**（两个探针，罐 1、罐 2）：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 正常运行 2 分钟 | `/telemetry/latest` 返回两罐 12 个字段，`alarmState=NORMAL`，`snapshotSeq` 递增 |
| 2 | 把罐 1 高液位阈值调到低于当前液位 | `/api/alarms` 出现 `HIGH_LEVEL` ACTIVE；快照 `alarmState=ALARM` |
| 3 | 阈值调回 | 同一 `alarmId` 的 CLEARED；`alarmState=NORMAL` |
| 4 | 拔掉罐 2 探头 | `PROBE_DISCONNECTED` ACTIVE 只出现一次；`alarmState=FAULT`；`probeConnected=false` |
| 5 | 插回 | CLEARED |
| 6 | 报警期间重启 App | 不重复发 ACTIVE；若重启期间已恢复，补发 CLEARED |
| 7 | 断网 → 制造报警 + 一次出入库 → 恢复网络 | 报警和出入库都补发到，`ts` 是事件发生时间而不是补发时间 |
| 8 | 进油/出油各一次 | `/api/oil-in-out` 体积、重量、温度与本地记录一致 |
| 9 | ThingsBoard 控制台改 `reportIntervalSec=10` | 不重启即按 10 秒上报 |
| 10 | 修改罐设置 | 客户端属性立即更新 |

### 3.8 V1 收尾（✅ 2026-10-04 完成）

- 更新 `docs/ThingsBoard接入现状与待办.md` 与 `CLAUDE.md` 的相关段落。
- 更正 CLAUDE.md 里"云端 `cmd/smoke` 在这个版本上会失败"的说法：`cmd/smoke` 用的是云端自带的模拟设备（`internal/devicesim`，在 `cmd/smoke/main.go` 里无条件创建，没有指向真实设备的参数），从不连接真实 Android 设备。

### 3.9 V1 工作量与顺序

| 顺序 | 任务 | 估算 |
|---|---|---|
| 1 | V1-T6 契约测试（先做，后面的改动都有样例兜底） | 1 天 |
| 2 | V1-T1 报警状态跟踪器 | 3 天 |
| 3 | V1-T2 队列容量/淘汰策略 | 0.5 天 |
| 4 | V1-T3 客户端属性 | 1 天 |
| 5 | V1-T4 百分比精度 | 0.5 天 |
| 6 | V1-T7 环境搭建 + 真机验收 | 2 天 |
| | **合计** | **约 8 个工作日**（不含等待跨仓库确认） |

---

### 3.10 本地报警逻辑的已知缺陷（2026-10-04 登记，不在 V1 修，以后单独处理）

V1-T1 按"跟现有本地逻辑一致"上报，以下缺陷会原样体现在云端：

1. **开机时已处于报警区域的报警不会触发**（用户确认算缺陷，以后再修）。`Singleton` 里的报警标志位开机默认都是 `false`（"未就绪"），只有读数先回到正常范围才会被置为可以报警。所以：
   - 开机时液位在高液位与高高液位之间：高液位不报；
   - 开机时液位高于高高液位：高液位、高高液位都不报；
   - 低液位/低低液位、高水位、高温同理。

   直到读数先恢复正常、再次越线才会报。
2. **低低液位"重新可报警"设错了标志位**：`AlarmDetection.java:86`（V1-T1 加了上报调用后的行号，原第 75 行）的 `else if (!isLLLowAlarm[pos])` 分支里写的是 `isLowAlarms[pos] = true`，本该是 `isLLLowAlarm`。液位从低低以下直接回到低液位以上时，低低液位不会被重新置为可报警，下次再降到低低以下不报。（云端仍会正常收到这一次的 CLEARED，因为恢复按读数判断。）
3. **高高液位没有声光**：高高液位只打开防溢阀继电器、写报警记录，不调用 `openL_S()`；只有高液位有声光。是否为预期行为需要产品确认。
4. **探头断线判定不一致**：报警要连续失败 20 次（`SerialPortManager` 的 `case 20`），遥测 `probeConnected` 失败 1 次就是 `false`。

### 3.11 V1 代码 review（2026-10-05）

对工作区 V1 改动做了一次整体 review，共 11 条。

**已修复（第 1～4 条，真机验收前必须修）**：

| # | 问题 | 修复 |
|---|---|---|
| 1 | 补发队列满时可能丢掉"恢复"事件，云端报警永远停在 ACTIVE | 淘汰策略改为只丢"产生"，并连同同一 alarmId 的"恢复"成对丢；"恢复"从不单独丢（`AlarmEventBuilder.EVICTION_POLICY`） |
| 2 | "恢复"可能比队列里它自己的"产生"先到云端；产生/恢复来自不同线程，提交顺序可能颠倒 | 即时事件统一走 `ThingsBoardManager.publishEventInOrder`：队列不空时新事件排队尾按序补发；`CloudAlarmReporter` 在同一把锁里完成"状态判断 + 提交发送" |
| 3 | 罐被删除/停用后，它的 ACTIVE 报警永远不会恢复；罐号被沿用时新报警会被当成重复 | 启动时和每次罐配置重新加载后，`CloudAlarmReporter.onTanksReloaded` 把不在当前启用罐里的报警全部上报恢复并清除 |
| 4 | 液位为 NaN/无穷大时 `heightRatioPct` 抛异常，整轮快照（所有罐）发不出去 | 非有限值返回 null，不发该字段 |

**记录，暂不修（第 5～11 条，低）**：

| # | 问题 | 说明 |
|---|---|---|
| 5 | 探头断线在通讯恢复前后可能出现"产生→恢复"来回跳 | "产生"经主线程 Handler 投递、"恢复"在串口线程直接执行；主线程繁忙时，恢复之前排队的"产生"消息可能在恢复之后才处理。本地逻辑同样如此 |
| 6 | 演示模式期间开始的真实报警，退出演示后不会上报 | 演示模式下不报"产生"，但本地"只报一次"标志已被用掉。演示模式约 20 分钟，跟旧上报路径行为一致 |
| 7 | "恢复"事件沿用"产生"时的文案 | 有意选择（没有"报警恢复"多语言文案）；云端样例恢复文案是"…报警恢复"。要改需设计稿提供文案 |
| 8 | 队列满时每次入队都要把整个队列重新解析一遍 | 淘汰策略每次都解析 JSON；长时间断网、队列里没有报警时每次入队约 200 次解析。可改为入队时记下优先级和分组 |
| 9 | 每次轮询多次读取"是否演示模式" | `onRecovered` 每次先读设置，绝大多数调用其实不用做任何事；可改为先查有无 ACTIVE 再读设置 |
| 10 | 文件持久化代码重复三份 | `ActiveAlarmFilePersistence`、`PendingAlarmFilePersistence`、`ProcessedCommandFilePersistence` 几乎逐行相同，可抽成通用类 |
| 11 | 以后打开混淆会出问题 | 现在 `minifyEnabled false`；混淆规则只保留了 `db`/`bean`/`web.handler`，打开混淆后 `logic.thingsboard` 里靠 Gson 读写文件的类（`ActiveAlarmTracker.Entry`、命令去重记录）会出错 |

## 4. V2：指令下发

### 4.0 先回答：协议版本不匹配返回什么错误码

协议 §2（`protocol-v1.md` 第 41 行）："每条 RPC 命令信封里也带 `protocolVersion`，收端不匹配即拒绝（`INVALID_PARAMS`）"；§6.7 错误码表（第 366 行）：`INVALID_PARAMS` 的含义是"参数缺失或越界，**含协议版本不匹配**"。云端代码 `internal/protocol/rpc.go` 的 `ValidateEnvelope`（第 164–165 行）在发送前也用 `INVALID_PARAMS` 拒绝版本不符的命令。

Android 目前返回 `PROTOCOL_VERSION_UNSUPPORTED`。实际影响很小：云端发送前已经强制 `protocolVersion == 1`（`control.go:214` 写死 `protocol.Version`），现阶段设备不可能收到版本不符的命令。但到协议升 V2 时会碰到，属于 V2-T1 要一并对齐的内容。

### 4.1 V2-T1 错误码对齐

| Android 现有 | 协议对应 | 建议 |
|---|---|---|
| `PROTOCOL_VERSION_UNSUPPORTED` | `INVALID_PARAMS` | 改成 `INVALID_PARAMS`，`reason` 写明版本不符 |
| `MALFORMED_PARAMS` | `INVALID_PARAMS` | 同上 |
| `FEATURE_DISABLED` | 无 | 改成 `NOT_SUPPORTED`，或请云端新增。V2 做完后只剩泵阀会用到 |
| `INTERNAL_ERROR` | 无 | 请云端新增（handler 异常、去重记录写盘失败都需要这个码，没有合适的现成码） |
| `UNKNOWN_METHOD`、`EXPIRED`、`INTERLOCK_BLOCKED`、`BUSY`、`NOT_SUPPORTED`、`HARDWARE_FAULT`、`LOCAL_MODE_LOCKED` | 一致 | 不变 |

改动在 `RpcErrorCode` 和 `RpcDispatcher`，有现成测试覆盖，约 0.5 天。

### 4.2 V2-T2 `config.setAlarmThresholds` 参数结构

已经能真实执行，但参数结构只存在于 Android 代码里，协议文档没写，云端只是原样转发 `args`。现在 Android 读的是旧字段名：`oilCanId`、`hheightOilAlarm`、`heightOilAlarm`、`lowOilAlarm`、`llowOilAlarm`。

**建议**：跟云端约定一份写进协议，字段名跟协议其它地方统一：`tank`、`highHighLevelMm`、`highLevelMm`、`lowLevelMm`、`lowLowLevelMm`，另外补上协议有、目前 RPC 没覆盖的 `highWaterLevelMm`、`highTempC`（`OilCanSet` 里都有对应字段）。Android 过渡期同时接受新旧字段名。

### 4.3 V2-T3 `alarm.mute`（现场消音）

**现成能力**：主界面"接警"按钮 `AlarmCoordinator.btn_jieJing` 已经实现了本地消音：把各罐报警标志复位，然后 `SoundLightManager.CanleAlarm()` 关闭蜂鸣和警示灯（`XzjhSystemManager.xzjhSetGpioValue(1, 0)`）。这正好符合协议"现场消音，不改变报警状态"。

**做法**：把 `btn_jieJing` 的逻辑提取成一个方法（比如 `AlarmSilencer.silenceAll()`），按钮和新的 `AlarmMuteHandler` 共用；handler 切到主线程执行（`SoundManager` 和界面相关）；成功后 `cmdResult` 回 `succeeded`。注意：消音不影响 V1 跟踪器的 ACTIVE 状态，云端报警仍然显示"未恢复"。

**风险**：`btn_jieJing` 会改 `Singleton` 里的报警标志，可能让本地下一轮重新触发声光，需要真机确认按钮现在的表现，确保远程消音跟现场按按钮的效果完全一样。约 1 天。

### 4.4 V2-T4 `alarm.clear`（清除已恢复的报警）

**问题**：设备本身没有"已恢复但未清除"这个状态，本地报警记录也没有清除的概念。这个命令在设备上要做什么，需要产品先定义。

**两种可能的语义**：
- A. 清掉设备上残留的本地提示（告警条、声光），对应 V1 跟踪器里已经 CLEARED、但本地声光还没关的情况。
- B. 纯云端动作：设备只回 `succeeded`，实际清除在云端或 ThingsBoard 的报警实体上做。

**建议**：等产品定义之前先回 `NOT_SUPPORTED`，不要回假的成功。

### 4.5 V2-T5 `device.reboot`

**现成能力**：厂商系统接口 `XzjhSystemManager.xzjhRebootSys(boolean)`（`libs/xzjh_interface_20260204.jar`，本 App 已经在用同一个接口控制 GPIO）。另有 `utils/Dwin.reboot()` 走 shell `reboot`，没有调用方，不建议用。

**关键难点：重启会杀掉进程，跟现有的断电恢复逻辑冲突。** `ProcessedCommandStore` 进程重启后会把所有非终态记录判为 `FAILED`。如果按普通 handler 处理，重启前记的是 `RECEIVED`，重启后这条命令一定被报成失败。

**做法**：
1. handler 先把这条命令记为终态 `SUCCEEDED`（`reason`: "已发起重启"），并发出 `cmdResult`。
2. 等 `cmdResult` 真正送达（QoS 1 收到 PUBACK），或最多等 5 秒。
3. 调用 `xzjhRebootSys`。
4. 重复投递时按已有逻辑回放 `SUCCEEDED`，不会二次重启。

"已发起"不等于"重启成功"，云端可以根据设备重新上线（ThingsBoard 的 `active` 属性）来确认。这一点要在协议里写明。真机验证时注意确认 `xzjhRebootSys` 的 boolean 参数含义（可能是是否确认）。约 1 天。

### 4.6 V2-T6 `config.setTank` / `config.setProbe`

**参数结构两边都没定义**，必须先定。

- **`config.setTank`**：现成依据是 `OilCanSetFragment`（设置菜单第一项，真机可达）。候选字段：罐号、储罐类型、介质（油品）、颜色、密度、液位/水位/温度修正、储罐高度、储罐容量、传感器长度等，对应 `OilCanSet` 的字段。写库走 `OilTankRepository.updateOilCanSet`（同步版本，理由同 `SetAlarmThresholdHandler`），写完刷新 `OilcanDataManager` 缓存并重发客户端属性。
- **`config.setProbe`**：依据很弱。对应的"探针设置"界面 `ProbeSetFragment` 在代码库里根本不存在，能找到的候选字段只有 `OilCanSetFragment` 上的"传感器 ID"和"传感器长度"，以及 `OilCan.probeType`。**建议 V2 不做**，或者并入 `config.setTank`。

**风险**：改罐配置可能影响容积计算和报警判断，需要像 `SetAlarmThresholdHandler` 一样做完整的参数校验。setTank 约 2 天（不含确认参数的时间）。

### 4.7 V2-T7 共享属性 `alarmThresholds`

协议 §5.2 定义了这个 key，但没定义结构；云端代码也没有任何地方会下发它。功能上跟 `config.setAlarmThresholds` 重复，区别只是共享属性没有审计、没有确认。

**建议**：跟云端确认**删掉这个 key，统一走 RPC**。如果云端坚持保留，结构直接复用 V2-T2 定下的 RPC 参数结构。

### 4.8 V2b：`pump.*` / `valve.*`（物理动作，单独发布）

**现成硬件能力**（比之前文档描述的多）：
- 设备通过继电器模块（`AsyManager`/`AsyManager2` 两套）控制现场设备。
- `SoundLightManager.openQyb(id)` / `closeRelay(id)`：潜油泵继电器开/关（目前没有调用方）。
- `SoundLightManager.FYF(...)` → `distribution(...)`：高高液位时**自动**打开防溢阀继电器（`AlarmDetection.oilHeightDection` 里调用，已经在现场联动运行）。
- 具体接线、哪个模块接哪个串口，需要对照现场确认。

**V2b 要做的**：
1. **通道映射**：协议参数 `{pump: n, tank: n}` / `{valve: n}` 对应哪一路继电器、哪个模块，需要一张可配置的映射表，现在代码里是按罐号直接对应继电器序号。
2. **联锁**（issue #34）：比如低低液位或探头断线时禁止启泵，高高液位时禁止远程关防溢阀，同一路正在执行时回 `BUSY`。具体规则需要产品和现场确认。
3. **结果回报**：继电器模块的 `recive`/`error` 回调转成 `cmdResult` 的 `succeeded`/`failed`（`HARDWARE_FAULT`），`detail` 带继电器状态。
4. **现场手动模式**：如果要支持 `LOCAL_MODE_LOCKED`，需要先定义 `localControlMode` 这个状态从哪里来。
5. **真机验证**：必须接真实继电器验证，并且要注意跟现有"高高液位自动开防溢阀"逻辑的互相影响。

在 V2b 完成之前，`HighRiskActionHandler` 继续对这四个方法回拒绝。

### 4.9 V2 工作量与顺序

| 顺序 | 任务 | 估算 |
|---|---|---|
| 0 | 跨仓库对齐会：V2-T1/T2/T4/T6/T7 的结论 | — |
| 1 | V2-T1 错误码 | 0.5 天 |
| 2 | V2-T2 阈值参数新字段名 | 0.5 天 |
| 3 | V2-T3 `alarm.mute` | 1 天 |
| 4 | V2-T5 `device.reboot` | 1 天 |
| 5 | V2-T6 `config.setTank` | 2 天 |
| 6 | V2-T4 `alarm.clear`（按产品定义） | 0.5–1 天 |
| 7 | V2a 真机验收（用云端 `POST /api/devices/:device/commands` 下发，检查 `/commands` 状态和 `/audit`） | 1 天 |
| | **V2a 合计** | **约 7 个工作日** |
| — | V2b 泵阀 | 联锁规则确定后再估 |

---

## 5. 需要确认的事项汇总

| # | 事项 | 找谁 | 影响 |
|---|---|---|---|
| 1 | 报警同一维度只报最严重一级、连续 3 次轮询防抖（§3.1） | 产品 | V1-T1 |
| 2 | 离线队列容量（§3.2，建议 10,000） | 产品 | V1-T2 |
| 3 | `deviceModel`/`hardwareVersion` 的定义，`probeType` 的字符串取值（§3.3） | 云端 + 产品 | V1-T3 |
| 4 | 液位/水位百分比改为小数（§3.4，协议 §12.2） | 云端 | V1-T4 |
| 5 | `tankCount` 改为"最大罐号"（§3.4） | 云端 | 协议文档 |
| 6 | ~~出入库 `source` 字段（§3.5）~~ 已结论：不需要（2026-10-04） | — | — |
| 7 | 错误码：新增 `INTERNAL_ERROR`，`FEATURE_DISABLED` 怎么处理（§4.1） | 云端 | V2-T1 |
| 8 | `config.setAlarmThresholds` 参数结构（§4.2） | 云端 | V2-T2 |
| 9 | `alarm.clear` 的设备侧语义（§4.4） | 产品 | V2-T4 |
| 10 | `device.reboot` 的 `succeeded` 表示"已发起"（§4.5） | 云端 | V2-T5 |
| 11 | `config.setTank` 参数结构；`config.setProbe` 是否取消（§4.6） | 云端 + 产品 | V2-T6 |
| 12 | 共享属性 `alarmThresholds` 是否删除（§4.7） | 云端 | V2-T7 |
| 13 | 泵阀通道映射、联锁规则、现场手动模式（§4.8） | 产品 + 现场 | V2b |
| 14 | 云端协议文档的过时内容：§4.2 "Android 发的是 toString()"、§12.5 `TcVolume` 含义（已确认是"空容量"） | 云端 | 文档 |

第 1、2 项有建议值，不用等确认就可以开工；其余项目开工前要有结论。

---

## 6. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 报警判断跟本地屏幕不一致 | 跟踪器独立于 `AlarmDetection` | 判定条件逐条照搬，验收清单第 2–5 项专门核对 |
| 真机验证依赖 ThingsBoard 环境 | 目前没有可用的测试服务器 | V1-T7 第一天先把环境搭起来 |
| 安装新包可能清空设备配置 | debug/release 签名不同只能卸载重装 | 安装前先核对签名，必要时先备份 `shared_prefs` 和数据库 |
| 协议未冻结 | 云端可能还会改字段 | V1-T6 先做，用共享样例让两边改动都会在测试里暴露 |
| 远程重启/泵阀属于高风险动作 | 误操作后果严重 | 重启按 §4.5 的"先记终态再重启"；泵阀单独发布，联锁和真机验证完成前保持拒绝 |
| 云平台设置仅中文界面可见 | 英文/西语设备要先切中文才能配网 | 等设计稿文案后补多语言 |

---

## 7. 代码位置索引

| 用途 | 位置 |
|---|---|
| 报警检测（本地声光，不改） | `manager/AlarmDetection.java`、`manager/AlarmManager.java:146-158` |
| 本地消音按钮 | `activity/AlarmCoordinator.java` `btn_jieJing` |
| 声光、继电器 | `manager/SoundLightManager.java`、`AsyManager`/`AsyManager2` |
| 厂商系统接口（GPIO、重启、序列号） | `app/libs/xzjh_interface_20260204.jar` → `android.app.XzjhSystemManager` |
| 遥测数据来源 | `manager/OilcanDataManager.getNetOilcans()` |
| 探头断线报警 | `manager/SerialPortManager.java:505-517` |
| 罐配置写入 | `fragment/OilCanSetFragment.java`（4 处 `updateOilCanSet`）、`repository/OilTankRepository` |
| ThingsBoard 胶水层 | `manager/ThingsBoardManager.java` |
| 协议纯 Java 层 | `logic/thingsboard/`、`logic/thingsboard/rpc/` |
| 云端协议真源 | `yeweiyi-cloud/internal/protocol/`、`yeweiyi-cloud/fixtures/protocol/v1/` |
| 云端读数据的接口 | `yeweiyi-cloud/internal/api/{telemetry,alarms,stock,control}.go` |
