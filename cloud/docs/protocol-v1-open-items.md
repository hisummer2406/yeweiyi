# 设备协议 V1：最终待确认清单

**状态：未冻结。** 2026-10-06 复核设备端（见下方"2026-10-06 复核"）：A-1 至 A-6 在设备端
`feature/thingsboard-device-protocol`（PR #38，未合并）上已对齐，**待合并与真机联调**。2026-09-04
逐项对照两侧当前代码重新核对，取代
[`protocol-v1-reconciliation.md`](protocol-v1-reconciliation.md) 中"建议的下一步"一节的时效性
内容（那份文档仍是差异的完整背景，本文档是**待办清单**）。

核对基准：

| 仓库 | 版本 | 核对时间 |
|---|---|---|
| yeweiyi-cloud | `26fb6b6`（PR #11 合并后的 main） | 2026-09-04 |
| zhongchengyeweiyi | `d0c3bce` | 2026-09-04 |

**在本清单清空之前，不要往任一侧的协议实现里新增字段。** 现在停手改名成本还小。
例外（2026-10-06）：设备端负责人已在跨仓库 issue 里提出、云端只做消费的**可选、向后兼容**字段
（#14 标定属性、#15 `source`）允许先落地；云端自行发起的新字段仍然禁止。
本文档也**不代表云端单方面的定稿**：下面每一条都标了决定人，云端能自己决定的只有标着
"云端"的那几条。

## 2026-10-06 复核：设备端 PR #38

核对基准：zhongchengyeweiyi `origin/feature/thingsboard-device-protocol`@`d2a44ac`（领先其 `main` 27 个
提交）。设备端 `main`@`2a2e0ee` **没有任何 ThingsBoard 代码**，下列对齐都只存在于未合并的 PR #38。

| 项 | 复核结果 | 依据 |
|---|---|---|
| A-1 | 代码已改：终态走 `cmdResult` 遥测，RPC 只发一条响应；**未经真机验证** | `RpcDispatcher`、`RpcWirePayloads`、`RpcResultPublisher`（复用补投队列） |
| A-2 | 代码已改：ACK 含 `commandId`、小写状态、`duplicate`、`tsMs` | `rpc_ack_*.json` 与云端逐字节一致 |
| A-3 | 代码已改为扁平键 | `telemetry_snapshot*.json` 一致 |
| A-4 | 代码已改：键 `alarm`，`alarmId` 含产生时刻，恢复复用同一 `alarmId` | `alarm_*.json` 一致 |
| A-5 | 基本对齐：`protocolVersion` 整数 1；10 个方法中 8 个已注册；`LOCAL_MODE_LOCKED` 已有 | `config.setTank`/`config.setProbe` 未实现（B-2） |
| A-6 | 代码已改为结构化 JSON | `stock_event_*.json` 一致 |

仍未完成，且云端不得因此宣称兼容：

- PR #38 未合并；设备端自述"零真机验证"（273 个 JVM 测试，没连过真实 ThingsBoard 与硬件）。
- 物理命令（`pump.*`、`valve.*`、`device.reboot`）在设备端只有占位，等真机（设备端 #34）。
- 设备端称云端没有"为真实设备创建 ThingsBoard 身份"的接口，装机拿不到凭据（**云端待核实**）。
- 设备端 fixture 副本拷贝自 `26fb6b6`，缺 #14 标定属性；设备端实际会发 `tank{N}.calibrationHeightMm`/
  `calibrationVolumeL`，该属性目前无契约测试覆盖。
- 设备端文档称 `source` "云端没有这个字段、不需要"，与事实不符：云端 `internal/protocol/stock.go` 已解析
  `source`（#15）。设备端补录仍发 `MANUAL`，云端可消费，仅文档过时，需设备端更正。
- A-7：设备端确认恢复事件复用产生事件的 `alarmId`；补投后是否可能先收到 `CLEARED` 仍未答复。
- B-9：设备端称不会用同一 `eventId` 重发修正版，但"待回复云端"，尚无正式答复。

---

## A. 阻塞项：按今天的两份代码，链路走不通

### A-1 设备没有 `cmdResult` 上行通道，命令永远到不了终态 ⛔（旧实现已实测；PR #38 已改，待真机验证）

**这是本次核对新发现的一条，先前的对账文档没有记。** 它比 A-2 的字段改名严重，因为它不是
"解析不出来"，而是"整条反馈通道不存在"。**2026-09-04 已用真实 ThingsBoard 实测确认**（见下）。

云端的控制状态机建立在一条铁律上（CLAUDE.md 不变量 2、3）：**ACK 不是执行成功**。ACK 只说
设备收下了；泵到底转没转，由设备后来通过**遥测**上报的 `cmdResult` 事件决定
（`internal/ingest` 订阅 → `control.Service.ApplyResult`）。fixture 是
`cmd_result_succeeded.json` / `cmd_result_failed.json`。

设备侧没有这条通道。`RpcDispatcher.dispatch()` 把受理和终态**都**发到
`v1/devices/me/rpc/response/{mqttRequestId}`：

```java
// RpcDispatcher.java:88   受理
ackPublisher.publishAck(mqttRequestId, RpcResultStatus.RECEIVED, RpcErrorCode.NONE, "已受理");
result = handler.handle(request);              // 真正执行，可能很久
// RpcDispatcher.java:104  终态，发到同一个 requestId
ackPublisher.publishAck(mqttRequestId, result.getStatus(), result.getErrorCode(), result.getMessage());
```

ThingsBoard 的双向 RPC 在收到**第一条**响应时就把该 requestId 的调用完成并返回给调用方。
第二条 publish 打向一个已经关闭的请求——**云端拿不到终态**，命令停在 `received`，直到 TTL
把它扫成 `expired`。届时审计日志会显示"设备接受了启泵命令，然后结果不明"，而泵可能已经转了。

> **实测状态：已实测（2026-09-04，真实 ThingsBoard CE）。** 初稿这里写的是"未实测、以上为推断"，
> PR #12 的复审也拒绝接受推断。现已用一台真实设备把它测掉：
> `internal/tbclient/rpc_response_channel_test.go` 的
> `TestTwoWayRPC_SecondDeviceResponseNeverReachesTheBackend` 让设备在**同一个** RPC 响应 topic 上
> 先回 `received`、1.5 秒后再回 `succeeded`，然后同时观察云端的两条读取通道。
>
> 实测结果：
>
> ```
> device published [received succeeded] on the RPC response topic;
> the backend received only "received" and no cmdResult telemetry.
> ```
>
> 即 `SendCommand` 只拿到第一条，六秒的观察窗内遥测流上没有任何 `cmdResult`。**推断成立**：
> 按今天的设备端实现，每条命令都会停在 `received` 直到被 TTL 清扫成 `expired`。
>
> 这个测试**保留**在仓库里，而不是跑完就删——万一日后的 ThingsBoard 升级改变了这个行为，
> 应该在这里被发现，而不是在现场。测试用裸 paho 客户端而不是 `internal/devicesim`：后者是本项目的
> 参考实现，已经在做正确的事，教它做错等于把错误行为藏进一个日后会被误认为受支持的开关。

**需要的改动（设备端）：** 终态不要发第二条 RPC 响应，改为按 `cmd_result_*.json` 的形状发一条
遥测事件：`{"ts":…,"values":{"cmdResult":{"commandId":…,"status":…,"detail":…,"tsMs":…}}}`。
这条同样应该进已有的补投队列——命令结果丢了比遥测丢了严重得多。

**决定人：** Android 负责人（形状已由云端 fixture 定义，本条不是取舍，是缺失）。

### A-2 ACK 载荷缺 `commandId`，云端无法关联（PR #38 已对齐，待合并）

设备发的 ACK（`ThingsBoardManager.java:205-211`）：

```json
{"status": "RECEIVED", "errorCode": "NONE", "message": "已受理"}
```

云端的 `protocol.Ack`（`rpc_ack_received.json`）：

```json
{"commandId": "c1a7e3d8-…", "status": "received", "tsMs": 1756713600412}
```

四处不一致，其中第一处是硬阻塞：

1. **缺 `commandId`。** 云端靠它把 ACK 关联到命令行；ThingsBoard 自己的 requestId 每次重投都
   会变，正是不能用来做关联键的那个东西。
2. `status` 大写 vs 小写。**任选一个，定下来即可**——建议小写，与遥测/命令状态机其它取值一致。
3. `errorCode: "NONE"` vs 省略该字段。建议无错误时省略（云端 `omitempty`）。
4. `message` vs `reason`。建议 `reason`，与 RPC 请求信封里的 `reason` 同名同义。
5. 云端还定义了 `duplicate`（重复 commandId 回放上次终态时置真）和 `tsMs`，设备侧的 ACK 里
   没有。设备的幂等逻辑（`ProcessedCommandStore`）已经实现了这个行为，只是没在 ACK 里标出来。

**决定人：** 双方（2–4 是命名取舍，1、5 是缺失）。

### A-3 遥测结构：扁平键 vs 嵌套数组（PR #38 已对齐，待合并）

云端 `tank1.oilLevelMm` 扁平键；设备 `tanks: [{id, oilLevel, …}]` + 单独的 `units` 对象
（`app/src/test/resources/thingsboard/telemetry_snapshot.json`，本次核对时仍是原样）。

**这条已由实测定案，不是取舍**：嵌套结构下 ThingsBoard 的聚合**不报错，静默返回 0**，会让
issue #1 要求的降采样功能产出一条贴着零的假曲线，而原始明细已按 TTL 删除。复现步骤和实测数据
见 [`protocol-v1-reconciliation.md`](protocol-v1-reconciliation.md) §A。**若设备侧对结论有异议，
请按那份复现步骤自行验证一次再讨论**，不要仅凭偏好。

**决定人：** Android 负责人执行迁移；结论本身已有证据支持。

### A-4 报警事件：键名与全部字段都不一致（PR #38 已对齐，待合并）

| 语义 | 云端 | 设备 |
|---|---|---|
| 遥测键 | `alarm` | `alarmEvent` |
| 事件 ID | `alarmId`（UUID） | `eventId`（`"2-1"` = 类型-罐号） |
| 状态 | `status`: `ACTIVE`/`CLEARED` | `state`: `raised`/`recovered` |
| 类型 | `type`: `HIGH_HIGH_LEVEL` 字符串枚举 | `alarmType`: `2` 整数码 |
| 罐号 | `tank` | `oilCanId` |
| 时间 | `tsMs` | `deviceTime` |
| 严重度 / 文本 | `severity` / `text` | 无 |

其中只有一处是真正需要一起权衡的取舍，其余是改名：**`eventId` 的时间唯一性**。设备侧按
"类型+罐号"派生，掉电重启也能算出同一个值，对嵌入式端是实打实的好处；代价是同一罐同一类型的
两次报警拿到同一个 ID，断网补传时恢复事件可能关掉上一次那条。折中建议：派生值里加上本次报警的
起始时间（`"2-1-1735689600000"`），仍然确定性、不需要长期状态，同时时间上唯一。

`alarmType` 建议用字符串枚举：整数码要双方各维护一张对照表，从 broker dump 一条报文看到
`"alarmType": 2` 谁也不知道是什么。

**决定人：** 双方。

### A-5 RPC 请求信封：字段名与方法命名（PR #38 基本对齐，待合并）

| 语义 | 云端 | 设备 | 建议 |
|---|---|---|---|
| 协议版本 | `protocolVersion: 1`（整数） | `protocolVersion: "tb-v1"`（字符串） | 需定一个。若取字符串，云端 `protocol.Version` 与 `MANIFEST.json` 的 `protocolVersion` 要一起改 |
| 下发时间 | `issuedAtMs` | `issuedAt` | 带 `Ms` 后缀，与遥测的单位后缀风格一致 |
| 过期时间 | `expiresAtMs` | `expireAt` | 同上 |
| 方法名 | `pump.start` / `config.setAlarmThresholds` | `setAlarmThreshold` | 带点号命名空间；方法会变多，ThingsBoard 审计日志里按前缀好筛 |
| 阈值参数 | 见 A-6 | `{oilCanId, hheightOilAlarm}` | 随 A-4 的罐号命名统一 |

**错误码需要取并集**，两边各有对方没有的，且有一对**不能合并**：

- 云端独有：`INTERLOCK_BLOCKED`、`BUSY`、`NOT_SUPPORTED`、`HARDWARE_FAULT`、`LOCAL_MODE_LOCKED`
- 设备独有：`FEATURE_DISABLED`、`PROTOCOL_VERSION_UNSUPPORTED`、`MALFORMED_PARAMS`、`INTERNAL_ERROR`
- `INTERLOCK_BLOCKED`（此刻的物理状态不允许，换个时刻可能就允许）与 `FEATURE_DISABLED`
  （这个功能整体没开放，联锁未验证）**不是同一件事，两个都要保留**。

**决定人：** 双方。

### A-7 报警"产生"与"恢复"是否合并为一行（issue #29 开放问题，**待设备端确认**）

落库时沿用 `/api/alarms` 既有语义：`ACTIVE` 与 `CLEARED` 各占一行，共用设备生成的 `alarmId`，以
`(设备, alarmId, 状态)` 唯一。合并成"一次报警一行（含恢复时刻）"会改变响应形状，是对小程序的契约变更，
所以没有顺手做。需要设备端确认：同一次报警的 `ACTIVE`/`CLEARED` 是否保证共用 `alarmId`、补投后是否
可能先收到 `CLEARED`（落库对顺序不敏感，但合并展示会）。

### A-6 出入库 `stockEvent`（PR #38 已改结构化 JSON，待合并）

设备仍以 `DetailInoutRecord.toString()` 上报（Java 调试字符串）。完整的字段映射、所需的设备端
改动、以及仍待确认的四点，见 [`protocol-v1-stock-event.md`](protocol-v1-stock-event.md)。

云端已改为在解析失败时返回 502 而非 200 + 空数组，所以这一条不再会静默地看起来像正常。

**决定人：** Android 负责人 + 产品（是否纳入本期）。

---

## B. 需要产品或硬件负责人拍板，云端无法决定

| # | 事项 | 现状 |
|---|---|---|
| B-1 | **单位（mm / L / kg / °C）** | 按行业惯例推断，**从未与设备真实寄存器标度核对**。对出入库尤其致命：体积差一个数量级，对账全错 |
| B-2 | `config.setTank` / `config.setProbe` 的参数形状 | 刻意未定义——设备端没有对应 handler。不要凭空补 |
| B-3 | `alarmState` 的取值 | 云端写了 `NORMAL`/`ALARM`/`FAULT`，设备侧该字段恒为 `null` |
| B-4 | `oilRatioPct` / `waterRatioPct` / `nullVolumeL` / `snapshotSeq` | 云端定义了，设备侧没有。需确认设备能否提供；`snapshotSeq` 建议保留（丢帧检测） |
| B-5 | `probeAddress` 放遥测还是客户端属性 | 建议属性：它只在改配置时变，放遥测等于每天存 2880 次常量 |
| B-6 | `deviceTime` / `connectionState` 是否删除 | 建议都删：两个时间源必然有一天对不上；设备自报"我在线"没有信息量，离线时它正好发不出来 |
| B-7 | 交班（`CommitWork`）是否纳入本期 | 两侧都没有上行通道。云端 `/api/shifts` 已删除，不是遗漏 |
| B-8 | ThingsBoard 里已堆积的不可解析出入库记录如何处理 | 见 `protocol-v1-stock-event.md` §4.4 |
| B-9 | `stockEvent` 补录后用同一 `eventId` 重发：重发的信封 `ts` 是否一定更晚、`eventId` 是否一定不变；是否需要显式 `revision` 字段 | 云端目前按信封 `ts` 折叠（issue #13，`protocol-v1-stock-event.md` "同一 `eventId` 可上报多次"），**暂行、未经 Android 确认**。决定人：Android 负责人 |

---

## C. 共用 fixture 的机制（**这是云端的准备工作，目前还不是跨仓库门禁**）

对账文档给出的做法是"以 `yeweiyi-cloud/fixtures/protocol/v1/` 为准，Android 侧通过 submodule
或构建期拷贝引入"。**做法对，但缺一步**：光是拷贝一遍，无法保证之后两侧读的还是同一批字节——
两个仓库各自写 fixture、各自注释"对方的还不存在"，正是这次不兼容的成因。

**先把话说清楚，避免把准备工作当成已完成的门禁。** 下面这些校验**只在云端跑**。Android 尚未引入
本目录、没有构建期校验、其测试也还没消费这批字节，因此**任一侧的改动并不会让两侧构建一起失败**
——这正是这套机制要解决的问题，而它现在只解决了一半。`MANIFEST.json` 的 `enforcement` 字段如实
记录了这个状态，`TestManifestDoesNotOverstateEnforcement` 保证这个声明不能在没有证据的情况下被
改成"两侧都已生效"。

云端这一侧现在有：

| 文件 | 作用 |
|---|---|
| `fixtures/protocol/v1/MANIFEST.json` | 14 个 fixture 的 sha256、上下行方向、各自钉住什么 |
| `fixtures/protocol/v1/verify-manifest.sh` | 只依赖 POSIX sh + awk + sha256 的校验器，无 Go 依赖，可直接挂进 Gradle/CI |
| `internal/protocol/manifest_test.go` | 云端侧强制：清单与文件一一对应、校验和一致、**且每个 fixture 确实被某个测试读过**（没人读的 fixture 钉不住任何东西，只会烂掉） |

刻意不做自动修复：改了 fixture 就必须手动改 `MANIFEST.json` 的 sha256，这一步摩擦正是目的——
它让任何一侧的改名或改单位**同时**让两个仓库的构建变红。

**Android 侧需要做的（一次性）：**

1. 以 submodule 或构建期拷贝把 `fixtures/protocol/v1/` 引入
   `app/src/test/resources/thingsboard/`（**替换**现有的四份自定义 fixture，不要并存）；
2. 在 `build.gradle` 的 `test` 任务前挂一次校验：

   ```gradle
   task verifyProtocolFixtures(type: Exec) {
       commandLine 'sh', "$projectDir/src/test/resources/thingsboard/verify-manifest.sh"
   }
   test.dependsOn verifyProtocolFixtures
   ```

3. 把 `TelemetrySnapshotBuilderTest` / `AlarmEventBuilderTest` / RPC 测试改为读共享 fixture，
   断言**设备产出的字节与 fixture 一致**，而不是与设备自己的结构体一致。

> **未完成，且云端不应替它完成。** 以上三步在 `zhongchengyeweiyi` 仓库里，本仓库的约定是
> 跨仓库协议改动必须经对方负责人同意，不得从这里直接改。本节写到可以照做的程度，动作由
> Android 负责人执行。
>
> **完成之后，请回来把 `MANIFEST.json` 的 `enforcement.crossRepoGate` 置为 `true`，并在
> `androidVerifiedAt` 里填上确认接入的 zhongchengyeweiyi 提交号**——不填的话云端测试会直接失败，
> 因为本仓库看不到对方的构建，这个声明必须有证据。

### C-1 这一项还差什么才算完成

| 步骤 | 状态 |
|---|---|
| 云端清单 + 校验脚本 + 契约测试 | ✅ 已完成 |
| Android 引入共享 fixture（替换现有四份自定义 fixture） | 🟡 PR #38 已放入原样副本并由 `ProtocolV1ContractTest` 读取；副本落后于 #14（缺标定属性），无 `MANIFEST.json` |
| Android 构建期挂 `verify-manifest.sh` | ⬜ 未开始 |
| Android 的 builder 测试改为断言"产出字节 == fixture" | ⬜ 未开始 |
| 两侧接上 CI，使校验真正成为门禁（本仓库目前无 CI） | ⬜ 未开始 |
| 一次真实设备到云端的端到端验证（含 A-1 的终态上行） | ⬜ 未开始，被 A-1 阻塞 |

---

## D. 云端 REST 契约 vs 小程序 —— **已由小程序负责人确认（2026-09-04）**

> **更新**：PR #12 复审指出改动 `/api/oil-in-out` 的成功响应形状属于跨仓库变更，需要小程序负责人
> 确认。`yeweiyi-cloud` 与 `yeweiyi-miniapp` 同一负责人，确认方式是**直接把小程序改对**而不是另留
> 一句"知悉"——`yeweiyi-miniapp` 分支 `rebuild/thingsboard-single-stack`（提交 `a34afc4`）已按下表
> 全部对齐，`npm run typecheck` 通过。以下原文保留，作为当时的事实核对记录。

### 事实核对（yeweiyi-miniapp `ae06989`）

小程序**存在客户端代码**，且是按**单栈改造之前**的契约写的。其 `CLAUDE.md` 第 55–60 行记录的
契约是：`GET /api/alarms`、`/api/oil-in-out`、`/api/shifts` 一律返回 `{rows: [...]}`，行内字段
沿用旧 Postgres 列名。代码与之一致：

```ts
// miniprogram/pages/device-detail/device-detail.ts:98
get<{ rows: OilInOut[] }>('/api/oil-in-out', { device, limit: 50, sort: false })
  .then((res) => this.setData({ oilInOut: res.rows, oilInOutLoaded: true }))
```

| 小程序期待 | 云端今天 | 何时改的 |
|---|---|---|
| `GET /api/oil-in-out` → `{rows: [...]}` | 本 PR 之前是裸数组；**本 PR 改回 `{rows: …}`** | 裸数组是 PR #11 引入的 |
| 行内字段 `begin_height` / `device_local_id` / `id`（旧 Alink 列） | `beginLevelMm` / `eventId` 等 V1 字段 | **PR #11** |
| `GET /api/alarms` → `{rows: [...]}` | 裸数组 | **PR #11**，本 PR 未改 |
| `GET /api/shifts` | 端点已删除 | **PR #11** |
| `POST /api/oil-in-out/correct` | 端点已删除（随 river 队列一起） | **PR #11** |
| `GET /api/oilcan/latest` | 端点已删除（由 `telemetry/latest` 取代） | **PR #11** |
| `GET /api/devices` → `{rows: [...]}` | `{rows: [...]}` ✅ | 未变 |

**结论：小程序与云端的这条链路早在 PR #11 就已经全面不兼容**，本 PR 不是它的成因。而在信封形状
这一点上，本 PR 把 `/api/oil-in-out` 从裸数组改回 `{rows: …}`，是**朝着**小程序既有契约和本仓库
自身惯例（`/api/devices` → `rows`、命令 → `commands`、审计 → `audit`）走，而不是背离。

### 需要小程序负责人确认的事项（**已确认，逐条见下**）

1. **行内字段迁移**：旧 Alink 列名 → V1 字段名。**已做**——`yeweiyi-miniapp` 的
   `types/api.d.ts` 整体重写，`device-detail.ts` 每个 tab 改读 V1 字段。
2. **`/api/alarms` 是否也改回 `{rows: …}`。** **确认为：暂不改。** 报警的键名本身（云端 `alarm`
   vs 设备 `alarmEvent`）还在 §A-4 里未定，先动响应形状等于给一个还没定的协议加行为——这个理由
   成立，维持云端现状（裸数组），小程序侧对此已知晓并按裸数组读取（`get<Alarm[]>`），在
   `types/api.d.ts` 里明确记了这处不一致以及原因，而不是当成疏漏。
3. **已删除端点的替代**：`/api/shifts` 在小程序侧已整体移除（无交班上行）；`/api/oil-in-out/correct`
   同样整体移除，**小程序侧确认：不发明替代设计**，出入库 tab 改为纯只读；`/api/oilcan/latest`
   已改用 `GET /api/devices/:device/telemetry/latest`；`/api/devices/{device}/oil-can-set` 已移除，
   概念上由新的"发送控制命令"页覆盖（`config.setTank`/`setProbe` 走原始 JSON 参数，理由同 B-2：
   参数形状本就没定，不该由客户端替它定）。
4. **`/api/oil-in-out` 的 502 与 `legacySkipped`**：**已做**——小程序的 `ApiError` 现在携带完整
   响应体，`device-detail.ts` 在 502 时仍渲染 `rows` 里能解析的部分，并在列表上方给出
   `unparsable`/`legacySkipped` 的中文提示，而不是把它压成一句 toast。

### 云端为什么没有采用"版本化端点"

复审给的另一条路是版本化端点或兼容响应。没有采用，理由是：小程序在**行内字段**上已经无法兼容
（旧列来自已删除的表，无法重建），所以一个只在信封形状上兼容的 `v1` 端点仍然无法被现有代码正常
消费——它会把维护成本变成两份，却换不到任何一个能跑起来的客户端。真正的解法是上面第 1 条的字段
迁移，那需要小程序负责人排期，不是云端能单方面完成的。

**若复审认为在拿到确认之前不应合并本 PR，云端同意。** 本节的作用是把"需要谁确认什么"写清楚，
而不是替代确认。

### D-租户. 多租户带来的 REST 变更 —— **已核对，无需改动（2026-10-06）**

openspec 变更 `implement-multitenant-support-with-customer-isolation` 的阶段 1 改了以下云端行为（该变更已于
2026-10-06 核对并归档）。

| 接口 | 变化 | 是否破坏 |
|---|---|---|
| `GET /api/devices` | `platform_admin` 返回**全部**设备（原：仅已绑定）；`customer_admin` 返回本客户全部设备（原：仅已绑定）。客户账号看到的行字段不变，**刻意不暴露** `customer_id`；`platform_admin` 的行多一个 `customer_id`（未分配为 null），供管理后台显示设备归属（`yeweiyi-admin` 的 `/ops/devices`） | 语义变化 |
| `POST /api/devices/bind` | 新增拒绝情形：设备属于其他客户或未分配，返回与"密钥错误"完全相同的 401 | 否（只多一种失败） |
| `POST /api/devices/:device/pairing-secret`（仅 `platform_admin`，安装工具） | 请求体新增**必填** `{"customerId": N}`；设备已属于别的客户返回 409 | **是**：不带 body 的旧调用得 400 |
| `GET /api/audit` | `customer_admin` 可不带 `device`；`platform_admin` 可选 `?customerId=` | 否（只放宽） |

阶段 2 另外**新增**了一组路由，全是新路由，不影响现有页面，列在这里只为让负责人知道它们存在：
`POST/GET /api/customers`、`PUT/DELETE /api/customers/:id`、`POST/GET /api/users`、
`PUT /api/users/:id`（停用/启用/重置密码）、`PUT /api/users/:id/customer`、
`PUT /api/devices/:device/customer`。被停用的用户登录得 401，已签发的 token 访问设备得 403。

**核对结论（2026-10-06）**。小程序与云端是同一个负责人，所以这里的"确认"就是对照两个客户端的代码，
结果如下，三处都不需要改客户端：

| 变化 | 核对对象 | 结论 |
|---|---|---|
| `GET /api/devices`：管理员现看到全部 / 本客户全部设备 | 小程序 `pages/devices/devices.ts` 读 `{rows: Device[]}`，用到 `device_name`、`is_online`、`last_online_at`、`last_offline_at` | 字段名未变，兼容。变化只影响管理员，普通客户用户看不出区别。**但字段曾长期无人写入**（旧写入逻辑随 Alink 栈删除），2026-10-06 起改由 `internal/ingest` 按 ThingsBoard 的 `active` 标志定时同步；语义变为"不活动超时内有过活动"，设备掉线后要等该超时（默认 10 分钟）才显示离线，小程序（`bbaaef0`）**刻意不显示 `is_online`**（它知道该列无人维护，见其 `types/api.d.ts`），只显示 `last_online_at`；现在该列有真实值，是否启用在线状态展示由小程序负责人决定，且要接受上述离线延迟 |
| 设备行不暴露 `customer_id` | 两个仓库全文搜索 `customer_id` / `customerId` | 无人读取，保持不暴露 |
| `pairing-secret` 必填 `customerId` | 小程序不调它（只调 `bind`）；`yeweiyi-admin` 的"配对密钥"页面目前只是只有标题的空壳 | 没有现存调用方会坏。实现该页面时请求体必须带 `{customerId}` |

另有一件**与本次变更无关**、但同样会让小程序对不上云端的事：小程序 `main` 的扫码绑定只发
`{ deviceName }`，没有 `pairingToken`，从 PR #11 起就被云端拒绝。修复已在远端分支
`origin/rebuild/thingsboard-single-stack`（`a34afc4`，尚未合入 `main`），不属于本变更的范围，记在这里以免被遗忘。

---

## 建议的推进顺序

1. ~~先验 A-1~~ **A-1 已实测，结论成立**：第二条 RPC 响应云端收不到，控制闭环在真实设备上是断的。
   因此设备端补上 `cmdResult` 遥测上行是第一优先，比所有改名都靠前。
2. A-3 遥测结构迁移（结论已定，是执行）。
3. A-2 / A-4 / A-5 的改名与错误码取并集，**一批做完**——分批改名会让两侧多经历几次不兼容窗口。
4. 接入 C 的共用 fixture，此后任一侧改动都会让双方构建变红。
5. A-6 与 B 各条按各自的决定人推进，不阻塞 1–4。

## E. 油罐配置镜像（cloud#70，第一阶段）—— 待设备端同步

云端已按设备端 `TankSettingsAttributeBuilder` / `远程配置下发-字段清单.md` 的键名消费客户端属性
`tank{N}.*`（契约见 [tank-settings-contract.md](tank-settings-contract.md)）。这是**可选、向后兼容**的消费，
**Refs 而非 Closes** 设备端 PR #38；字段名、单位、形状仍以设备端确认为准。待办：

| 项 | 需要谁 |
|---|---|
| 把 `fixtures/protocol/v1/client_attributes_tank_settings.json` 同步进设备端 fixture，并让其测试读它 | 设备端 |
| `density` 单位（设置页标 kg/m³，实际存 0.56）：云端原样透传，不声明单位 | 现场 / 产品 |
| 确认 `oilTypeIndex` 1–17 内置名称由谁维护（云端不内置） | 小程序 / 设备端 |
| 真机验证：属性是否在连接与变更时按预期上报（零真机验证） | 设备端 |

**#71 追加（下发配置）：** 云端新增方法 `config.setTankSettings`（admin）、`config.setDeviceSettings`（仅平台管理员），
args 与 cmdResult 的共享 fixture 为 `rpc_request_set_tank_settings.json`、`cmd_result_set_tank_settings.json`，同样待设备端同步
并让其测试读取。另：设备端字段清单把结果载荷写成 `data`，但 `RpcWirePayloads.cmdResult` 和云端协议都是 `detail`——请设备端
订正文档。真机验证清单见 [tank-settings-device-verification.md](tank-settings-device-verification.md)。

**#14 追加（标定表）：** 云端已消费 `tank{N}.calibrationHeightMm` / `calibrationVolumeL`（镜像表 `tank_calibration`，
`GET /api/devices/:device/tanks/:tank/calibration`，见 [tank-settings-contract.md](tank-settings-contract.md)）。仍待设备端确认：
数组形状/单位；设备只在 MQTT 重连时重发（现场改表后云端滞后到下次重连）；是否需要变更时主动重发。

## F. 交班、库存记录与介质字段（cloud#85 / zhongchengyeweiyi#49）—— 两端已实现，待真机验证

契约见 [shift-stock-records-contract](shift-stock-records-contract.md)（v0.3）。新增的协议内容（产品 2026-11-03 决定，设备端会话已同意形状）：

- 新事件 `shiftEvent`、`stockRecord`（§2.1/§2.2），走设备端独立 outbox，不占报警补投队列。
- 既有事件新增**可选、向后兼容**字段：`stockEvent.deliveredWeightKg`、`stockEvent.oilTypeIndex`、`alarm.oilType`、`alarm.oilTypeIndex`。
- 共享 fixture 已由设备端起草、云端收录（2026-11-03，8 份，见 MANIFEST），两端已按其实现并测试。仍待：设备端分支合并、
  真机联调；Android 侧 fixture 仍是副本，未接入校验和门禁（同 §C-1）。
