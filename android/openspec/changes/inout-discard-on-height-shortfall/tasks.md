## 1. 状态机判定顺序修正

- [x] 1.1 `TankLevelTracker.onReadingWhileTracking` 改为"时长先行"：先判断 `durationSeconds` 是否达标，未达标时无条件继续等待（`maxTrackingMillis` 兜底仍在此分支）；达标后才判断净高度变化，够则产出事件，不够则立即 `resetTo(lastHeight)` 放弃
- [x] 1.2 更新类顶部 Javadoc 规则说明，反映"时长先行、高度不够立即放弃"的新顺序
- [x] 1.3 `InOutThresholds.maxTrackingMillis` 字段注释同步澄清：仅覆盖"时长阈值本身迟迟凑不够"的情况
- [x] 1.4 `InOutThresholds.DEFAULT_MAX_TRACKING_MILLIS` 由 20 小时改回 2 小时——兜底适用范围收窄后，不再需要用很大的默认值去兼容"高度不够也要等很久"的旧场景

## 2. 单元测试同步更新

- [x] 2.1 重写原 `timeSatisfied_heightNotYet_neverFiresAndEventuallyGivesUp`（依赖旧的"等 `maxTrackingMillis` 才放弃"行为，与新逻辑不符），替换为 `heightNotYet_onceTimeThresholdAlsoMet_discardsImmediately`：时长已达标、高度不够 → 立即放弃
- [x] 2.2 新增 `heightNotYet_timeNotYetEither_keepsWaitingInsteadOfDiscarding`：时长未达标时，即使当前高度也不够，也应继续等待，不提前放弃
- [x] 2.3 新增 `heightSatisfied_timeNeverCatchesUp_givesUpAfterMaxTracking`：验证 `maxTrackingMillis` 兜底现在只对"时长阈值本身迟迟凑不够"生效
- [x] 2.4 新增 `crossesBothDirectionsButNetChangeInsufficient_discardsOnceTimeThresholdAlsoMet`：先涨后跌、净变化被抵消，时长未达标前继续等待，时长达标后立即放弃且不与后续变化合并
- [x] 2.5 确认既有测试（`bothThresholdsSatisfied_*`、`heightSatisfied_timeNotYet_keepsWaitingInsteadOfDiscarding`、`resumesChangingWhileWaiting_continuesSameSegmentNotNewOne`、`noiseWithinTolerance_doesNotResetOrCountAsChange` 等）在新判定顺序下结论不变，无需修改

## 3. 编译与测试验证

- [x] 3.1 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` —— 读取 `app/build/test-results/testDebugUnitTest/*.xml` 核实：`TankLevelTrackerTest` 13/13、`MultiTankInOutDetectorTest` 3/3，其余既有测试套件（`CRCheckTest`/`DataUtilsTest`/`ExampleUnitTest`/`GoldenMasterHarnessTest`）均无失败

## 4. 真机验证（不可在本机完成，随 rewrite-stock-inout-detection 一并处理）

- [ ] 4.1 真实探头时序下验证："时长达标、高度不够"立即放弃是否符合现场预期，尤其是液位在阈值附近反复小幅波动时的表现
- [ ] 4.2 确认收紧后不会误伤"分步累积"的真实操作场景（现场操作是否存在断续分多步完成单次出/入库的情况，若存在需要重新评估本次改动是否合适）
