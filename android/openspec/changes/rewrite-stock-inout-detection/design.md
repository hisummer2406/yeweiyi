## Context

见 proposal.md - Why。本设计只覆盖"液位读数序列 → 出/入库事件判定 → 落库"这条链路（`manager/InOilManager.java` + `activity/MainActivity.java` 里 `success(...)`/`inOutOilDection`/`refreshData` 相关部分），不覆盖 `layer-data-and-hardware-boundaries` 变更范围内的持久化层重构（`DBDataManager` 拆分），也不覆盖 UI 展示层（`InOilFragment`/`OutStockFragment` 的列表渲染）。

现状代码风格是老式 Java（匿名内部类、无 lambda/`java.util.Optional` 使用痕迹），`app/build.gradle` 未显式声明 `sourceCompatibility`/`targetCompatibility`。为降低风险、贴合现有代码习惯，新代码同样使用返回 `null` 表示"无事件"而不是 `Optional`，不使用 lambda。

## Goals / Non-Goals

**Goals:**
- 一次液位变化事件，只有在"持续时长 ≥ 时间阈值 且 总高度变化 ≥ 高度阀值"同时成立时才生成出/入库记录；未同时满足时继续观察等待，而不是提前放弃。
- 判定逻辑（该不该算一次事件）与实时取数/落库解耦，可脱离 `Context` 在纯 JVM 单测中验证。
- 方向（入库/出库）只在一处计算，消除 `MainActivity.success()` 里重新猜测方向导致的 `in.getOilId()`/`out.getOilId()` 混用风险。
- 状态管理不再依赖 16 长度定长数组，支持任意数量油罐。

**Non-Goals:**
- 不处理"一次 tracking 过程中方向中途反复反转应拆分成多个独立事件"这种更精细的场景——沿用现状的"净变化量"语义（一段连续变化的起点到终点的净高度差），如需要更精细拆分需要后续变更单独评估，本次只在下方 Decisions 中记录为已知限制。
- 不改变 `DetailInoutRecord` 通过 `saveObject` 后 `findLast()` 反查自增 id 关联父记录的方式——这是已知的并发脆弱点，但修复它需要触及 `DBDataManager` 持久化方式，超出本次范围，留给 `layer-data-and-hardware-boundaries` 或后续变更处理。
- 不新增用户可见的设置项——新引入的去抖窗口、死区容差、最长跟踪兜底时长是实现细节参数，不在本次暴露到 `SetBaseFragment` UI；以有名字的常量/构造参数形式存在，方便后续需要时再决定是否开放为设置项。
- 不改变 `IsCreatIn`/`IsCreateOut` 开关语义——沿用现状"两者都关闭时完全不检测；单独关闭一侧时该方向的事件仍会被检测和消费（内部状态照常复位），只是不落库"的行为。

## Decisions

**1. 拆分为纯状态机层 + 胶水层，纯状态机放新包 `com.sansen.yeweiyi.logic.inout`。**

新增：
- `InOutDirection`：枚举 `IN`/`OUT`。
- `InOutThresholds`：不可变值对象，持有 `inHeight`/`inTimeSeconds`/`outHeight`/`outTimeSeconds`（业务阈值，来自 `Singleton`）+ `noiseTolerance`/`settleWindowMillis`/`maxTrackingMillis`（去抖/兜底参数，实现细节常量）。
- `InOutEvent`：不可变值对象，持有 `tankId`/`direction`/`startHeight`/`endHeight`/`startTimeMillis`/`endTimeMillis`。
- `TankLevelTracker`：单罐纯状态机，`InOutEvent onReading(double height, long timestampMillis, InOutThresholds thresholds)`，无事件时返回 `null`；另有 `setEnabled(boolean)`。
- `MultiTankInOutDetector`：持有 `Map<Integer, TankLevelTracker>`（懒创建，替代 16 长度数组和 `maps`/`current`/`isRecord[]` 静态状态），转发 `onReading(tankId, height, timestampMillis, thresholds)`/`setEnabled(tankId, enabled)` 到对应罐的 tracker。

`InOilManager` 改为胶水层：持有一个 `MultiTankInOutDetector` 实例，`setOil(...)` 里从 `Singleton` 组装 `InOutThresholds`、调用 `detector.onReading(...)`，收到非 null 的 `InOutEvent` 后按 `direction` 分流，补 `OilcanDataManager` 实时字段、落库（`OilInOut`/`OutStock`），直接使用 `event.getDirection()`，不再用高度差重新猜测方向。`MainActivity.success(...)` 的签名或调用方式相应调整为直接消费方向明确的事件（具体是保留 `UpdateInOilListener` 接口改造签名，还是把落库逻辑整体挪进 `InOilManager`，作为 tasks.md 第2组任务的一部分具体确定，原则是"方向只算一次、落库前不再重新判断"）。

*备选方案*：保留单类不拆分，只在 `InOilManager` 内部修复清零 bug + 加容差。放弃原因：不解决"业务判定耦合 Context 无法单测"这一根本问题，后续任何阈值调整都要在真机上试错验证；拆分后纯逻辑层可以在 JVM 上快速跑几十个边界场景，回归成本显著更低，值得这次一起做。

**2. 稳定判定用死区容差，不用精确相等；业务时长阈值与去抖窗口拆成两个独立参数。**

- `noiseTolerance`：判断两次读数是否"实质不同"的死区，读数差的绝对值 ≤ 此值视为未变化。默认给一个较小的常量，具体数值需要结合真实传感器分辨率在真机验证阶段确认（tasks.md 标注为真机验证项，不在本机臆造精确数值）。
- `settleWindowMillis`：读数进入容差内后持续 60 秒才确认稳定，入库、出库共用；减少短暂停顿造成的分段结算，与 `InTime`/`OutTime` 业务时长分别判断。现场效果未经真机验证。
- 两者都不是业务阈值，不经过 `Singleton`/`SharePrefUtil`，作为 `InOutThresholds` 构造参数存在，未来若需要现场调优，可以再评估是否要暴露成设置项。

**3. "未同时满足阈值"时保持 TRACKING 状态继续等待，不清零；只有真正开始新一段变化，或超过 `maxTrackingMillis` 兜底上限，才复位。**

这是本次要修的根因 bug 的直接对应设计：`TankLevelTracker` 进入"稳定"（连续读数在死区容差内且已超过 `settleWindowMillis`）后：
- 计算净高度变化 `delta = lastHeight - trackStartHeight` 和净持续时长 `duration = lastChangeTime - trackStartTime`；
- 两者都达到对应方向的阈值 → 产出 `InOutEvent`，复位到 IDLE（新基准 = 当前高度）；
- 未同时达到 → **不清零**，保持 TRACKING，等待下一次读数再检查（现实中随真实时钟推进，`duration` 会自然增长直到达标，无需高度再变化）；
- 若在稳定等待期间高度又真正变化（超出死区容差）→ 视为同一段事件的延续（暂停后恢复流动），更新 `lastHeight`/`lastChangeTime`，`trackStartHeight`/`trackStartTime` 不变；
- 若从 `trackStartTime` 起总耗时超过 `maxTrackingMillis`（默认给一个"明显不正常"的量级，比如数小时级），判定为异常/长期悬空，放弃并复位，避免状态无限期挂起。

*备选方案*：不设 `maxTrackingMillis` 兜底，永久等待。放弃原因：如果高度差本身就低于阈值且不会再变化，状态会无限期停留在 TRACKING，占着"上一次基准点"不放，掩盖掉此后所有更小幅度的正常波动判断（因为基准点一直不更新）；加一个远大于正常业务时长的兜底上限，可以避免这种情况又不影响正常场景。

**4. 方向判定只在 `TankLevelTracker` 内部算一次，通过 `InOutEvent.direction` 传出。**

净高度变化 `delta > 0` 判 `IN`，`delta < 0` 判 `OUT`（`delta == 0` 理论上不会触发——只有 `delta` 的绝对值不小于对应方向阈值时才会产出事件，而阈值恒为正数——但作为防御性判断，`delta == 0` 时不产出事件、直接复位）。`InOilManager`/`MainActivity` 侧不再用 `endHeight-beginHeight` 相对 `InHight` 重新推导方向，`in.getOilId()`/`out.getOilId()` 混用的 bug 从结构上消除（落库时只会用当前事件自己的数据，不存在"用错另一个方向残留字段"的可能）。

**5. `IsCreatIn`/`IsCreateOut` 的过滤仍然放在胶水层、事件产出之后。**

沿用现状语义：`MultiTankInOutDetector`/`TankLevelTracker` 本身不感知这两个业务开关，只要满足双阈值就产出事件；`InOilManager` 收到事件后，按 `direction` 检查对应开关，关闭则不落库（但事件已经让 tracker 复位，不会重复触发）。外层仍然保留"`IsCreatIn` 和 `IsCreateOut` 都关闭时完全不喂数据给 detector"的短路（避免无意义的状态维护），与现状行为一致。

**6. 去掉 `setOil()` 里跨罐共享的静态节流/轮转逻辑（`current`/`RecordTime`/`isRecord[]`）。**

`MultiTankInOutDetector` 按罐号独立处理，每次 `refreshData` 回调都直接喂给对应罐的 tracker，不做跨罐的"轮转攒批"。如果后续真机验证发现确实需要限频（比如高频轮询下 CPU 占用问题），应该是"每罐独立的节流"，不应该用共享的全局计时器实现——本次不预先加这层限频，等真机验证发现需要时再加，避免过度设计。

## Risks / Trade-offs

- [风险] `noiseTolerance`/`settleWindowMillis`/`maxTrackingMillis` 的具体数值本机无法根据真实传感器噪声特征标定，先给保守的占位默认值 → 缓解：以命名清晰的常量/构造参数形式暴露，真机验证阶段按实际噪声/轮询频率调整，不需要改状态机结构本身。
- [风险] "一段 tracking 内方向中途反转按净变化量处理"可能在极端场景（先大涨后大跌，净变化很小但中间瞬时幅度很大）下既不产生"入库"也不产生"出库"记录，和现状行为一致，但仍然不是精细的双向拆分 → 缓解：本次明确记录为 Non-Goal，不在本变更掩盖或臆造修复；如果真机验证发现这是高频场景，再单独立变更处理。
- [风险] 新的两层结构（纯状态机 + 胶水层）比原来的单文件多几个类，短期内增加了阅读成本 → 缓解：新类职责单一、命名直白，且新增的单元测试本身就是最好的行为文档，长期可维护性优于原来纠缠在一起的实现。

## Migration Plan

1. 先补一份"现状问题场景"的说明/测试基线（tasks.md 第1组），明确记录清零 bug 等现状行为，作为后续对比的锚点，呼应硬约束2"测试先于重构"。
2. 新增 `logic/inout` 包下的纯状态机类，同步编写单元测试（tasks.md 第2组），做到实现与测试同步产出而不是先写完再补测。
3. 改造 `InOilManager` 为胶水层，接入新状态机（tasks.md 第3组）。
4. 改造 `MainActivity` 调用点，消费带方向的事件，去掉重复的方向判定（tasks.md 第4组）。
5. 编译 + 全量单测通过后，标注真机验证待办收尾（tasks.md 第5组）——真实液位时序下的阈值行为、`noiseTolerance` 等参数的实际取值，都需要真机确认，本机无法验证。
