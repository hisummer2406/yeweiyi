## Why

测试反馈：出库记录、入库记录生成的逻辑不对——用户期望的行为是"一次液位变化，只有在持续时长达到时间阈值、且总高度变化达到高度阈值时才生成记录"，但现状不是这样。梳理 `InOilManager.beginInOutDection()` 后确认了根因：液位趋于稳定后，只在第二次稳定读数时检查一次 `InTime/OutTime`+`InHight/OutHight` 阈值，只要这一次没同时满足，就把 `lastHeight/beginIn/inCount/startTime` 全部清零、彻底丢弃这次事件——而不是继续等待到阈值真正满足为止。由于真实的出/入库操作正是"持续变化一段时间后趋于稳定"的模式，这个分支被命中的概率很高，是"该出现的记录没出现"的主要原因。

除了这个根因 bug，现状还有一批结构性问题使得整块逻辑难以正确推理和验证（详见 design.md Decisions）：稳定判定用精确相等、无死区容差；`setOil()` 里叠了一层职责不清的跨罐静态节流/轮转逻辑；业务时长阈值和去抖窗口共用同一套变量语义；`InOilManager` 每实例只服务一个罐号却用 16 长度数组存状态；方向判定在 `InOilManager` 和 `MainActivity.success()` 两处重复计算，且这种重复已经导致出库分支里错误使用了 `in.getOilId()`（应为 `out.getOilId()`）这一潜在 NPE；判定逻辑与实时取数/落库耦合在同一方法里，无法脱离 Android `Context` 做纯 JVM 单测——目前这块行为完全没有单测覆盖。

## What Changes

- 重写出/入库事件检测逻辑，拆分为：
  - 一个不依赖 `Context`/单例的纯 Java 状态机（新类），按罐号独立跟踪液位变化，实现"未同时满足双阈值时继续等待，而不是清零丢弃"的目标行为；稳定判定加死区容差；业务时长阈值与去抖确认窗口拆成两个独立概念。
  - 一个薄的胶水层（改造 `InOilManager`），负责把状态机产出的事件（带明确的 IN/OUT 方向）与 `OilcanDataManager` 实时数据、`DBDataManager` 落库对接，替代现在 `MainActivity.success()` 里重新猜测方向的逻辑。
- 去掉 `InOilManager.setOil()` 里跨罐共享的静态节流/轮转逻辑（`current`/`RecordTime`/`isRecord[]`），改为按罐独立处理。
- 修复方向判定重复导致的 `in.getOilId()`/`out.getOilId()` 混用风险（结构上消除，而不是打补丁）。
- 为新的纯状态机类补充可在 JVM 直接运行的单元测试，覆盖持续变化、噪声去抖、双阈值边界（时间够高度不够/高度够时间不够/都够/都不够）、长时间不稳定兜底等场景。

## Capabilities

### New Capabilities
- `stock/inout-event-detection`：液位变化到出/入库事件的判定规则（双阈值门控、去抖、方向判定），当前项目里从未被记录为一个正式能力，此变更是首次将其行为固化为 spec。

### Modified Capabilities
（无——现状行为未被记录为任何已归档能力，本变更直接为其建立首个 spec 版本，不存在"修改既有 spec"的情况）

## Impact

- **受影响代码**：`manager/InOilManager.java`（核心重写）、`activity/MainActivity.java`（`success(...)` 回调改为消费新事件对象，`inOutOilDection`/`refreshData` 调用点相应调整）、新增状态机类与其单测（路径见 tasks.md）。不改动 `InOilFragment`/`OutStockFragment` 的列表展示逻辑，也不改动 `DBDataManager` 的持久化方式（那是 `layer-data-and-hardware-boundaries` 变更的范围）。
- **不属于协议/硬件层改动**：本变更只处理"液位数值序列 → 事件判定"这一层的业务逻辑，不涉及 `serialport/`、`factory/`、`structbean/`，不需要真机验证探头协议本身；但事件判定的最终效果（是否在真实设备上按预期生成出/入库记录）仍然依赖真实液位数据的时序特征，本机无法验证，需要真机回归确认阈值行为符合预期。
- **对三条硬约束的态度**：
  - **respects 约束1**（无大爆炸重写）：改动范围限定在 `InOilManager` + 其在 `MainActivity` 的调用点，不牵动其他模块；新旧逻辑通过新增单测比对关键场景，降低回归风险。
  - **respects 约束2**（测试先于重构）：tasks.md 第一步是给现状行为补一个理解基线（记录现状的问题场景作为文档/测试意图），随后新状态机类的单测与实现同步产出，不是"先重写再补测"。
  - **respects 约束3（部分适用）**：这不是硬件协议层改动，但涉及依赖真实液位时序的业务判定，真机验证是必需但不可在本机完成的收尾步骤，已在 tasks.md 中列为不可跳过项。
- **已知遗留、本次不修复**：`DetailInoutRecord` 与父记录（`OilInOut`/`OutStock`）的关联仍然依赖 `saveObject` 后 `findLast()` 反查自增 id 的方式，设计上对并发写入敏感；本次胶水层重写会保留这一调用方式（不扩大改动范围），已在 design.md 中记录为已知限制。
