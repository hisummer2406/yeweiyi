# 出入库事件（`stockEvent`）：最终协议与设备端改动

**状态：云端已实现并锁定字段名；设备端改动待 Android 负责人确认后实施。**
2026-09-04 整理，作为 PR #11 阻塞项之二的收口文档。

本文只谈出入库这一个遥测键。整体协议冻结状态见
[`protocol-v1-reconciliation.md`](protocol-v1-reconciliation.md) 与
[`protocol-v1-open-items.md`](protocol-v1-open-items.md)。

---

## 1. 现状：链路是通的，内容不可解析

设备侧的上报链路**已经在跑**，断网补投也已复用报警的 `PendingAlarmStore`，设计上没问题：

```
InOilManager.java:214   SocketManager.sendUnloadOil(detailInoutRecord.toString())
  → AIDL IBackService.sendUnloadOil
  → BackService.java:72   sendUnloadOils(msgInfo)
  → ThingsBoardManager.java:168   reportRawEvent("stockEvent", msgInfo)
  → v1/devices/me/telemetry   {"ts":…,"values":{"stockEvent":"…"}}
```

问题只在 `msgInfo` 的内容。`DetailInoutRecord.toString()`（`DetailInoutRecord.java:235-251`，
核对于 zhongchengyeweiyi `d0c3bce`）产出的是 Java 调试字符串：

```
DetailInoutRecord{oilcanId=1, beginHeith=402.0, endHeight=1642.5, beginVolume=6030.0,
 endVolume=24030.0, beginTime='2026-09-04 09:00:00', endTime='2026-09-04 10:00:00',
 beginTcVolume=6010.0, endTcVolume=23990.0, beginWater=18.3, endWater=18.5,
 beginTemp=22.1, endTemp=23.4}
```

三个独立的问题，任何一个都足以让它不能作为协议：

1. **不是 JSON**，云端要消费就得为某个 Java 类当前的 `toString()` 写专用解析器；
2. **没有契约**。`toString()` 是调试输出，加一个字段、IDE 重新生成一次，云端就静默解析失败，
   而且没有任何一侧的构建会因此变红；
3. **它比记录本身少一半字段**。`DetailInoutRecord` 有 `oilType`、`beginWaterVolume`/
   `endWaterVolume`、`beginOilWeight`/`endOilWeight`、`saleVolume`、`duringDelivery` 这些列，
   `toString()` 一个都没输出——其中 `duringDelivery` 正是"边卸边卖"场景下把到货量算对的那个数。

**这些记录现在正在 ThingsBoard 里堆积**，每一条都是永久无法还原的业务数据。

## 2. 云端在此之前的行为，以及为什么改掉

云端原本跳过解析不了的点、返回 `200 []`。对现役固件的设备，这意味着 `/api/oil-in-out` 用一个
格式正确、语气笃定的成功响应回答"这段时间没有出入库"——而真相是"每一笔出入库都在，只是读不出来"。
出入库记录恰恰是跟供应商对账时要拿出来吵架的东西。

**现在的契约**（`internal/api/stock.go`）：

| 情况 | 响应 |
|---|---|
| 窗口内所有 `stockEvent` 点都能解析（包括一条都没有） | `200` + 对象 |
| 解析失败，但记录**生成于** `stock.legacyFormatBefore` 之前 | `200` + 对象，计入 `legacySkipped` |
| 解析失败，且记录生成于该时间点之后（或未配置该时间点） | `502` + 对象，计入 `unparsable` |

响应**两条路径都是对象**：`{rows, parsed, unparsable, legacySkipped, …}`。裸数组装不下
`legacySkipped`，而一个悄悄省略"有多少条读不出来"的 200，跟当初那个空数组是同一个错误换了一层。
键名取 `rows` 而不是别的：这是小程序对本接口既有的期待
（yeweiyi-miniapp `miniprogram/pages/device-detail/device-detail.ts:98`），也是本仓库列表接口
一贯的形状（`/api/devices` → `{rows}`、命令 → `{commands}`、审计 → `{audit}`）。PR #11 在这里
改成裸数组才是偏离，本次是回到既有契约。行内字段与小程序的分歧另见
[`protocol-v1-open-items.md`](protocol-v1-open-items.md) §D。

`rows` 里仍然带上能解析的那些，所以运维不会因为一条坏记录丢掉本来看得到的数据；
`sample` 是第一条坏值的截断原文（按 rune 截断，200 字），足以一眼认出是 `DetailInoutRecord{…}`。
`code` 固定为 `STOCK_EVENT_FORMAT_UNSUPPORTED`。

选 502 而不是 500 或 4xx：调用方没做错任何事，本后端也在正常工作，是**上游**（设备经 ThingsBoard）
的载荷无法解释——这正是 Bad Gateway 的含义。

### 为什么需要 `stock.legacyFormatBefore`（PR #12 复审提出）

**设备端改完之后 502 不会自己消失。** 固件升级只让**新**记录变成 JSON，**不会转换 ThingsBoard 里
已有的那些**——升级前写进去的调试字符串永远读不出来。所以如果规则只是"解析不了就 502"，那么升级
之后任何回看到升级之前的查询窗口仍然会一直失败，直到这套部署退役为止。那不是"暂时诚实地失败"，
是**永久损坏的接口**，并不比当初那个空数组好。

而两种记录的载荷**逐字节相同**，唯一能区分它们的是时间。所以这个边界只能由运维给出：

```yaml
stock:
  legacyFormatBefore: "2026-10-01T00:00:00+08:00"   # 必须带时区偏移
```

| 解析失败的记录 | 含义 | 响应 |
|---|---|---|
| 早于该时刻 | 已经封口的历史数据空洞，没有人需要为它做什么 | `200`，计入 `legacySkipped` 如实报出 |
| 晚于等于该时刻 | **有设备该升级而没升级**，是当下的故障 | `502`，必须有人去现场看 |

**不配置**（默认）= "还没升级"，任何解析失败都按当前故障处理。这是正确的默认：没人告诉过它车队
已经升级的部署，不应该自作主张地把读不出来的记录一笔勾销。502 的 `error` 文案里会主动提到这个
配置项，否则第一个撞上的人根本不知道它存在。

取值要保守：宁可比实际升级时刻**晚**一点（多几条记录被当成故障、被人看见），也不要更早（真正没
升级的设备被静默写进历史空洞）。格式错误（缺时区、只写日期）会让后台**启动失败**而不是按 UTC
猜——一个被猜歪的边界最多能把它挪动一整天。

运维步骤见 `deploy/README.md`"排障 → 车队固件升级之后"。

## 3. 需要的设备端改动

一处，很小：

```java
// BackService.java:72 / InOilManager.java:214 一线之隔，任选其一改
- SocketManager.getInstance(...).sendUnloadOil(detailInoutRecord.toString());
+ SocketManager.getInstance(...).sendUnloadOil(StockEventMapper.toJson(detailInoutRecord, isIn));
```

`toJson` 产出的对象即下表右列。云端**不接受**在 `values.stockEvent` 里放一个 JSON *字符串*
以外的形状变化——字符串包 JSON 和直接放对象两种都能解析（`protocol.ParseStockEvent` 两者都吃），
但字段名必须一致。

### 硬要求：`stockEvent` 必须带信封 `ts`，且补投必须原样保留

这一条**已经是设备端当前的行为**，此处把它从"实现细节"提升为协议要求，因为云端的历史记录
分类完全依赖它。

`ThingsBoardManager.reportRawEvent`（:168-176）在记录**生成**的那一刻把
`ts = System.currentTimeMillis()` 写进信封，并把**已经序列化好的 JSON**入队；
`PendingAlarmStore.drainAndSend`（:87-99）在网络恢复后把那个字符串原样重发，全链路没有任何
重新打时间戳的地方。ThingsBoard 认信封里的 `ts`，所以一条晚几天才补投上来的记录仍然落在它
原本的时刻。

**为什么这条不能破坏：** 固件升级会重启设备，队列里积压的升级前记录正好在升级**之后**才补投
上来。如果分类按"ThingsBoard 收到的时刻"算，这些记录会全部被判成"有设备没升级"，接口恰好在
本该恢复正常的时候开始 502。反过来，如果设备发的报文**不带** `ts`，ThingsBoard 会盖上到达
时间，云端就无从区分"补投的旧记录"和"没升级的设备"——此时按安全方向处理，一律算作当前故障
（返回 502）。宁可多报一个假故障让人去看，也不能把真故障静默写进历史空洞。

两条路径都有回归测试钉住：`TestListOilInOut_ReplayedPreUpgradeRecordIsClassifiedByItsOwnTimestamp`
（信封 ts 早于边界、当场发布 = 补投）与 `TestListOilInOut_RecordWithoutAnEnvelopeTimestampCountsAsCurrent`
（不带信封 ts → 落在故障侧）。

### 字段映射（设备列 → V1 字段）

| `DetailInoutRecord` 列 | V1 字段 | 说明 |
|---|---|---|
| — | `eventId` | **设备需新增**。每笔movement 唯一、补投重发保持不变，供云端去重。建议 `"{in\|out}-{oilcanId}-{beginTimeMs}"` |
| `oilcanId` | `tank` | 1 起编号，与遥测快照的罐号一致 |
| —（由 `saveInRecord`/`saveOutRecord` 分支已知） | `direction` | `"IN"` / `"OUT"`。设备当前把方向丢在调用点上了，记录里没有 |
| `oilType` | `oilType` | 已有列，`toString()` 漏了 |
| `beginTime` | `beginTimeMs` | **需转换**：现为 `DateUtils.getYYYMMddHHmmss()` 的本地时间字符串、无时区。V1 用 epoch 毫秒 UTC |
| `endTime` | `endTimeMs` | 同上 |
| `beginHeith` | `beginLevelMm` | 拼写在此统一（另有 `beginHeigth`/`beginheigth` 两种写法） |
| `endHeight` | `endLevelMm` | |
| `beginVolume` | `beginVolumeL` | |
| `endVolume` | `endVolumeL` | |
| `beginWater` | `beginWaterLevelMm` | |
| `endWater` | `endWaterLevelMm` | |
| `beginWaterVolume` | `beginWaterVolumeL` | 已有列，`toString()` 漏了 |
| `endWaterVolume` | `endWaterVolumeL` | 同上 |
| `beginOilWeight` | `beginWeightKg` | 同上 |
| `endOilWeight` | `endWeightKg` | 同上 |
| `beginTemp` | `beginTempC` | |
| `endTemp` | `endTempC` | |
| （`endVolume - beginVolume`） | `netVolumeL` | 罐自身净变化，出库为负 |
| `duringDelivery` | `duringDeliveryVolumeL` | 已有列，`toString()` 漏了。见下方待确认项 |
| —（上报时刻） | `tsMs` | 记录关闭时刻，epoch 毫秒 UTC。与信封 `ts` 相同即可 |

规范的样例报文就是共享 fixture 本身：
[`fixtures/protocol/v1/stock_event_in.json`](../fixtures/protocol/v1/stock_event_in.json) 与
`stock_event_out.json`。云端的 `TestListOilInOut_ValidJSONFromTheSharedFixtureIsServed`
把这两个文件的字节原样发给真实设备端点并断言结果，Android 侧应当对同一文件做同样的事。

### 同一 `eventId` 可上报多次（issue #13）——云端的**暂行**处理，待 Android 确认

> **状态：云端暂行行为，不是已冻结的协议条款。** 它依据 Android 侧在 issue #13 里对自己行为的描述
> （补录后用同一 `eventId` 重发，两次上报的信封 `ts` 不同），而该分支（zhongchengyeweiyi PR #38）
> 尚未合并，也没有共享 fixture 或跨仓库测试覆盖。Android 确认之前不要把它当作设备必须遵守的契约。
> 若设备实际沿用旧的信封 `ts` 重发，或在重发时改变 `eventId`，云端会保留过期数值或重复计算同一笔
> 移库——所以这条列入 `protocol-v1-open-items.md` 的待确认事项。

`duringDeliveryVolumeL` 要操作员事后核对保存才有真实值，所以设备会用**同一个 `eventId`** 把补全后的
记录再发一次。云端 `/api/oil-in-out` 的处理：

1. 从 `beginTime`（向前留 1 小时时钟余量）**一直读到当前时刻**，分页读完所有 `stockEvent` 点，而不是只读
   `[beginTime, endTime)`。修正版比原始报告生成得晚，可能落在窗口结束之后；不设固定的向后查找期限，
   因为任何期限都只是对"操作员最晚多久确认"的猜测。
2. 按 `eventId` 折叠：保留**遥测信封 `ts`** 最大的一条，相同则保留先读到的。`eventId` 为空的记录不参与。
3. 折叠后，按事件自身的关闭时刻（`tsMs`，即响应的 `time`）落在 `[beginTime, endTime)` 内的才列出，
   所以一笔移库只会出现在它发生的那个窗口，跨月对账不会算两次；窗口里只含修正、原始报告更早时，该笔
   属于更早的窗口，不会在此重复出现。
4. 按 `time` 排序（`sort` 指定方向），再按 `limit` 截断；被截断时响应里 `truncated: true`。`limit`
   限制的是移库笔数，不是原始点数，所以排在前 `limit` 个点之后的修正版依然会被应用。
5. 若 `beginTime` 到现在的原始点超过 5000 × 10 个，返回 400 要求缩小窗口，而不是静默截断。

响应计数：`folded`（被折叠掉的重复报告数，只算列出的那些笔）、`truncated`。

**为什么用信封 `ts` 而不是载荷里的 `tsMs` 决定保留哪一条：** `tsMs` 的既有含义是设备关闭记录的时刻，
把它改成"版本号"是对跨仓库契约的单方面改动。信封 `ts` 的语义早已是硬要求（见上面"硬要求"一节：
是设备生成该报文的时刻，且补投必须原样保留），所以云端没有为此新增任何字段或要求。

**依赖的前提（也是暂行的原因）：** 一笔移库的所有报告共用同一个 `tsMs` 和 `eventId`；修正版的信封 `ts`
更晚。`eventId` 变了（补录改时间，见 issue #15）就不会被折叠。成本：读取范围随 `beginTime` 到现在的
跨度增长；遥测保留 7 天生效后（issue #23）这一跨度自然有界。是否需要显式的 `revision` 字段待与 Android
侧确认；若确认，应以该字段取代信封 `ts` 作为选择依据。

### 两列刻意不进 V1

- `beginTcVolume` / `endTcVolume`：名字读作温度补偿体积（标准体积），但赋值来自
  `OilCan.getSurPlus()`，而 `surPlus` 在设备端没有任何文档说明它是什么。
  **名实不符的字段不能冻结进协议**——需要设备端确认它到底是什么之后再单独加。
- `saleVolume`：`duringDelivery` 的计算输入（`saleVolume − (endVolume − beginVolume)`），
  云端拿到 `duringDeliveryVolumeL` 就够了。若确认云端也需要销售量本身，再加。

## 4. 待双方确认（不要单方面定稿）

1. **`eventId` 的生成规则。** 云端只要求"唯一且补投重发不变"。上面的建议式派生值需要设备端
   确认掉电重启后仍能算出同一个值。
2. **`duringDelivery` 的符号约定。** 云端按"卸油期间加油机发出的量，恒为正"处理
   （`deliveredVolumeL = netVolumeL + duringDeliveryVolumeL`）。设备端的计算式
   `saleVolume − (endVolume − beginVolume)` 在出库方向上是什么含义，需确认。
3. **单位。** `mm / L / kg / °C` 是按行业惯例的推断，**从未与设备的真实寄存器标度核对过**。
   这条对出入库尤其致命：体积单位错一个数量级，对账就全错。见 `protocol-v1.md` §12。
4. **历史记录怎么办。** ThingsBoard 里已经堆积的 `DetailInoutRecord{…}` 字符串：丢弃，还是
   写一次性迁移脚本尽力回填？回填只能恢复 `toString()` 输出的那 13 个字段，`duringDelivery`
   等永久缺失。建议丢弃并在文档里记下这段数据空洞的起止时间，而不是产出一批半真的对账数据。

   **云端已经按"丢弃"实现**：`stock.legacyFormatBefore` 把这段记录归类为 `legacySkipped`，
   数量如实报出但不当作故障。若产品决定要回填，那是一次性的迁移脚本，不改这个端点——回填之后
   那些记录本来就能解析了，边界配置可以直接删掉。
5. **出入库是否属于本期范围。** 若产品决定不做，正确的动作是**删掉 `/api/oil-in-out`**
   （像 `/api/shifts` 那样），而不是留一个永远 502 的端点。
