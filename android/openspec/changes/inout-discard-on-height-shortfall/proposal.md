## Why

`rewrite-stock-inout-detection`（已合入代码、真机验证尚未完成）在 design.md 里把"一段跟踪期间方向来回反转，最终净变化被抵消到不够阈值"明确标注为 Non-Goal/已知风险：当时的处理是——净变化不够阈值时，不管时长阈值是否已经达标，都继续保持 TRACKING 等待，直到 `maxTrackingMillis`（默认 20 小时）才兜底放弃。

自测（见对话记录里对一份真实探头日志的逐条模拟）发现这个"继续等待"在净变化已经确定不够、且时长阈值也已经达标之后，没有实际意义：净变化量不会随时间推移自己变大，唯一能让它变大的只有液位真的再次变化——而那种情况本来就会被当作"仍在变化"处理，不需要靠"不放弃"来兜底。继续占着一个很久以前的起点，只会让之后不相关的小波动被错误地并入这段陈旧的跟踪，产出语义不清的记录。

## What Changes

- `TankLevelTracker` 的阈值判定改为"时长先行"两段式：
  1. 时长未达标时，不论净高度变化是否已经够了，都继续等待，不清空起点（行为不变）；
  2. 时长一旦达标，立即检查净高度变化：够了才产出事件（行为不变）；不够则**立即放弃**这段跟踪、以当前稳定值重建基准（新增行为，替代原先"继续等待到 `maxTrackingMillis` 才放弃"）。
- `maxTrackingMillis` 兜底的适用范围收窄为"时长阈值本身迟迟凑不够"这一种情况（例如时间阈值配置得异常大），不再需要覆盖"高度不够、迟迟等不到"的场景——那种场景现在会在时长达标的那一刻立即了结。
- `InOutThresholds.DEFAULT_MAX_TRACKING_MILLIS` 默认值相应由 20 小时改回 2 小时——兜底范围收窄后不再需要用很大的默认值兼容"高度不够也要等很久"的旧场景。

## Impact

- 受影响能力：`stock/inout-event-detection`
- 受影响代码：
  - `app/src/main/java/com/sansen/yeweiyi/logic/inout/TankLevelTracker.java`
  - `app/src/main/java/com/sansen/yeweiyi/logic/inout/InOutThresholds.java`（`maxTrackingMillis` 字段注释澄清适用范围）
- 受影响测试：`app/src/test/java/com/sansen/yeweiyi/logic/inout/TankLevelTrackerTest.java`
- 不涉及 `InOilManager`/`MainActivity` 等胶水层与落库代码——`TankLevelTracker.onReading(...)` 的方法签名和 `InOutEvent` 的产出语义不变，只是"什么时候放弃"这条内部规则变了。
