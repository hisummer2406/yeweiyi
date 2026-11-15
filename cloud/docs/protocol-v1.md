# 设备协议 V1（ThingsBoard CE 单栈）

**状态：草案，待冻结。** 本文是 GitHub issue #7 的交付物。协议内容已由 `internal/protocol` 实现、
由 `fixtures/protocol/v1/` 的契约测试固定，并已在真实 ThingsBoard CE 4.2.1.1 上跑通端到端验证
（见 `internal/tbclient/e2e_test.go`）。但按 #7 的验收口径，**协议须由云端与 Android 负责人共同确认后
才算冻结**，冻结之前不要开始设备侧生产代码迁移。第 12 节列出了仍需对方确认的具体条目。

本协议是 Android 设备、ThingsBoard CE 与 Go 后台之间**唯一**的设备协议。项目不保留 Alink Topic、
不保留 EMQX Topic、不建设双协议兼容层。仓库中仍然存在的 `internal/mqtt`（旧 Alink/Paho 栈）由
issue #10 负责删除，不属于本协议的一部分。

---

## 1. 设备标识、凭据与传输

| 项目 | 规则 |
|---|---|
| 设备标识 | ThingsBoard 设备名 = 本项目 `devices.device_name`，同一个字符串。**两侧无任何机制强制其一致**，只能由统一的开通流程保证（见下）。 |
| 凭据类型 | ThingsBoard `ACCESS_TOKEN`，一机一密，由 ThingsBoard 签发。 |
| MQTT 认证 | access token 放在 MQTT **username** 字段，password 留空。这是 ThingsBoard 自身的约定。 |
| 生产传输 | `ssl://<host>:8883`（MQTT over TLS）。 |
| 开发传输 | `tcp://localhost:1884`（`docker-compose.thingsboard.yml`，明文；1884 而非 1883 是为了避开旧栈的 mosquitto）。 |
| 吊销 | 删除或重签 ThingsBoard 设备凭据即刻生效，无需业务库参与。 |

**设备身份不出现在任何 Topic 或报文里。** ThingsBoard 由 MQTT 会话的凭据判定是哪台设备，因此协议中
没有 `deviceName` 字段，也没有 `productKey`。这与旧 Alink 设计相反——旧设计把设备名写在 Topic 里，
任何拿到通配符发布权限的客户端都能伪造他人身份。

### 开通流程与两套设备表

ThingsBoard 有自己的设备表，本项目有自己的 `devices` / `user_devices` 表。二者仅靠 `deviceName`
字符串对齐，**代码里没有任何约束保证它们一致**。因此设备开通必须统一走
`tbclient.EnsureDevice`，不要在 ThingsBoard 控制台手工建设备。QR 码扫码绑定流程调用它，因为设备
可能在第一次联网之前就被用户绑定。

---

## 2. 协议版本与兼容策略

- 设备通过客户端属性 `protocolVersion`（整数，当前 `1`）上报自己说的版本；
- 每条 RPC 命令信封里也带 `protocolVersion`，收端不匹配即拒绝（`INVALID_PARAMS`）。

**同一大版本内的兼容规则（双方都必须遵守）：**

- 接收方 **必须忽略** 自己不认识的 JSON 字段和遥测 key，**不得报错**；
- 新增 key、新增枚举成员、新增 RPC 方法 = 兼容变更，不升版本；
- 重命名字段、改变类型、**改变单位** = 不兼容变更，必须升 `Version`。

`fixtures/protocol/v1/telemetry_snapshot_forward_compat.json` 就是用来固定这条规则的：它包含两个
V1 不认识的 key，双方的解析器都必须接受它并正确解出其余字段。

---

## 3. 遥测：30 秒完整快照

- **Topic**：`v1/devices/me/telemetry`（ThingsBoard 原生，不可配置）
- **QoS**：1
- **周期**：默认 30 秒，可由共享属性 `reportIntervalSec` 覆盖
- **方向**：设备 → 云。**后台不再逐台轮询**（旧设计的 `requestOilcan` 轮询已废弃）

报文使用 ThingsBoard 的显式时间戳形式：

```json
{ "ts": 1756713600000, "values": { ... } }
```

`ts` 是设备本机墙钟时间，**epoch 毫秒，UTC**。V1 废弃了旧的 `"yyyy-MM-dd HH:mm"` 字符串——它没有秒、
没有时区，逼得后台只能猜 Asia/Shanghai。断网补传时必须填测量当时的 `ts`，不能填上传时间。

### 设备级 key

| key | 类型 | 含义 |
|---|---|---|
| `snapshotSeq` | int64 | 自设备启动起每发一条快照 +1，用于发现丢帧 |
| `tankCount` | int | 本次快照包含的油罐数 |

### 每罐 key（前缀 `tank{n}.`，`n` 从 1 开始，与设备本机屏幕上的罐号一致）

| key | 类型 | 单位 |
|---|---|---|
| `tank{n}.oilType` | string | 油品名称 |
| `tank{n}.oilLevelMm` | double | **毫米** |
| `tank{n}.waterLevelMm` | double | **毫米** |
| `tank{n}.tempC` | double | **摄氏度** |
| `tank{n}.oilVolumeL` | double | **升** |
| `tank{n}.waterVolumeL` | double | **升** |
| `tank{n}.oilWeightKg` | double | **千克** |
| `tank{n}.nullVolumeL` | double | **升** |
| `tank{n}.oilRatioPct` | double | **百分比 0–100** |
| `tank{n}.waterRatioPct` | double | **百分比 0–100** |
| `tank{n}.alarmState` | string | `NORMAL` / `ALARM` / `FAULT` |
| `tank{n}.probeConnected` | bool | 探针通信状态 |

**单位写进 key 名是刻意的。** #7 的验收要求"不存在未定义单位"，把单位放进 key 名让"一个没有单位的
数字"在结构上不可能出现，而且 ThingsBoard 仪表盘直接拿 key 名当曲线标签，单位不会丢。

**扁平标量 key 而不是一个嵌套 JSON 大对象**，是为了让 ThingsBoard 自身的聚合、TTL 和趋势图直接可用。

这一条已于 2026-09-01 对真实 ThingsBoard CE 4.2.1.1 实测（复现步骤见
`docs/protocol-v1-reconciliation.md` §A）：同一条报文里同时发扁平键与嵌套数组，对扁平键
`agg=AVG/MIN/MAX` 与 5 分钟降采样分桶全部返回正确值；对嵌套数组，**`AVG` 静默返回 `0.0`、
降采样每个桶都是 `0.0`、`MIN`/`MAX` 返回空**，而 `COUNT` 却是正确的 `10`。内部字段无法寻址
（`tanks.oilLevel` / `tanks[0].oilLevel` 均返回 `{}`）。

**注意失效模式**：不是报错，是**静默的错误数据**。趋势图会画出贴零的平直线，而按 §"数据与消息策略"
"7 天后删除原始明细、保留分钟级和小时级汇总"处理后，汇总全为 0 且原始数据已删——数据永久丢失
且无人察觉。这是本协议选择扁平键的决定性理由。

### 相对旧 `NetOilCan` 的取舍

| 旧字段 | V1 去向 |
|---|---|
| `oilRadio` / `waterRadio` | 改名 `oilRatioPct` / `waterRatioPct`。旧名是 ratio 的拼写错误，V1 是纠正它的时机 |
| `id` | 改为 `tank{n}` 中的 `n` |
| `deviceName` | 删除，由 ThingsBoard 会话隐含 |
| `oilColor` | 删除，纯 UI 字段，云端从不使用 |
| `probeAddress` / `probeType` / `address` / `fatherAllProbe` | 移到**客户端属性**，它们是配置不是时序数据 |
| `object` | 删除，未使用的占位字段 |

### 参考样例

- `fixtures/protocol/v1/telemetry_snapshot.json` — 设备发出的原始报文
- `fixtures/protocol/v1/telemetry_snapshot_rest.json` — **同一条快照**经 ThingsBoard REST 读回的样子

第二个 fixture 很重要：**ThingsBoard 会把所有遥测值字符串化**，数字和布尔也不例外
（`"1842.5"`、`"true"`），JSON 值则变成一个 JSON 字符串。这一点已在真实实例上实测确认。任何只处理
设备侧形状的解析器读 REST 都会失败，所以两个 fixture 都必须过。

---

## 4. 即时事件：不等 30 秒周期

以下事件在发生的**瞬间**发出，不等下一个快照周期。走同一个 telemetry Topic，但承载为**单个 JSON
对象值**而不是拆成多个标量 key——快照可以按 key 拆，离散记录不行：读到"半条报警"比读不到更糟。

实测延迟：设备 → ThingsBoard → 后台 WebSocket，报警产生 **29 ms**，恢复 **66 ms**
（`internal/tbclient/e2e_test.go` 的 `TestAlarm_ArrivesWithoutWaitingForSnapshot`，该用例把快照周期
故意设成 10 分钟，若报警搭快照的车则绝无可能通过）。

### 4.1 报警产生 / 恢复 — key `alarm`

```json
{ "ts": 1756713612345,
  "values": { "alarm": {
    "alarmId": "9f1c2b04-7c1e-4f0a-9a7d-2c5b8e0d41aa",
    "tank": 1,
    "type": "HIGH_HIGH_LEVEL",
    "severity": "CRITICAL",
    "status": "ACTIVE",
    "text": "1号罐高高液位报警",
    "value": 1980.4,
    "tsMs": 1756713612345
  } } }
```

- `alarmId` 由**设备**生成，同一次报警的 `ACTIVE` 与 `CLEARED` 必须用**同一个** `alarmId`。
  仅靠 `(tank, type)` 配对是不够的——断网补传时两次重叠的同类报警会配错。
- `tank`：1 起的罐号；`0` 表示设备级报警。
- `type`：`HIGH_HIGH_LEVEL`、`HIGH_LEVEL`、`LOW_LEVEL`、`LOW_LOW_LEVEL`、`HIGH_WATER_LEVEL`、
  `HIGH_TEMPERATURE`、`PROBE_DISCONNECTED`、`LEAK_SUSPECTED`、`OVERFILL_RISK`
- `severity`：`CRITICAL` / `MAJOR` / `MINOR` / `WARNING` / `INDETERMINATE`
  （**照抄 ThingsBoard 自己的枚举拼写**，这样规则链可以直接透传，不需要翻译表）
- `status`：`ACTIVE` / `CLEARED`。**确认（acknowledge）是云端代操作员做的动作，设备永远不上报它。**
- `value`：触发时的读数，单位是该类报警自己的单位。

### 4.2 出入库事件 — key `stockEvent`

一次完成的进油/出油记录，在记录关闭的**瞬间**上报。

```json
{ "ts": 1756717200000,
  "values": { "stockEvent": {
    "eventId": "in-1-1756713600000",
    "tank": 1, "direction": "IN", "oilType": "0号柴油",
    "beginTimeMs": 1756713600000, "endTimeMs": 1756717200000,
    "beginLevelMm": 402.0,  "endLevelMm": 1642.5,
    "beginVolumeL": 6030.0, "endVolumeL": 24030.0,
    "beginWaterLevelMm": 18.3, "endWaterLevelMm": 18.5,
    "beginWaterVolumeL": 210.4, "endWaterVolumeL": 212.7,
    "beginWeightKg": 5065.2, "endWeightKg": 20185.2,
    "beginTempC": 22.1, "endTempC": 23.4,
    "netVolumeL": 18000.0,
    "duringDeliveryVolumeL": 420.0,
    "tsMs": 1756717200000
  } } }
```

- `direction`：`IN`（进油/收油）/ `OUT`（出油/发油）。
- `eventId`：设备生成，同一次移动唯一且在补投中保持稳定，云端据此去重。
- **`netVolumeL` 的符号是"油罐自身的变化"**（end − begin），因此出库为负。这样两个方向共用一个
  字段，读方不必按 `direction` 再翻一次符号。
- `duringDeliveryVolumeL`：**卸油期间加油机仍发出的油量**。油库边进边卖时，只看罐的净变化会把
  到货量算少——实际到货 = `netVolumeL + duringDeliveryVolumeL`。这是跟供油商对账时争的那个数。
- `beginTimeMs`/`endTimeMs` 是这次移动的时间窗，与 `tsMs`（记录关闭时刻）不同：一次卸油可能持续
  一小时，对账关心的是它什么时候开始。

**设备侧现状**：这条通道已经在跑（`ThingsBoardManager.reportRawEvent("stockEvent", …)`，且复用报警
的补投队列），但目前发的是 `DetailInoutRecord.toString()`——Java 调试字符串而非 JSON，云端无法解析。
改为 `gson.toJson(...)` 并按上表字段命名即可，见 zhongchengyeweiyi#39。

### 4.3 控制命令最终结果 — key `cmdResult`

见第 6 节。

### 4.4 上线 / 离线

**设备侧不需要做任何事。** ThingsBoard 由 MQTT 会话自身维护 `active` 服务端属性并产生连接事件。
旧设计里那个 retained 的 `isOline` 遗嘱消息在 V1 中**取消**。

---

## 5. 属性（Attributes）

Topic：`v1/devices/me/attributes`（上报客户端属性 / 订阅共享属性下发）。

判据很简单：**按快照周期变化的进遥测；只有人去改配置时才变的进属性**——否则一个一天变不了一次的值
会被存 2880 次。

### 5.1 客户端属性（设备所有，连接时和变更时上报）

| key | 类型 | 含义 |
|---|---|---|
| `protocolVersion` | int | 恒为 `1` |
| `deviceModel` | string | 设备型号 |
| `hardwareVersion` | string | 硬件版本 |
| `tankCount` | int | 油罐数量 |
| `localControlMode` | bool | `true` = 现场手动模式，远程控制会被拒绝 |
| `tank{n}.probeAddress` | string | 探针地址 |
| `tank{n}.probeType` | string | 探针类型 |
| `tank{n}.capacityL` | double | 油罐公称容积，**升** |

**注意：属性与遥测不同，属性值保留原本的 JSON 类型**（布尔就是布尔），不会被字符串化。这两个
ThingsBoard API 行为不一致，已实测确认并在 `TestUplink_ClientAttributesReachBackend` 中固定。

### 5.2 共享属性（云端所有，ThingsBoard 下发给设备）

| key | 类型 | 含义 |
|---|---|---|
| `reportIntervalSec` | int | 覆盖 30 秒快照周期 |
| `alarmThresholds` | JSON | 每罐报警阈值；**权威副本在业务库**，这里是下发的副本 |

设备必须**不重启即可生效**。

共享属性**刻意不走命令状态机**：它是"期望状态"，ThingsBoard 会在设备每次重连时重新下发，不需要
自己的投递跟踪。凡是需要确认、审计、有有效期的动作，一律用 RPC。

### 5.3 OTA 属性（ThingsBoard 保留名，本协议无权重定义）

下发：`fw_title`、`fw_version`、`fw_size`、`fw_checksum`、`fw_checksum_algorithm`
上报：`current_fw_title`、`current_fw_version`、`fw_state`

> **OTA 的范围提醒：** ThingsBoard 的 OTA 只做云端分发那一半（存包、版本、分组、进度）。设备端接收、
> 校验、刷写的客户端逻辑在 `../android/` 中**目前完全不存在**，选用 ThingsBoard
> 并不会凭空产生它。这仍是设备侧的待办工作。

---

## 6. 远程控制：RPC + ACK + 最终结果

### 6.1 为什么是三段而不是一段

`pending → sent → received → executing → succeeded/failed` 中，**MQTT 发布成功只代表消息发出了**。
因此 V1 把"设备收到了吗"和"设备做成了吗"拆成两条不同的消息：

| 阶段 | 载体 | 含义 |
|---|---|---|
| ACK | RPC 响应（同步返回给后台） | 设备**收到并接受**了请求，或**当场拒绝** |
| 最终结果 | telemetry `cmdResult`（异步，稍后） | 泵到底转没转 |

把 ACK 当成功，正是这套状态机存在的意义所在。`e2e_test.go` 的
`TestControl_ReportsFailureAfterAccepting` 专门固定这个分支：设备答应了，动作依然没做成。

### 6.2 状态机

| 状态 | 由谁写入 | 说明 |
|---|---|---|
| `pending` | 后台 | 命令行已落库，尚未交给 ThingsBoard |
| `sent` | 后台 | ThingsBoard 接受了这次 RPC 调用 |
| `received` | 设备 | ACK，非终态 |
| `rejected` | 设备 | ACK，**终态** |
| `executing` | 设备 | 可选的 `cmdResult`，用于耗时动作 |
| `succeeded` | 设备 | `cmdResult`，**终态** |
| `failed` | 设备 | `cmdResult`，**终态** |
| `expired` | 后台 | 到 `expiresAtMs` 仍未进终态，**终态** |

### 6.3 请求

后台 `POST /api/rpc/twoway/{deviceId}`：

```json
{ "method": "pump.start",
  "params": {
    "commandId": "c1a7e3d8-5b62-4d19-8f30-1e9a4c77b201",
    "protocolVersion": 1,
    "args": { "pump": 1, "tank": 1 },
    "issuedAtMs": 1756713600000,
    "expiresAtMs": 1756713660000,
    "operator": "zhangsan@customer-a",
    "reason": "手动补油，值班室确认"
  },
  "timeout": 10000 }
```

信封嵌在 `params` 里而不是取代整个 body，因为 ThingsBoard 自己要读 `method` 写它的审计日志。

- `commandId`：**由后台生成**，不是 ThingsBoard 每次调用自己的 requestId（那个每次重试都会变）。
- `reason`：二次确认时操作员必填的理由，一路带到审计记录。**后台校验非空**。
- `timeout`：只约束 **ACK** 的等待时间，不约束执行。

### 6.4 方法表

| method | 权限 | 物理动作 | 说明 |
|---|---|:---:|---|
| `pump.start` | control | ✅ | 启泵 |
| `pump.stop` | control | ✅ | 停泵 |
| `valve.open` | control | ✅ | 开阀 |
| `valve.close` | control | ✅ | 关阀 |
| `alarm.mute` | control | | 现场消音，不改变报警状态 |
| `alarm.clear` | control | | 清除已恢复的报警 |
| `device.reboot` | admin | ✅ | 重启设备 |
| `config.setAlarmThresholds` | admin | | 修改报警阈值 |
| `config.setTank` | admin | | 修改油罐配置 |
| `config.setProbe` | admin | | 修改探针配置 |
| `config.setTankSettings` | admin | | 修改单个罐的设置（cloud#71，args 见 tank-settings-contract.md） |
| `config.setDeviceSettings` | platform | | 修改整机出入库判定阈值（仅平台管理员，cloud#71） |

> **"物理动作"这一列是安全闸门。** 按 issue #1，设备端联锁尚未补齐、未经真机验证的动作**不得开放到
> 生产环境**。配置写错还能改回来，意外启泵不能。这一列由
> `TestMethods_PhysicalActionsAreFlagged` 固定，防止它被悄悄改成 `false`。

表外的 method：后台必须在到达 ThingsBoard **之前**就拒绝；设备若仍收到，回 `UNKNOWN_METHOD`。

### 6.5 ACK

设备发到 `v1/devices/me/rpc/response/{requestId}`：

```json
{ "commandId": "...", "status": "received", "tsMs": 1756713600412 }
```

拒绝：

```json
{ "commandId": "...", "status": "rejected",
  "errorCode": "INTERLOCK_BLOCKED",
  "reason": "1号罐液位低于下限，设备端联锁禁止启泵",
  "tsMs": 1756713600418 }
```

### 6.6 最终结果

```json
{ "ts": 1756713604700,
  "values": { "cmdResult": {
    "commandId": "...", "status": "succeeded",
    "detail": { "pump": 1, "running": true },
    "tsMs": 1756713604700
  } } }
```

`rejected` 是 ACK 阶段的终态，**不再发 `cmdResult`**。设备应在 ACK 前完成参数预检，非法参数直接回
`rejected` ACK。

**兼容规则（#42，云端单方面落地，待设备端负责人确认）**：现场旧固件先回 `received` ACK、再校验参数，
把拒绝作为 `cmdResult` `status: "rejected"`（带 `errorCode`/`reason`，如 `INVALID_PARAMS`）上报。云端接受这种
结果：命令从 `pending`/`sent`/`received`/`executing` 进入终态 `rejected`，错误码与原因取自该结果（覆盖 ACK 的
"已受理"），审计 `control.result` 为 `denied`。设备不得以此表示"已部分执行"——`rejected` 一律视为未写入任何字段。
命令已终态后才到的 `rejected` 结果按迟到结果处理（只写 `control.result_late`，状态不变）。
`cmdResult` 里的其他非法状态（`expired`、`received` 等）或不可解析的 `commandId` 被丢弃并记日志，不阻断同一设备其他
结果的回读。

### 6.7 错误码

| 码 | 含义 |
|---|---|
| `UNKNOWN_METHOD` | 方法名不在协议内 |
| `INVALID_PARAMS` | 参数缺失或越界，含协议版本不匹配 |
| `EXPIRED` | 到达时已超过 `expiresAtMs` |
| `INTERLOCK_BLOCKED` | 设备端联锁拒绝 |
| `BUSY` | 另一条命令正在执行 |
| `NOT_SUPPORTED` | 本机型无此硬件 |
| `HARDWARE_FAULT` | 执行中硬件故障 |
| `LOCAL_MODE_LOCKED` | 设备处于现场手动模式 |

### 6.8 有效期与幂等

**有效期：** 设备**必须**用自己的时钟比对 `expiresAtMs`，超期即回 `EXPIRED` 而不是执行。这条检查
必须在设备端，因为只有设备知道自己**什么时候真正收到**。它防的是：命令在断网期间卡在队列里，几小时
后操作员早已离开，泵却突然启动。

**幂等：** 设备保留已处理的 `commandId`。重复投递时：

- **不得重复产生现场动作**；
- 回放原来的 ACK，并置 `duplicate: true`。

```json
{ "commandId": "...", "status": "received", "duplicate": true, "tsMs": 1756713605990 }
```

这条保证正是后台敢于重试"不确定是否送达"的命令的前提。
（`TestControl_DuplicateCommandIsNotReExecuted` 固定）

---

## 7. 未知字段与错误处理

- 未知遥测 key、未知 JSON 字段 → **忽略**，不报错（第 2 节）；
- 未知 `method` → `UNKNOWN_METHOD`；
- 未知报警 `type` → 接收方**仍须存储并展示**，不得丢弃；
- 报文无法解码 → 记录并丢弃该条，**不得**因此断开 MQTT 会话。

---

## 8. 消息大小与容量输入

以下是**实测字节数**（UTF-8、紧凑 JSON，由 `internal/protocol` 编码本仓库 fixture 得出），不是估算：

| 报文 | 大小 | 数据点数 |
|---|---:|---:|
| 1 罐快照 | 380 B | 14 |
| 2 罐快照 | 696 B | 26 |
| 4 罐快照 | 1328 B | 50 |
| 报警事件 | 235 B | 1（JSON 值） |
| cmdResult | 173 B | 1（JSON 值） |
| 客户端属性（2 罐） | 350 B | 13 |

**每罐 12 个数据点 + 设备级 2 个。** 容量估算按数据点算，不是按消息算：

- 1,000 台 × 2 罐 = 26 点/台/30 秒 ≈ **867 点/秒**
- 10,000 台 × 2 罐 ≈ **8,670 点/秒**

上述只是稳态遥测，**还必须叠加**：报警突发、RPC、以及断网恢复后的重连风暴与补传。ThingsBoard
默认 MQTT 报文上限 64 KB，本协议远低于此。

---

## 9. QoS、重连与离线补投

| 项目 | 规则 |
|---|---|
| QoS | 全部 1。协议本身幂等（快照带 `ts`，命令带 `commandId`），至少一次重复无害 |
| Clean session | `true` |
| 重连退避 | 2 s 起，指数增长，**上限 60 s**，并加 ±20% 抖动——整队设备断网恢复时不能齐步重试 |
| 快照补传 | **不补**。只保留最新一条；30 秒前的液位没有留存价值 |
| 事件补传 | **必须补**。报警与 `cmdResult` 进本地 FIFO 队列，按真实 `ts` 补发 |
| 事件队列上限 | 建议 30 天 / 10,000 条，**具体数字待产品确认**（见第 12 节） |

---

## 10. 参考实现与契约测试

| 位置 | 作用 |
|---|---|
| `internal/protocol/` | 协议的 Go 实现，**唯一真源** |
| `fixtures/protocol/v1/` | 跨仓库共享 fixture，Android 侧应当用**同一批文件**做测试 |
| `internal/protocol/contract_test.go` | 对每个 fixture 做**双向**校验：解码得到期望结构，编码结果与 fixture 逐 key 相等 |
| `internal/devicesim/` | 设备侧参考实现（可被测试在进程内驱动） |
| `cmd/tbdevicesim/` | 手工联调用的命令行封装 |
| `internal/tbclient/e2e_test.go` | 对真实 ThingsBoard CE 的端到端验收 |

双向校验是关键：只验解码，写出去的报文跑偏了也不会被发现。

**Android 侧照做即可让协议漂移在两边同时构建失败**，而不是在真机上才发现。

---

## 11. 本协议明确不包含

- Alink Topic（`sys/{productKey}/{deviceName}/thing/...`）
- EMQX 及其 ACL
- 任何第二套 MQTT 接入通道
- Go 后台自建的设备凭据体系
- 设备侧轮询响应式的 `requestOilcan` / `requestAlarm` / `requestUnloadOil` / `requestShift` /
  `requestOilLeak` / `requestOilStorage`

---

## 12. 冻结前待确认事项

**这些不是 TBD 占位，是明确列出、需要具体人拍板的条目。** 第 1–11 节的内容已实现并验证，此处每条都
标明了当前取值与需要谁确认。

### 需 Android 负责人确认

1. **单位**：`oilLevelMm` 毫米、`oilVolumeL` 升、`oilWeightKg` 千克、`tempC` 摄氏度、
   `oilRatioPct` 0–100。这是按行业惯例取的，**未与设备实际寄存器量纲核对过**。若设备内部是厘米或
   立方米，改的是 key 名（升版本），不是悄悄改语义。
2. **`oilRatioPct` / `waterRatioPct` 的类型**：旧 `NetOilCan` 里是 `int`，V1 定为 `double`。
   确认设备能给出小数精度，或确认取整可接受。
3. **罐号 `n` 的来源**：假定等于设备本机屏幕上的罐号，且在设备生命周期内稳定。
4. **`localControlMode`**：设备当前是否真有"现场手动模式"这一状态可上报。
5. **出入库的两个字段语义未确认，故意未写进协议**（同 `config.setTank` 的处理方式——不猜）：
   - `DetailInoutRecord.beginTcVolume` / `endTcVolume`：名字读作温度补偿体积（标准体积），若真是
     它则对对账很重要；但代码是从 `OilCan.getSurPlus()`（余量）赋值的，而 `surPlus` 本身没有任何
     说明。**一个以某个量命名、却用另一个量填充的字段，正是最不该冻进协议的**。
   - `duringDelivery` 的符号约定：已按
     `saleVolume − (endVolume − beginVolume)` 建模为 `duringDeliveryVolumeL`，请确认方向。
6. **方法表**：#1 列出的控制范围是否已完整覆盖，以及每个方法的 `args` 具体形状——
   `config.setTank` / `config.setProbe` 的参数结构目前**尚未定义**，因为设备侧
   `requestTankTable` / `requestTankSet` / `requestProbeSet` 至今没有处理逻辑，现在定型等于凭空猜。

### 需产品确认

0. **交班（`CommitWork`）是否纳入范围**：V1 两侧都没有交班上行通道。云端 `/api/shifts` 已于
   2026-09-01 删除（留一个永远返回空列表的端点比没有更糟——它看起来像个能用的功能）。若产品需要
   交班远程可见，需两侧新建上行；否则本条即为最终结论。

7. **离线事件队列的保留天数与条数上限**（第 9 节的 30 天 / 10,000 条是沿用旧设计的示例数字，不是决定）。
8. **命令有效期默认值**（fixture 用 60 秒）。
9. **设备离线多久算"异常"**——这条从旧设计文档起就一直悬着。

### 需在设备侧补齐（非协议问题，但阻塞生产启用）

10. **联锁**：第 6.4 节标 ✅ 的物理动作，在设备端联锁补齐并真机验证之前不得开放生产。
11. **OTA 客户端**：接收 / 校验 / 刷写逻辑在 Android 仓库中完全不存在（第 5.3 节）。
