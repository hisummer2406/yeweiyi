# 设备协议 V1 对账：云端与 Android 并行实现的差异

**状态：待双方确认。** 2026-09-01 整理。

## 发生了什么

两个仓库在同一天各自实现了"ThingsBoard 单栈设备协议 V1"，**彼此不兼容**。这不是谁做错了——
两边的 fixture 注释都写着同一句话：

> 云端 `fixtures/protocol/v1/`：*"cross-repo protocol fixtures, shared with the Android repo"*
> Android `app/src/test/resources/thingsboard/telemetry_snapshot.json`：
> *"跨仓库协议 V1（yeweiyi-cloud#7）尚未冻结，也没有可复用的 fixture——这份是 Android 侧按
> ThingsBoard 官方设备 telemetry API 惯例自行定义的，协议冻结后需要回来对齐字段名/单位。"*

双方都知道对方存在、都在等协议冻结、都因为等不到而先自行定义了一套。**yeweiyi-cloud#7 的验收条件
"协议冻结后再开始生产代码迁移"没有被遵守**，两边都已经写了生产代码和测试。

现状：按今天的两份实现，**设备发出的遥测云端一个字段都解析不出来**。

相关提交：
- Android：`d9796b0` `3175a52` `272d433` `94fed27`（2026-09-01，已删除 Alink 协议）
- 云端：`65d13b1` 起的 `feat/thingsboard-single-stack` 分支

---

## 已经一致的部分（不需要讨论）

这些是双方独立做出的相同选择，说明底层判断是一致的：

| 项目 | 状态 |
|---|---|
| MQTT topic（`v1/devices/me/telemetry` / `attributes` / `rpc/request/+` / `rpc/response/{id}`） | ✅ 完全一致（ThingsBoard 原生，本无选择余地） |
| RPC 信封嵌在 `params` 内，`method` 留给 ThingsBoard 审计 | ✅ 一致 |
| `commandId` / `operator` / `reason` / `args` 四个字段名 | ✅ 一致 |
| 命令状态机语义（received/rejected/executing/succeeded/failed/expired） | ✅ 集合一致（大小写不同，见下） |
| 幂等（重复 commandId 不重复动作）、有效期（到达即过期则拒绝） | ✅ 一致 |
| **高风险物理动作默认关闭** | ✅ 双方各自独立实现（Android `FEATURE_DISABLED` + issue #34；云端 `AllowPhysicalCommands` 默认 false） |
| 报警立即上报、不等 30 秒周期 | ✅ 一致 |
| 上线/离线交由 ThingsBoard 会话管理，设备不上报 | ✅ 一致 |

`reason`（二次确认原因）两边都做成了必填并一路带到审计——这条最容易被当成可选字段砍掉，两边都没砍。

---

## 差异清单

### A. 遥测结构 —— 最大的一处，其余都是改名

| | 云端 | Android |
|---|---|---|
| 罐数据 | 扁平键 `tank1.oilLevelMm` | 嵌套数组 `tanks: [{id, oilLevel, ...}]` |
| 单位 | 写在键名里（`...Mm` / `...L` / `...Kg` / `...C` / `...Pct`） | payload 内单独一个 `units` 对象 |

**建议：采用扁平键。理由是功能性的，不是口味问题。**

ThingsBoard 的聚合、TTL、趋势图部件都以「一个数值型 timeseries key」为单位工作。`tanks` 作为一个
JSON 数组存进 `ts_kv` 后是**一个** key，ThingsBoard 无法对 `tanks[0].oilLevel` 画曲线、做降采样或
按 key 设保留期——而 issue #1 明确要求"历史趋势"和"7 天后删原始明细、保留分钟/小时级汇总"。
用嵌套结构，这些都得由 Go 侧把每个点取回来再拆，等于把 ThingsBoard 的时序能力废掉一半。

单位写进键名的额外好处：`units` 对象每 30 秒重复上报 6 个键的常量，而且**可能与实际数据不一致**
（改了单位忘了改 units 就成了错误元数据）；写进键名则结构上不可能不一致，且 ThingsBoard 仪表盘
直接拿键名当曲线标签。

> ⚠️ 这条建议依据的是"ThingsBoard 不能对 JSON 值内部字段画曲线"。此断言来自其 timeseries API 的
> 行为（值一律字符串化返回，数值聚合针对标量键），**云端未专门做过嵌套 JSON 画图的实测**。如果
> Android 侧对此有异议，这是唯一需要先跑实验再拍板的点。

### B. 遥测字段

| 语义 | 云端 | Android | 建议 |
|---|---|---|---|
| 油位 | `oilLevelMm` | `oilLevel`（mm） | 云端 |
| 水位 | `waterLevelMm` | `waterLevel`（mm） | 云端 |
| 油温 | `tempC` | `temp`（C） | 云端 |
| 油体积 | `oilVolumeL` | `oilVolume`（L） | 云端 |
| 水体积 | `waterVolumeL` | `waterVolume`（L） | 云端 |
| 油重量 | `oilWeightKg` | `oilWeight`（kg） | 云端 |
| 罐号 | `tank{n}` 前缀 | `id` 字段 | 随 A 的结论 |
| 探针通信 | `probeConnected` | `probeConnected` | ✅ 已一致 |
| 油品 | `oilType` | `oilType` | ✅ 已一致 |
| 报警状态 | `alarmState`：`NORMAL`/`ALARM`/`FAULT` | `alarmState`: `null` | 需定义取值 |
| 油/水占比 | `oilRatioPct` / `waterRatioPct` | 无 | 云端有，确认设备能否提供 |
| 空容积 | `nullVolumeL` | 无 | 同上 |
| 快照序号 | `snapshotSeq` | 无 | 建议保留（丢帧检测） |
| 罐数 | `tankCount` | 无 | 随 A 的结论 |
| 探针地址 | 客户端属性 | 遥测内 `probeAddress` | **建议属性**：它只在改配置时变，放遥测等于每天存 2880 次常量 |
| 设备时间 | 只用信封 `ts` | 另有 `deviceTime` 重复 | **建议只留信封 `ts`**，两个时间源必然有一天对不上 |
| 连接状态 | 无（ThingsBoard 管） | `connectionState: "online"` | **建议删除**：设备自报"我在线"没有信息量，离线时它正好发不出来 |

**单位本身（mm / L / kg / °C / 0–100%）双方一致**，这是最重要的——只是表达方式不同。

### C. 报警事件

| 语义 | 云端 | Android |
|---|---|---|
| 遥测键 | `alarm` | `alarmEvent` |
| 事件 ID | `alarmId`（UUID） | `eventId`（`"2-1"` = 类型-罐号） |
| 状态 | `status`: `ACTIVE` / `CLEARED` | `state`: `raised` / `recovered` |
| 报警类型 | `type`: `HIGH_HIGH_LEVEL` 等字符串枚举 | `alarmType`: `2` 整数码 |
| 罐号 | `tank` | `oilCanId` |
| 严重度 | `severity`（ThingsBoard 枚举） | 无 |
| 报警文本 | `text`（设备上显示的中文） | 无 |
| 时间 | `tsMs` | `deviceTime` |

**一处需要一起决定的取舍：`eventId` 的唯一性。** Android 侧的 `AlarmEventBuilder` 注释写明这是
刻意的设计——*"报警产生和恢复用同一个 eventId（按 类型+油罐ID 派生，确定性、无需额外状态）"*。
这个取舍是成立的：设备不必为每次报警保存一个生成的 ID，掉电重启后也能算出同一个值，对嵌入式端
是实打实的好处。

代价是 `eventId` 在时间上不唯一：同一罐同一类型的**两次**报警拿到相同的 ID。云端靠 ID 把 `ACTIVE`
与 `CLEARED` 配对，所以断网补传时若两次同类报警的事件交错到达，恢复事件可能关掉的是上一次那条。
云端的 `alarmId` 用 UUID 正是为了避开这个，代价是设备要存一份未恢复报警的 ID。

折中方案（建议）：保留派生式，但加上本次报警的**起始时间**，例如 `"2-1-1735689600000"`。仍然是
确定性的、不需要长期状态（只需记住当前这次报警的起始时刻，而设备为了判断"是否已恢复"本来就得
记），同时在时间上唯一。

`alarmType` 整数码 vs 字符串枚举：建议字符串。整数码需要双方各自维护一张对照表，从 broker 里
dump 一条报文时 `"alarmType": 2` 看不出是什么；`"HIGH_HIGH_LEVEL"` 自解释。

`severity` 建议保留并直接照抄 ThingsBoard 自己的枚举拼写（`CRITICAL`/`MAJOR`/`MINOR`/`WARNING`/
`INDETERMINATE`），这样规则链能原样透传，不需要翻译表。

### D. RPC 信封 —— 差异最小

| 语义 | 云端 | Android | 建议 |
|---|---|---|---|
| 协议版本 | `protocolVersion: 1`（整数，且只在客户端属性里） | `protocolVersion: "tb-v1"`（字符串，每条报文都带） | **取 Android 的"每条报文都带"**（从 broker dump 一条就能判断版本），取值建议简化为 `"v1"`——现在全部是 ThingsBoard，`tb-` 前缀是冗余的 |
| 下发时间 | `issuedAtMs` | `issuedAt` | 任选，建议带 `Ms` 后缀与遥测的单位后缀风格一致 |
| 过期时间 | `expiresAtMs` | `expireAt` | 同上 |
| 方法名 | `config.setAlarmThresholds` | `setAlarmThreshold` | 建议带点号命名空间（`pump.*` / `valve.*` / `alarm.*` / `config.*`），方法会变多，且 ThingsBoard 审计日志里按前缀好筛 |
| 状态取值 | 小写 `received` | 大写 `RECEIVED` | 任选，定一个 |

**错误码：需要合并，两边各有对方没有的。**

| 云端 | Android |
|---|---|
| `UNKNOWN_METHOD` ✅ | `UNKNOWN_METHOD` ✅ |
| `INVALID_PARAMS` ✅ | `INVALID_PARAMS` ✅ |
| `EXPIRED` ✅ | `EXPIRED` ✅ |
| `INTERLOCK_BLOCKED`（联锁拒绝） | `FEATURE_DISABLED`（高风险未开放） |
| `BUSY` / `NOT_SUPPORTED` / `HARDWARE_FAULT` / `LOCAL_MODE_LOCKED` | `PROTOCOL_VERSION_UNSUPPORTED` / `MALFORMED_PARAMS` / `INTERNAL_ERROR` |

注意 `INTERLOCK_BLOCKED` 与 `FEATURE_DISABLED` **不是同一件事**，两个都要保留：前者是"这台设备
此刻的物理状态不允许"（液位太低不能启泵），后者是"这个功能整体没开放"（联锁未验证）。前者可能
换个时刻就允许了，后者不会。

---

## 尚未有任何一方实现的：出入库与交班

**双方都没有 `OilInOut`（出入库）和 `CommitWork`（交班）的上行通道。** 已确认：Android 侧的
`logic/thingsboard/` 与 `ThingsBoardManager` 中没有任何相关代码，云端 `internal/protocol` 也没有。

旧 Alink 协议里这两个是**轮询式查询**（`requestUnloadOil` / `requestShift`：云端发请求、设备回一页
记录），V1 没有保留这种形态——V1 是设备主动上报。

设备侧这两张表的字段（来自 `db/OilInOut.java` / `db/CommitWork.java`，已核对）：

```
OilInOut:    id, oilId, jieZhi, time, beginHeigth, endHeigth,
             beginVolum, endVolum, beginWeight, endWeight
             （beginHeigth/beginVolum 是设备侧原有拼写，非笔误）
CommitWork:  id, workId, time, endTime, info
             （info 是一个已序列化的 JSON 字符串）
```

**影响：** 云端 `/api/oil-in-out` 与 `/api/shifts` 目前仍读旧 MQTT 摄取才会写的表，单栈部署下永远
返回空。这是删除旧 Alink 栈前**最后一个阻塞项**——删掉之后这两个端点能编译、能返回 200，但永远
没有数据，这种"看着正常其实没数据"比编译错误难发现得多。

**需要产品决定，不是技术选型：**

1. 出入库/交班是否属于单栈的启动范围？如果暂不需要，云端直接删掉这两个端点，旧栈即可整体删除。
2. 如果需要，建议做成**事件上报**而非查询：记录产生时立即推一条（与报警同样的即时事件机制），
   而不是把旧的分页查询语义搬过来——V1 的整体形态是设备主动上报，再引入一条请求/响应式通道会
   让协议出现两种不同的交互模型。
3. `CommitWork.info` 存原样 JSON 字符串还是解析成结构化字段？（这条在 CLAUDE.md
   "Open product decisions" 里从设计阶段挂到现在，一直没定。）

---

## 建议的下一步

1. **先冻结 A（扁平 vs 嵌套）**，其余全是改名，改名可以批量做；结构定不下来则其余讨论无意义。
2. 定稿后**双方共用同一批 fixture 文件**，而不是各自维护一份。这正是 yeweiyi-cloud#7 的原意，
   这次没做到才导致了本文。具体做法建议：以 `yeweiyi-cloud/fixtures/protocol/v1/` 为准，Android
   侧通过 git submodule 或构建期拷贝引入，任一侧改名会同时让两边的构建变红。
3. 出入库/交班单独走产品决策，不阻塞上面两步。
4. **在此之前不要再往任一侧的协议实现里加新字段**——现在停手改名成本还小。

## 引用

| 内容 | 位置 |
|---|---|
| 云端协议规范（含第 12 节待确认清单） | `yeweiyi-cloud/docs/protocol-v1.md` |
| 云端实现 | `yeweiyi-cloud/internal/protocol/` |
| 云端 fixture | `yeweiyi-cloud/fixtures/protocol/v1/` |
| Android 实现 | `zhongchengyeweiyi/app/src/main/java/com/sansen/yeweiyi/logic/thingsboard/` |
| Android fixture | `zhongchengyeweiyi/app/src/test/resources/thingsboard/` |
