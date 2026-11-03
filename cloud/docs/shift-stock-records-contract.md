# 交班、库存记录同步与五类记录展示：契约（v1.0，两端已实现，未真机验证）

状态（2026-11-03）：**设备端与云端均已实现，只在 JVM 单测 / 云端真实数据库 + ThingsBoard 测试中验证，未做真机联调；
小程序未接入。** 设备端实现见 [zhongchengyeweiyi#49](https://github.com/hisummer2406/zhongchengyeweiyi/issues/49)
（分支 `fix/shift-record-defects`）；共享 fixture 已收录到 `fixtures/protocol/v1/`（8 份，设备端起草，以云端副本为准）。
协议 V1 整体仍未冻结（open-items §F）。
主需求：[cloud#85](https://github.com/hisummer2406/yeweiyi-cloud/issues/85)。配套：
[miniapp#16](https://github.com/hisummer2406/yeweiyi-miniapp/issues/16)、[#17](https://github.com/hisummer2406/yeweiyi-miniapp/issues/17)；
筛选沿用 [records-filter-contract](records-filter-contract.md)（#84）。
标注：**【现状】**（已核对的设备端代码事实）**【提议】**（云端建议，需设备端/客户端确认）**【需求】**（#85 的硬要求）
**【待确认】**（附决定人）。

设备端事实核对于 `zhongchengyeweiyi` `main` `9669d90`（2026-11-03），设备端会话已复核并更正（v0.2）。注意：该 `main` 上**已经有** ThingsBoard 代码
（`logic/thingsboard/`、`manager/ThingsBoardManager.java`），出入库 `stockEvent` 已按
[protocol-v1-stock-event](protocol-v1-stock-event.md) 结构化上报。

## 1. 设备端现状

### 1.1 交班（`db/CommitWork` + `bean/OilWork`，`repository/ShiftRepositoryImpl`）

- 【现状】显示器上「开始交班」（`MainActions.but_startJiaoBan`）新增一行 `CommitWork`：`time` = 当前时间，`info` =
  每罐一个 `OilWork` 的 JSON 数组，只填 `oilId`、`beginHeight`、`beginVolume`（由当前罐表换算）。
- 【现状】「结束交班」（`but_endJiaoBan`）取 **id 最大的一行**（不论它是否已结束）写 `endTime` 与每罐 `endHeight`、
  `endVolume`、`shellVolume = beginVolume − endVolume`、`shellWeight = shellVolume × 当时罐设置的密度`。
- 【现状】界面标签：`time` = 「开始时间」/接班高度，`endTime` = 「结束时间」/交班高度。即 `time` 是**接班**，`endTime` 是**交班**。
- 【现状】已知缺陷，上报前必须由设备端处理（否则云端会如实存下错误数据）：
  1. `workId`（班号）现行代码从不赋值，恒为 0——**没有班号可同步**。
  2. 时间只到分钟（`DateUtils.getYYYMMddHHmmss()` 实际是 `yyyy-MM-dd HH:mm`），无时区。
  3. 结束交班按 `oilWorks.get(key − 1)` 取开始数据，罐号不连续时会**串罐**；下标取不到时新建空对象，开始值为 0，
     于是 `shellVolume = −endVolume`（负值）；开始时有、结束时已删除的罐整行消失（只遍历结束时的罐）。
  4. `OilWork` 字段是基本类型 `double`，进行中的班序列化后 `endHeight`/`endVolume`/`shellVolume` 是 `0.0`，与真实 0 无法区分。
  5. 对已结束的班再「结束交班」会覆盖其结束数据。正常操作触发不了（结束后按钮隐藏）；有删除交班权限者删掉进行中的班后
     再点结束即可触发。
  6. `OilWork.jieZhi`（介质）从不赋值，交班记录**没有历史介质**；显示器交班详情与 Excel 导出用的是**当前**罐设置的介质。
  7. 出库量是起止容积差；班内若有进油，差值不等于销量（可能为负）。

### 1.2 库存（`db/StockSave`，`manager/SaveOilManager.SaveOilDB()`）

- 【现状】触发：库存页手动保存（`StockFragment` 的 `ly_saveStock`），以及设置了「库存生成时间」（`StockTime`，单位小时，
  0 = 关闭）时按周期自动保存（`MainActivity.refreshData`）。每次为**每个罐**各存一行。
- 【现状】字段：`oilId`（罐号）、`time`（`yyyy-MM-dd HH:mm`，无时区）、`jieZhi`（**保存当时**的介质）、`height` 液位、
  `waterheigth` 水高、`volum` 容积、`temperature` 温度、`weight` 质量。单位与 30 秒快照同源（`OilCan`）：mm、mm、L、℃、kg。
- 【现状】没有批次标识；同一分钟内手动保存两次得到时间完全相同的两组行。周期保存的起点是进程启动后第一次读数，
  重启后重新计时，不对齐整点。
- 【现状】介质 `jieZhi` 是按**界面语言**取的自定义显示名（`DataUtils.getOilType()[i]`），不是稳定代码；切换语言后同一油品
  字符串不同。`stockEvent.oilType` 有同样问题。
- 【现状】`SaveOilDB(String remark)` 写的是另一张旧表（`saveSaveOilRecord`），不在本契约范围。

### 1.3 上报通道

- 【现状】出入库与报警走 `ThingsBoardManager` 的即时事件 + 持久化补投队列（`PendingAlarmStore`，上限 200 条，按设备身份分文件）。
  交班与库存**目前不上报**；升级前的本地历史也从未离开设备。

## 2. 上报协议（【提议】，字段名待设备端确认）

两类都是 ThingsBoard telemetry 即时事件，复用出入库的信封与补投队列，硬要求与
[protocol-v1-stock-event §3](protocol-v1-stock-event.md) 相同：**信封 `ts` = 设备生成该报文的时刻，补投原样保留。**
所有时间为毫秒 UTC epoch；设备端由本地时间按设备时区换算，新产生的记录精确到秒。

### 2.1 `shiftEvent`：每班一条，开始与结束各报一次

```json
{"ts": 1762150000000, "values": {"shiftEvent": {
  "shiftId": "shift-1762146400000",
  "status": "OPEN",
  "beginTimeMs": 1762146400000,
  "endTimeMs": null,
  "tanks": [
    {"tank": 1, "oilType": "92#", "beginLevelMm": 1520.0, "beginVolumeL": 18230.5,
     "endLevelMm": null, "endVolumeL": null, "outVolumeL": null, "outWeightKg": null}
  ]
}}}
```

- 【设备端同意】`shiftId`：设备生成、终身不变，`shift-{beginTimeMs}`；结束报文必须沿用同一个值。设备端在 `commitwork`
  新增一列存开始时刻的 epoch 毫秒，不从本地时间字符串重算（否则开始与结束之间改时区会让 `shiftId` 变化）。
- 【提议】`status`：`OPEN`（开始时上报）| `CLOSED`（结束时上报，补齐 `endTimeMs` 与各罐结束数据）。
  同一 `shiftId` 多次上报时，**信封 `ts` 最大的一份胜出**（与 `stockEvent` 的折叠规则一致），所以对已结束的班重复按「结束」
  会以最新一次为准——这是设备本地的事实，云端不纠正。
- 【需求】进行中或缺失的值必须是 `null` 或省略，**不得是 0**。设备实际一律**省略键**（fixture 照此），读方把缺键当 null（对应 §1.1 缺陷 4）。旧版本库里进行中的班结束字段是 `0.0`，
  上报层按「`endTime` 为空 ⇒ 结束字段全部为 `null`」处理。
- 【需求】`tanks[]` 按罐号匹配开始/结束数据（修 §1.1 缺陷 3）；只有开始或只有结束数据的罐也要出现，缺的一侧为 `null`。
- 【提议】`oilType`：**开始交班时**该罐的介质（设备端需新增记录，§1.1 缺陷 6）；没有则省略，云端显示「—」，
  **不得**用当前罐设置补。
- 【提议】`outVolumeL`、`outWeightKg`：按设备现行口径（`begin − end`、`× 结束时刻密度`）由设备计算后上报，
  云端只存不算。字段说明里写明「班内有进油时不等于销量」。
- 【已决定 2026-11-03】不要班号：协议里不出现 `workId`，同一班以 `shiftId` 与起止时间区分。

### 2.2 `stockRecord`：每次保存一条，携带全部罐

```json
{"ts": 1762153200000, "values": {"stockRecord": {
  "recordId": "stock-1762153200000",
  "trigger": "SCHEDULED",
  "tsMs": 1762153200000,
  "tanks": [
    {"tank": 1, "oilType": "92#", "levelMm": 1498.0, "volumeL": 17960.2, "waterLevelMm": 12.0,
     "tempC": 18.6, "weightKg": 13290.5}
  ]
}}}
```

- 【设备端同意】一次保存一条消息（多罐），`recordId` = `stock-{tsMs}`，`tsMs` 精确到秒；云端按 `(recordId, tank)` 拆行。
  同一秒的两次保存（手动 + 周期）折叠为一条，`trigger` 取胜出的那份。
- 【提议】`trigger`：`SCHEDULED`（周期）| `MANUAL`（手动保存）；可选，未知时省略。
- 【需求】只上报设备**已生成**的库存记录；云端绝不用快照或小时汇总补造库存记录。
- 【需求】`oilType` 是保存当时的介质（设备本来就存了 `jieZhi`）；缺失时省略，不填当前值。

### 2.3 上报通道（设备端方案，云端同意）

- 持久化 outbox（`AtomicJsonFile`），只记「哪条记录要报」与「报文生成时刻」，不存载荷；发送时按数据库里该行的**最新内容**组装，
  信封 `ts` 用条目记下的生成时刻。同一班多个未发条目合并成一条、取最大 `ts`。
- 写入点：开始交班、结束交班、显示器上手工修改交班（`UpdateCommitDialog`）、库存保存。手改与结束都会让整班以更大的 `ts` 重报，
  云端按「`ts` 最大者胜」折叠。
- 不占用报警/出入库共用的 200 条补投队列。
- 【已决定】显示器本地删除交班/库存记录**不同步**，云端保留（业务记录永久保存，同约束 5）。

### 2.4 升级前的本地历史

- 【已决定 2026-11-03】**都不传**：只同步升级后产生的新记录。客户端对没有记录的早期时段不得显示成「设备没有交班/库存」，
  见 §4 `syncStatus`。以下原提议作废，仅留作背景。

- 【需求】#85 要求同步显示器上**已有**的交班/库存记录。这些记录只在设备本地，ThingsBoard 里没有。
- （作废）交班与库存**不进**报警/出入库共用的 200 条补投队列（队列满时会淘汰旧的出入库事件与报警），
  改走独立通道：按本地 id 升序读 `commitwork` / `StockSave`，按 §2.1/§2.2 映射后限速上报，进度游标持久化（断电续传）。
  新记录也走这条通道（游标追上最新行即为实时），新旧共用一套机制。交班的结束会更新已上报过的行，通道须能重发该班
  （例如游标之外再记「已结束未重报」的班）。设备端估计 1.5–2 人日。旧记录时间只到分钟、无时区，按设备当前时区换算，
  在载荷里带 `"precision": "MINUTE"` 与 `"legacy": true` 标记；旧交班没有介质，且串罐无法事后识别（旧代码把别的罐的
  开始数据改了罐号存回），开始值为 0 的行与真实 0 无法区分——只有罐号连续且班内未增删罐时可信。是否展示由产品决定。

## 3. 云端存储（已实现，迁移 000037）

与 `stock_events` / `alarm_events` 同构：`internal/ingest` 实时订阅 + 定时回读 ThingsBoard，幂等写入，业务库永久保存。

| 表 | 主键 | 说明 |
| --- | --- | --- |
| `shift_records` | `(device_name, shift_id)` | 表头：状态、接班/交班时刻、胜出报告的 `env_ts` 与原始 `payload`、首次写入时的 `customer_id` |
| `shift_record_tanks` | `(device_name, shift_id, tank)` | 每罐一行（该罐原始对象），供按罐授权、筛选、分页 |
| `stock_records` | `(device_name, record_id)` | 表头：库存生成时刻、`env_ts`、`payload`、`customer_id` |
| `stock_record_tanks` | `(device_name, record_id, tank)` | 每罐一行 |

- 同一记录信封 `ts` 最大的一份胜出（相同则保留先写入的）；每次写入后罐行与表头载荷对齐（`Sync*RecordTanks`），
  中断或并发写入都在下一次写入（含回补重放）时收敛。
- 实时订阅与定时回读（`RecordReconcileLookback`，默认 8 天）都覆盖 `shiftEvent`/`stockRecord`；`cmd/backfill` 同样覆盖。
  回补完成标记 `record_backfills` 是在这两类出现之前就有的，已标记的设备不会为它们再从头回读——它们只随新固件出现，
  回看窗口会覆盖；需要全量时运行 `cmd/backfill`。

- 【已实现】四张表已加进设备彻底删除清单（`db/queries/device_purges.sql` 与 `CountDeviceRemnants`），写入语句经
  `device_record_writable` 判定（#83）。
- 【已实现】无法解析的原文进 `record_rejects`（`kind` = `shift` / `stock_record`），接口计入 `unparsable`，不静默丢弃。

## 4. 查询接口（已实现）

```
GET /api/shift-records?device=&tank=&beginTime=&endTime=&limit=&offset=
GET /api/stock-records?device=&tank=&beginTime=&endTime=&limit=&offset=
```

- 【需求】`device` 必填（完整设备编号）；`tank` 可选，语义、校验与错误码同 #84（`invalid_tank`、`tank_not_authorized`）。
  授权、客户归属时间窗、按罐过滤、筛选全部在 SQL 的 `LIMIT/OFFSET` 之前生效；员工只见被授权的罐。
- 【需求】必须登记到 `internal/api/tenancy_test.go` 的 `deviceScopedRoutes`。
- 交班按 `(shift, tank)` 一行返回，带 `shiftId` 以便客户端把同一班的行归组；时间窗按**接班时间**（`beginTimeMs`）匹配，
  进行中的班也返回，`status: "OPEN"`、结束字段为 `null`。
- 库存按 `(recordId, tank)` 一行返回，时间窗按 `tsMs` 匹配。
- 其余参数（`beginTime`/`endTime` 默认最近 24 小时、`limit` 1–500 默认 50、`offset`、`sort` 布尔默认升序）与
  `/api/oil-in-out` 相同。
- 行字段（缺失一律 `null`）：
  - 交班：`shiftId` `status` `beginTime`（接班）`endTime`（交班）`tank` `oilType` `oilTypeIndex` `beginLevelMm` `beginVolumeL`
    `endLevelMm` `endVolumeL` `outVolumeL` `outWeightKg`
  - 库存：`recordId` `time` `trigger` `tank` `oilType` `oilTypeIndex` `levelMm` `volumeL` `waterLevelMm` `tempC` `weightKg`
- `/api/oil-in-out` 行新增 `deliveredWeightKg`、`oilTypeIndex`；`/api/alarms` 行新增 `oilType`、`oilTypeIndex`（均可为 `null`）。
- 响应信封：

```json
{"rows": [...], "truncated": false, "partial": false, "unparsable": 0,
 "syncStatus": "SYNCED"}
```

  `syncStatus`：`UNSUPPORTED`（该设备从未上报过此类记录，客户端显示「设备暂不支持」）| `SYNCING`（初次回补未完成）|
  `SYNCED`。客户端只有在 `SYNCED` 且 `rows` 为空时才能显示「暂无记录」。
- 【选择】`UNSUPPORTED` 按「该设备是否上报过任一该类记录（含读不出来的原文）」判定，不需要设备端新增属性。代价：新固件的
  设备在第一条交班/库存记录到达前显示「设备暂不支持」。【待确认·小程序】该文案是否可接受。
- `unparsable` 是整机计数（原文无法归属到罐），员工也能看到计数但看不到原文。

## 5. 五类记录的字段口径（对照 #85「设计要求字段顺序」）

| 设计列 | 来源 | 可用性 |
| --- | --- | --- |
| 入/出库「介质」 | `stockEvent.oilType`（检测当时） | 已有 |
| 入/出库「前高/前量/后高/后量」 | `beginLevelMm` `beginVolumeL` `endLevelMm` `endVolumeL` | 已有 |
| 入/出库量(L) | `deliveredVolumeL`（云端算：净变化 `netVolumeL` + 边卸边卖核对的期间销量；未核对时等于净变化） | 已有 |
| 入/出库量(kg) | 【已决定】设备端新增可选 `stockEvent.deliveredWeightKg`：设备按记录关闭时刻的密度、对其交付体积计算（`beginWeightKg`/`endWeightKg` 之差含期间销售，不能代替）；边卸边卖核对后随修正版重报 | 新固件起有；旧记录 `null` 显示「—」 |
| 报警「介质」 | 【已决定】设备端新增可选 `alarm.oilType` + `alarm.oilTypeIndex`（报警发生时） | 新固件起有；设备级报警与旧记录 `null` |
| 报警「状态/级别」 | 现有 `status` `severity` | 保留，客户端不得因视觉精简丢掉 |
| 库存全部列 | §2.2 | 依赖设备端上报 |
| 交班「交班时间」 | `endTimeMs`（界面「结束时间」） | 依赖设备端上报 |
| 交班「接班时间」 | `beginTimeMs`（界面「开始时间」） | 依赖设备端上报 |
| 交班「出库量(L/kg)」 | `outVolumeL` / `outWeightKg` | 依赖设备端上报；班内有进油时不等于销量 |
| 交班「介质」 | `tanks[].oilType` | 依赖设备端新增；旧记录无 |

- 【需求】库存「液位/水高」单位是 **mm**（设计截图标成毫升有误）。
- 【已决定 2026-11-03】凡带 `oilType` 的载荷（`shiftEvent.tanks[]`、`stockRecord.tanks[]`、`stockEvent`、`alarm`）同时带可选
  `oilTypeIndex`（整数，设备油品表下标，与界面语言无关）。`oilType` 照存显示名；客户端优先按 `oilTypeIndex` 本地化，
  没有时显示 `oilType` 原文。下标与油品的对照表由设备端在 fixture 里给出。
- 【需求】任何缺失值返回 `null`，客户端显示「—」，不显示 0。

## 6. 换机与转移

- 【需求】记录以 `device_name` 为键；换机后按新设备编号查询，不合并旧设备历史（与 [油站契约](station-device-replacement-contract.md) 一致）。
- 【需求】客户转移后只返回与设备当前客户一致的记录（`customer_id` 取首次写入时的设备客户，同 `stock_events`）。

## 7. 待确认清单

| # | 事项 | 决定人 |
| --- | --- | --- |
| 1 | §2.1/§2.2 事件名、字段名、`shiftId`/`recordId` 规则 | 已定，两端已实现 |
| 2 | §1.1 缺陷 3、4、6 与时间到秒在上报前修复；缺陷 5 暂不改 | 设备端已修（未真机验证） |
| 3 | 是否恢复班号 | 已定：不要 |
| 4 | 库存按罐一条还是每次一条多罐 | 已定：每次一条多罐（v0.2） |
| 5 | 升级前历史是否上传 | 已定：都不传 |
| 6 | 入/出库千克数、报警介质是否由设备端新增字段 | 已定：设备端补上报（`deliveredWeightKg`、`alarm.oilType`） |
| 7 | `syncStatus` 判定方式与接口路径、参数名 | 云端已按 §4 实现；待小程序负责人确认 |
| 8 | 介质是否另加稳定的 `oilTypeIndex` | 已定：加，四类载荷都加 |
| 9 | `deliveredWeightKg` 的精确口径、`oilTypeIndex` 对照表 | 已定，见 fixture `stock_event_in_delivered_weight.json`、`oil_type_index.json` |

剩余：小程序接入（miniapp#17 的库存、交班表格）→ 真机联调（数据库 7→8 升级、outbox 实际发布、云端入库）→ 联合验收。

注意（设备端说明）：`deliveredWeightKg` 公式里的边卸边卖核对在设备上已废弃，`duringDeliveryVolumeL` 实际恒不出现、出入库事件
也不会因核对而重报；`oilType` 中英文表不逐项对应（设备端已知翻译缺陷），客户端应按 `oilTypeIndex` 本地化。
