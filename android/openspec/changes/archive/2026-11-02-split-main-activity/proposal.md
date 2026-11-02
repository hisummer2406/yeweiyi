## Why

`MainActivity`（966 行）同时承担导航（Fragment 切换）、定时轮询、报警响应、密码校验、出入库业务动作、触摸分发六类职责，是全项目除 `ExportExcel`/`DBDataManager` 外的第三个上帝类。它还携带一处编译期不可见的地雷：18 处 `android:onClick="方法名"` 通过字符串反射绑定到 `MainActivity` 的方法（34 个 XML 属性引用，已核实现状与 `CLAUDE.md` 记录一致），搬走或改名任意一个方法都会编译成功、点击时崩溃。这是本项目风险最高的一处重构，必须放在阶段2（数据/硬件边界确立、测试网补齐）之后进行。

## What Changes

- **前置步骤（不可跳过，顺序错了会出现场事故）**：把全部 18 处 `android:onClick` 绑定改写为 `setOnClickListener`，让方法引用从字符串反射变为编译期可见的调用。这一步单独提交、单独验证，不与后续拆分混在一起。
- 拆 `MainActivity` 为四部分：
  - 导航器（Fragment 切换、返回栈管理）
  - 报警协调器（对接阶段2产出的 `AlarmRepository`，响应报警状态变化）
  - 轮询器（定时任务，对接 `MyTimeTask`/数据刷新）
  - 业务动作（出入库、交班等按钮触发的动作，密码校验）
- 逐个职责搬移，每次搬移后手工在真机上过一遍相关触摸路径（不仅是编译通过）。
- 不引入 Fragment 间通信框架或事件总线之类的新增架构组件——按 `docs/重构方案.md` 硬约束1，本次只做职责搬移，不做设计升级。

## Capabilities

### New Capabilities
- `ui/main-navigator`：Fragment 导航与返回栈职责边界
- `ui/alarm-coordinator`：报警响应的 UI 协调职责边界
- `ui/polling-coordinator`：定时轮询触发 UI 刷新的职责边界
- `ui/main-actions`：出入库/交班等业务动作与密码校验的职责边界

### Modified Capabilities
（无）

## Impact

- **受影响代码**：`activity/MainActivity.java`（966行，拆分为上述四部分）；18 处分布在 `res/layout/*.xml` 的 `android:onClick` 绑定（先改为 `setOnClickListener`）。
- **依赖前置变更**：报警协调器部分依赖本方案另一个变更 `layer-data-and-hardware-boundaries` 产出的 `AlarmRepository`——若那个变更还没落地，报警协调器的拆分要么阻塞，要么临时继续依赖 `DBDataManager`（需在 tasks.md 里明确写清楚，不要模糊处理）。
- **风险等级：高**。`docs/重构方案.md` 阶段3已明确标注"建议逐方法搬移 + 每次手工回归"——本变更不做单次大规模搬移，任务拆分需体现这一点。
- **真机验证要求**：`onClick` 绑定和触摸分发的正确性无法用单元测试覆盖，也无法在本机（无 Android 设备）验证，每个任务组完成后都需要真机点击回归，不能只凭 `assembleDebug` 通过就判定完成。
- **对三条硬约束的态度**：respects 约束1（禁止大爆炸重写，逐方法搬移）；respects 约束2（前置的 `onClick` → `setOnClickListener` 转换本身就是为了让后续搬移有编译期保护网，这是本变更版本的"阶段1"）；约束3 不直接适用（这是 UI 层工作，但正因为核心资产是硬件协议而非 UI，本变更的优先级本就低于 `layer-data-and-hardware-boundaries`，已体现在阶段顺序里）。
