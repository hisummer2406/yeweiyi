## Context

见 proposal.md - Why。本变更只修正 `TankLevelTracker` 内部"未同时满足双阈值时该不该放弃"这一条规则，不改变 `InOutEvent`/`InOutThresholds`/`MultiTankInOutDetector` 的对外接口，也不改变 `InOilManager` 胶水层或落库逻辑。

## Goals / Non-Goals

**Goals:**
- 时长阈值已经达标、但净高度变化不够时，稳定确认后立即放弃这段跟踪，不再依赖 `maxTrackingMillis` 兜底。
- 时长阈值未达标时的行为保持不变（不论高度是否已够，都继续等待）——这是本次唯一要保留的"继续等待"分支。

**Non-Goals:**
- 不处理"一段 tracking 内方向中途反复反转应拆分成多个独立事件"这种更精细的场景——沿用 `rewrite-stock-inout-detection` 的既有立场：净变化量语义不变，只是"迟迟不够时该多久放弃"这一点被收紧。
- 不新增用户可见的设置项，不改变 `noiseTolerance`/`settleWindowMillis` 的默认值。

## Decisions

**1. 判定顺序从"净高度变化优先"改为"时长优先"。**

原实现（`rewrite-stock-inout-detection` 引入）：先算 `delta`，用 `delta` 的符号决定方向和对应的 `heightThreshold`/`timeThreshold`，再统一判断 `|delta| >= heightThreshold && durationSeconds >= timeThreshold`；未同时满足时无论是哪个条件不满足，都只用 `maxTrackingMillis` 兜底放弃。

新实现：先算出 `durationSeconds`，未达标时直接返回（沿用 `maxTrackingMillis` 兜底，但此时它兜的是"时长阈值本身不合理地大"这一种情况）；一旦达标，再检查 `|delta| >= heightThreshold`——够就产出事件，不够就立即 `resetTo(lastHeight)`。

选择"时长先行"而不是"高度先行"，是因为两者在"继续等待是否有意义"这件事上不对称：
- 时长不够时，`durationSeconds` 会随真实时钟自然增长，继续等待**必然**在某个时刻转为"达标"（假设液位不再变化）；
- 高度不够时，`delta` 不会自己变化，继续等待**不会**让这个判断结论改变，除非液位真的又发生了新的变化——而那种情况在代码里是走"仍在变化"分支处理的，不需要靠"暂不放弃"来覆盖。

所以正确的顺序是：先把"必然会随时间自然达标"的时长门槛过掉，再对"不会自己变"的高度做一次性判断。

**2. `maxTrackingMillis` 的角色收窄。**

收紧之前，`maxTrackingMillis` 要同时兜底两种情况："高度够、时长迟迟不够"和"高度不够、迟迟没有新变化"，语义比较杂。收紧之后，高度不够的那一支在时长达标的瞬间就会被立即了结，`maxTrackingMillis` 只需要覆盖"时长阈值本身配置得不合理地大，导致迟迟到不了达标点"这一种边缘情况——语义更单一，也更符合这个参数"避免状态无限期悬挂"的命名初衷。

*备选方案*：保留"高度不够也要等到 `maxTrackingMillis`"的原逻辑，只是把默认值调小。放弃原因：调小默认值治标不治本——只要液位在这段时间内保持稳定不变，无论 `maxTrackingMillis` 多小，这段等待期内产生的任何新小波动仍然会被错误地并入这个陈旧的起点；只有"时长达标后就该有确定结论"这条规则本身改掉，才能从根上避免这种合并。

## Risks / Trade-offs

- [风险] 收紧后，"净变化在时长达标时点前后来回跨越阈值"这种边界场景（比如净变化恰好在时长达标那一刻前一瞬跌破阈值）会更依赖轮询频率——如果轮询间隔较大，可能错过某个净变化恰好达标的中间读数 → 缓解：这个风险在收紧前后都存在（判定本身就是离散采样），不是本次引入的新风险，且已有 `TankLevelTrackerTest` 里的边界场景测试覆盖判定顺序本身的正确性。
- [风险] 本机仍然无法用真实探头数据验证收紧后的行为在现场噪声/轮询节奏下是否符合预期 → 缓解：延续 `rewrite-stock-inout-detection` 的既有待办，一并放进真机验证清单。

## Migration Plan

1. 调整 `TankLevelTracker.onReadingWhileTracking` 的判定顺序（时长先行），`InOutThresholds.maxTrackingMillis` 字段注释同步澄清适用范围。
2. 同步更新 `TankLevelTrackerTest`：修正被原顺序覆盖的用例，新增"时长已达标、高度不够立即放弃"“时长未达标时即使高度也不够也要继续等待”“先涨后跌净变化被抵消，时长达标后才放弃且不与后续变化合并”等场景。
3. `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` 验证全量单测通过。
4. 真机验证待办随 `rewrite-stock-inout-detection` 第6组一并处理，不重复建单。
