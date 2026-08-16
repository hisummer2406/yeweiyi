## 1. 现状基线确认（不改代码，呼应硬约束2"测试先于重构"）

- [x] 1.1 确认 `InOilManager`/`MainActivity.success(...)` 目前没有任何单测覆盖（`app/src/test/java` 现有三个测试文件均不涉及），本变更是这块逻辑第一次获得测试覆盖，无需担心"改坏已有测试"，但也不能依赖已有测试兜底
- [x] 1.2 把 proposal.md / design.md 里列出的现状问题场景（清零 bug、精确相等无容差、方向重复判定导致的 `in.getOilId()` NPE 风险）逐条对照 spec.md 的 Requirement/Scenario，确认新实现要覆盖的边界场景清单完整，作为第2组测试用例的依据

## 2. 新增纯状态机层（`com.sansen.yeweiyi.logic.inout`），实现与单测同批产出

- [x] 2.1 新增 `InOutDirection`（枚举 IN/OUT）、`InOutThresholds`（不可变值对象：`inHeight`/`inTimeSeconds`/`outHeight`/`outTimeSeconds`/`noiseTolerance`/`settleWindowMillis`/`maxTrackingMillis`）、`InOutEvent`（不可变值对象：`tankId`/`direction`/`startHeight`/`endHeight`/`startTimeMillis`/`endTimeMillis`）
- [x] 2.2 新增 `TankLevelTracker`（单罐状态机，`InOutEvent onReading(double height, long timestampMillis, InOutThresholds thresholds)`，无事件返回 `null`；`void setEnabled(boolean enabled)`，另加 `isTracking()` 供胶水层判断"是否刚开始跟踪一段变化"），实现 design.md 决策2/3/4 描述的状态转移（IDLE/TRACKING、死区容差、稳定后持续等待直到双阈值满足或超过 `maxTrackingMillis` 兜底放弃、稳定期间再变化视为同段延续）
- [x] 2.3 新增 `MultiTankInOutDetector`（`Map<Integer, TankLevelTracker>`，懒创建；`onReading(int tankId, double height, long timestampMillis, InOutThresholds thresholds)`/`setEnabled(int tankId, boolean enabled)`/`isTracking(int tankId)` 转发到对应罐）
- [x] 2.4 新增 `app/src/test/java/com/sansen/yeweiyi/logic/inout/TankLevelTrackerTest.java`（10 个测试），逐条覆盖 spec.md 的 Scenario：双阈值都满足产出事件；只满足高度、时间不够时不产出且不清零，等时长追上后正确产出（验证根因修复）；只满足时间、高度不够时永不产出并在超过 `maxTrackingMillis` 后兜底放弃；死区容差过滤噪声抖动；稳定期间恢复变化视为同段延续；产出事件后以结束高度重新建基准；`setEnabled(false)`/`(true)` 的暂停/恢复语义
- [x] 2.5 新增 `MultiTankInOutDetectorTest.java`（3 个测试），验证多罐之间状态互不影响、`setEnabled` 只影响目标罐
- [x] 2.6 跑 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`，新增 13 个测试全部通过，读取 `app/build/test-results/testDebugUnitTest/*.xml` 核实（非凭退出码）

## 3. `InOilManager` 改造为胶水层

- [x] 3.1 `InOilManager` 内部持有 `MultiTankInOutDetector` 实例，`setOil(...)` 从 `Singleton` 组装 `InOutThresholds`（`noiseTolerance`/`settleWindowMillis`/`maxTrackingMillis` 用 `InOutThresholds.of(...)` 的占位默认值，已在类注释标注"真机验证阶段需确认取值"），调用 `detector.onReading(...)`
- [x] 3.2 去掉 `setOil()` 里跨罐共享的静态节流/轮转逻辑（`current`/`RecordTime`/`isRecord[]`），保留 `IsCreatIn`/`IsCreateOut` 均关闭时完全不喂数据给 detector 的短路
- [x] 3.3 去掉 16 长度定长数组（`beginIn`/`inCount`/`beginTime`/`curHeight`/`lastHeight`/`startTime`），状态全部下沉到 `TankLevelTracker`/`MultiTankInOutDetector`
- [x] 3.4 状态机判定"开始跟踪"时（`isTracking()` 由 false 变 true）抓取一份实时快照作为 `DetailInoutRecord` 起始字段（对应原来 `inCount 0→1` 时机的行为）；收到非 null 的 `InOutEvent` 后补全结束字段，按 `event.getDirection()` 分流落库到 `OilInOut`/`OutStock`，`IsCreatIn`/`IsCreateOut` 开关检查放在这一步（design.md 决策5）
- [x] 3.5 **与原计划不同**：`InOilManager` 从"每罐一实例 + 静态方法"改为单例（`getInstance()`），`setEnable(int, boolean)`/`setOil(int, double)` 变成实例方法而不是静态方法——`InOilFragment` 三处调用点和 `MainActivity` 一处调用点都相应改为 `InOilManager.getInstance().xxx(...)`（已同步修改，非"签名不变"）
- [x] 3.6 **与原计划不同**：没有保留/精简 `UpdateInOilListener` 接口，而是把落库、`SocketManager.sendUnloadOil`、`AlarmManager.addNoticesState` 全部整体搬进 `InOilManager`；`UpdateInOilListener` 因此完全没有实现方还接口引用，已随本变更一并删除（`listener/UpdateInOilListener.java`），比"保留精简版接口"更彻底地消除了"落库端重新猜测方向"的可能

## 4. `MainActivity` 调用点改造

- [x] 4.1 **与原计划不同**：不是"调整 success(...) 签名"，而是整个 `success(...)` 方法（含方向重复判定）连同 `UpdateInOilListener` 接口实现一起从 `MainActivity` 删除，方向判定与落库全部在 `InOilManager` 里只做一次
- [x] 4.2 修复 `out.getOilId()` 应替代 `in.getOilId()` 的问题——结构性消除：`in`/`out` 这两个 `MainActivity` 字段随 `success(...)` 一起删除，新代码统一用 `event.getTankId()`，不存在"用错另一个方向残留字段"的可能
- [x] 4.3 `inOutOilDection(OilCan)`/`refreshData(OilCan)` 调用点确认仍按每次新液位数据触发一次检测（`InOilManager.getInstance().setOil(...)`），触发时机不变
- [x] 4.4 确认未涉及任何 `res/layout` 的 `android:onClick` 绑定方法搬移/改名

## 5. 编译与测试验证

- [x] 5.1 `./gradlew :app:assembleDebug` —— `BUILD SUCCESSFUL`
- [x] 5.2 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` —— `BUILD SUCCESSFUL`；读取 `app/build/test-results/testDebugUnitTest/*.xml`：`ExampleUnitTest` 1/1、`CRCheckTest` 12/12、`DataUtilsTest` 25/25、`TankLevelTrackerTest` 10/10、`MultiTankInOutDetectorTest` 3/3，合计 **51 个测试，0 failures，0 errors**
- [x] 5.3 如实报告：编译通过、单测全部通过（见上），这只证明新逻辑内部自洽和不破坏现有编译/测试，不代表真机行为已验证（见第6组）

## 6. 真机验证与收尾（不可在本机完成，需明确标注待办）

- [ ] 6.1 **真机验证（不可跳过，尚未进行）**：本机无真实探头/液位数据源，`noiseTolerance`/`settleWindowMillis`/`maxTrackingMillis` 的占位默认值、以及双阈值判定在真实液位时序下是否符合现场预期，都需要接入真实设备（或至少真实/模拟的液位数据流）验证，本次的编译通过和单测通过只证明逻辑自洽，**不证明现场行为正确**
- [ ] 6.2 真机验证时重点回归：出库/入库记录在满足双阈值时正确生成、单独调整时间或高度阀值到临界值附近的边界行为、`InOilFragment` 人工卸油流程中 `setEnable` 暂停/恢复自动检测是否仍然正常
- [x] 6.3 更新 `CLAUDE.md` `manager/` 一节，补充新增的 `logic/inout` 包及其与 `InOilManager` 的关系
- [ ] 6.4 记录真机验证的实际结果（通过/待验证/发现的问题）——**当前仍是待办，不能声称"出/入库记录生成已修复"，只能说"判定逻辑已按新规则重写并通过单测，等待真机回归"**
