## 1. 先写测试

- [x] 1.1 新增 `SnapshotTriggerTest.kt`，覆盖 spec 的每个 Scenario：首次必发、保底周期、≥1 mm 立即发、<1 mm 抖动不发、5 秒限速、缓慢漂移累积、罐集合变化、变化后保底周期顺延、发布失败不更新基准、运行期改周期
- [x] 1.2 时间用注入的时钟，不依赖真实线程和 sleep

## 2. 实现

- [x] 2.1 新增 `logic/thingsboard/SnapshotTrigger.kt`：纯逻辑类，持有周期、阈值（1 mm）、最短间隔（5 s）、上次发出时刻和每罐液位；提供 `shouldPublish(now, levels)` 和 `onPublished(now, levels)`
- [x] 2.2 `TelemetryScheduler` 间隔改为 1 秒（类本身只删了已无调用方的 `updatePeriod`）；`reportIntervalSec` 改为设置 `SnapshotTrigger.periodMillis`
- [x] 2.3 `ThingsBoardManager.buildAndPublishSnapshot()`：每秒用 `currentLevels(getData())` 只读液位交给触发器；要发时才 `getNetOilcans()`；`publishTelemetry` 返回 true 才 `onPublished`
- [x] 2.4 更新相关类注释里"30 秒快照"的说法（例如失败日志"30 秒快照发布失败"）
- [x] 2.5 删除 `TelemetrySchedulerTest` 里 3 个 `updatePeriod` 用例，其余 4 个不变

## 3. 本机验证

- [x] 3.1 `./gradlew :app:assembleDebug` 通过（2026-11-18，JDK `~/jdks/jdk-11.0.33+10`）
- [x] 3.2 `cleanTestDebugUnitTest testDebugUnitTest`：563 个用例，0 失败，7 跳过（其中 `SnapshotTriggerTest` 13/13）

## 4. 跨端与评审

- [x] 4.1 更新 `cloud/docs/protocol-v1.md` §3 的"周期"说明
- [ ] 4.1a 请云端负责人确认 protocol-v1.md 的改动
- [ ] 4.2 请负责安卓的同事审核代码

## 5. 真机验收（本机做不了）

- [ ] 5.1 测试设备 ZCT000001 装新 APK，液位静止时查 ThingsBoard，确认快照间隔仍是 30 秒
- [ ] 5.2 手动改变液位，确认 1–2 秒内出现新快照，持续变化时间隔不小于 5 秒
- [ ] 5.3 上线一周后统计每台设备每小时的快照条数，确认没有设备因探头抖动持续 5 秒一发
