## Why

现场反馈（2026-11-18，ZCT000008）：液位变化后，设备屏幕上很快就更新了，手机上却要半分钟以上才变。

排查结果：
- 设备只按固定的 30 秒周期发遥测快照（`ThingsBoardManager.TELEMETRY_PERIOD_MILLIS = 30_000L`，`TelemetryScheduler` 用 `scheduleWithFixedDelay` 调度），液位变化不会触发上报。生产 ThingsBoard 里 ZCT000008 的快照时间戳间隔正好是 30 秒，没有丢包也没有积压。
- 云端 `GET /api/devices/:device/telemetry/latest` 直接读 ThingsBoard，没有缓存，不会额外增加延迟。
- 小程序首页和详情页只在打开页面或下拉时读一次 latest，不会自动刷新（这部分由小程序的配套变更 `miniapp/openspec/changes/auto-refresh-live-readings` 处理）。

只把周期调短（共享属性 `reportIntervalSec`）也能加快，但数据量会成倍增加：10 秒周期是现在的 3 倍。液位大部分时间不变，没必要按最快的频率持续上报。

## What Changes

- 遥测快照的触发条件从"每 30 秒一次"改为两种都会触发：
  1. **保底周期**：距离上一次成功发出的快照已满一个周期（默认 30 秒，仍可由 `reportIntervalSec` 覆盖），行为和现在一样。
  2. **变化触发**：任一罐的 `oilLevelMm` 或 `waterLevelMm`，跟上一次成功发出的快照相比变化 ≥ 1 mm，且距离上一次快照已经至少过了 5 秒（限速），就立即发一次完整快照。
- 无论是哪种触发，发出快照后保底周期都从这次发出的时刻重新计时，所以液位不变时数据量和现在完全一样。
- 快照内容、key、`snapshotSeq` 的语义都不变，云端和 ThingsBoard 不需要任何改动。
- 断线期间依旧不发、不补投（沿用现有策略）；重连后的第一次检查发现距离上次快照已满一个周期或液位有变化，就照常发出。

## 遵循的三条硬约束

1. **不做大爆炸式重写**：只改 `TelemetryScheduler` 的调度规则，加一个纯逻辑的变化判定类；不碰串口、探头解析或落库。
2. **测试先行**：先给新的判定规则写 JVM 单元测试，再改调度器。
3. **协议层最重要**：不改 `serialport/`、`factory/`、`structbean/`，只读取 `OilcanDataManager` 里已有的内存数据。

## Impact

- 受影响能力：`cloud/telemetry-reporting`（新增）
- 受影响代码：
  - `app/src/main/java/com/sansen/yeweiyi/logic/thingsboard/TelemetryScheduler.java`：从"固定间隔执行"改为"每秒检查一次是否该发"
  - 新增 `app/src/main/java/com/sansen/yeweiyi/logic/thingsboard/SnapshotTrigger.kt`：纯逻辑，判断是否该发、记录上次发出的液位
  - `app/src/main/java/com/sansen/yeweiyi/manager/ThingsBoardManager.java`：`buildAndPublishSnapshot` 只在发布成功后才更新"上一次发出的液位"
- 受影响测试：`app/src/test/java/com/sansen/yeweiyi/logic/thingsboard/TelemetrySchedulerTest.java` 需按新调度方式调整，新增 `SnapshotTriggerTest`
- 跨端：
  - `cloud/docs/protocol-v1.md` §3 的"周期"要补一句"液位变化 ≥ 1 mm 时会提前上报，最短间隔 5 秒"。这是协议语义的变化，按 `cloud/CLAUDE.md` 需要云端负责人确认。
  - 云端小时汇总 `internal/rollup` 用 ThingsBoard 的 `AVG` 求平均，它按数据点平均、不按时间加权。液位变化期间多出来的点会让那一小时的平均值偏向变化过程中的读数。偏差在出入库的小时里才会出现，幅度不大，本次接受，不改云端。
- 不涉及串口、探头协议，**但"液位真实变化时多快能上报"必须在真机上验证**，本机只能验证编译和单元测试。
- 安卓端由同事负责：代码完成后需要同事审核，并单独做真机验收。
