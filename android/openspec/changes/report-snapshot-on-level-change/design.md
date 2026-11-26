## Context

- 探头由 `SerialPortManager` 轮询，读数原地写进 `OilcanDataManager` 里的 `OilCan` 对象，屏幕通过 `BaseActivity` 的 `DATA_REFRESH` 消息刷新，所以屏幕几乎是实时的。
- 遥测由 `ThingsBoardManager.buildAndPublishSnapshot()` 生成：调用 `getNetOilcans()` 取当前所有罐的数据，用 `TelemetrySnapshotBuilder` 组成完整快照发出去。
- `TelemetryScheduler` 用单线程 `scheduleWithFixedDelay` 每 `periodMillis` 执行一次，带单飞保护（`inFlight`），支持运行期 `updatePeriod`（共享属性 `reportIntervalSec`）。

### 生产数据（2026-11-18，最近 24 小时，全部设备）

对相邻两次 30 秒快照之间的液位变化做了统计：

| 变化量 | 油位 | 水位 |
| --- | --- | --- |
| 0 | 175,896（85.0%） | 198,314（95.8%） |
| 0 < Δ < 1 mm | 27,449（13.3%） | 8,705（4.2%） |
| 1–2 mm | 1,978（1.0%） | 0 |
| 2–5 mm | 1,264（0.6%） | 0 |
| ≥ 5 mm | 432（0.2%） | 0 |

13% 的相邻快照有不到 1 mm 的抖动，所以阈值不能小于 1 mm。变化 ≥ 1 mm 的只占 1.7%，用 1 mm 不会被噪声持续触发。

## Goals / Non-Goals

**Goals：**
- 液位开始变化后 1 秒左右发出第一条快照；持续变化期间最多每 5 秒发一条。
- 液位不变时数据量和现在一样。

**Non-Goals：**
- 不新增共享属性来远程调阈值和限速。先用固定值，现场证明需要再加。
- 不按温度、体积等其他字段触发。体积由液位算出，温度变化慢；报警已经有即时事件通道。
- 不改云端和 ThingsBoard 规则链。

## Decisions

### 1. 每秒检查一次，而不是在探头读数回调里触发

调度器改成每 1 秒执行一次 `tick`，由 `SnapshotTrigger` 判断这一秒该不该发：

```
shouldPublish(now, levels) =
    lastPublishAt == null                                         // 启动后第一条
 || now - lastPublishAt >= periodMillis                           // 保底周期
 || (changedSinceLastPublish(levels) >= 1mm
     && now - lastPublishAt >= MIN_GAP_MILLIS /* 5s */)           // 变化触发 + 限速
```

**为什么不挂在探头回调上**：探头读数走串口线程和主线程 Handler，属于硬约束 3 里的协议层；在那里加钩子会把遥测逻辑带进串口路径。每秒读一次内存里的值，开销可以忽略，最多只多 1 秒延迟。

**调度器本身不改**：`TelemetryScheduler` 只把间隔从 30 秒改成 1 秒，沿用单飞保护和"不排队补跑"。周期改由 `SnapshotTrigger.periodMillis` 持有，`reportIntervalSec` 直接改它；`TelemetryScheduler.updatePeriod` 因此没有调用方，已删除。

**每秒只读液位**：`ThingsBoardManager.currentLevels` 直接读 `OilcanDataManager.getData()` 里的油位和水位；`getNetOilcans()` 每次都会反序列化 SharedPreferences，只在确定要发时才调用。

### 2. 和"上一次成功发出的快照"比较，而不是和上一秒的读数比较

如果跟上一秒比，缓慢的漂移（每秒 0.1 mm）永远到不了 1 mm，就不会触发；跟上次发出的值比，漂移累积到 1 mm 才发一次，符合"手机上的值与设备差距不超过 1 mm 或 30 秒"这个直观目标。

只有 `publishTelemetry` 返回成功时才更新基准值和 `lastPublishAt`。发布失败或断线时，下一秒会继续判定，等连上后就会发出。

### 3. 比较的范围

- 字段：每个罐的 `oilLevelMm` 和 `waterLevelMm`。
- 罐的集合变了（增删罐、启停罐），直接视为有变化。罐配置变更的属性上报本来就是即时的，这里跟着发一次快照，代价很小。
- **不看探头连接状态**。生产数据（2026-11-18，24 小时）里，多台设备的 `probeConnected` 在 30 秒采样下一天翻转 100–180 次（`errorTimes` 偶尔非 0 就算断开），每秒采样只会更多。按它触发会让这些设备几乎一直每 5 秒发一次。连接状态照常随快照上报，持续断开另有报警事件。

### 4. 阈值与限速取值

- **阈值 1 mm**：见上面的生产数据统计。
- **最短间隔 5 秒**：一次出入库通常持续几分钟到几十分钟，在这期间每 5 秒一条，是 30 秒周期的 6 倍，但只发生在液位变化的时段。按生产数据粗估，额外的快照最多比现在多约三分之一，实际会更少。
- 两个值写成 `SnapshotTrigger` 的常量，构造函数可注入，方便测试。

## Risks / Trade-offs

- **[探头在某些现场抖动超过 1 mm]** → 那台设备会每 5 秒发一次，数据量最多变成 6 倍。缓解：上线后在生产上统计每台设备每小时的快照数量，异常的设备再单独处理（届时再考虑做成共享属性）。
- **[小时平均值偏向变化时段]** → 见 proposal 的 Impact，接受。
- **[同一毫秒内两条快照]** → 不会发生：两次发布之间至少隔 1 秒（tick 间隔）。

## Migration Plan

- 设备升级 APK 即生效，不需要云端配合；旧 APK 的设备行为不变。
- 回滚：装回旧 APK。

## Open Questions

- 云端负责人是否同意 `protocol-v1.md` §3 的语义补充（只增加触发条件，key 和格式不变）。
