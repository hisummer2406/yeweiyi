## Why

`manager/SerialPortManager` 的探头协议分发 `switch (mOilCan.getProbeType())` 中，`case 1`（LD 探头）末尾漏写 `break`，执行穿透到 `case 2`，同一帧报文再被 `LD2Company` 解析一次。

排查中发现后果比 `docs/重构方案.md` 8.2 原先登记的更严重 —— 原文写的是「LD 结果被 LD2 覆盖」，实际是**可能污染其它油罐的读数**：

- `LD2Company.probeInfoPase` **完全不校验 CRC**，只判断 `bytes.length > 6`。LD 的应答帧是 9 字节，直接通过
- 它按 `decimal = (bytes[2] & 0xFF) / 2` 决定循环次数。LD 请求 2 个寄存器，应答的字节计数位是 `4`，因此循环 **2 次**
- 循环内按 `String address = "0" + (i + 1)` **以下标匹配油罐**，而非按实际应答探针的地址

也就是说：一台 LD 探头的正常应答，会被当作两个 LD2 探针的数据，写到探针地址为 `01` 和 `02` 的油罐上。受影响的可能不是 LD 探头自己所属的那个罐。

代码中已标 `FIXME` 但一直未修，原因是补 `break` 会改变现场读数行为、需先确认影响面。本变更把「确认 + 修复 + 验证」作为一件事完整做掉。

## What Changes

- **BREAKING（对现场读数而言）**：`case 1` 补上 `break`，LD 探头的解析结果不再被 `LD2Company` 覆盖，也不再误写其它油罐
- 为分发 `switch` 补 `default` 分支并记录未知 `probeType`，使「配了一个没有解析器的探头类型」从静默无效变为日志可见
- 先行确认影响面：查明现场探针配置中是否存在 `probeType=1`，据此判定这是防御性修复还是现场读数纠正
- 若确认现场在用 LD 探头，评估历史数据可信度并给出结论（本变更只做评估与记录，不做数据订正）

**不在本次范围**：
- 不给 `LD2Company` 补 CRC 校验 —— 那是另一处独立缺陷，会改变 LD2 探头自身的行为，应单独立项
- 不修改 `LD2Company` 按下标匹配油罐的逻辑 —— 同上
- 不做历史数据订正

## Capabilities

### New Capabilities

- `hardware/probe-protocol-dispatch`：探头协议分发的正确性契约 —— 每种 `probeType` 只由其对应解析器处理，且未知类型可被观测

### Modified Capabilities

无。本变更新增分发行为契约，不改动已有 change 中的任何 capability。

## Impact

- **受影响代码**：`app/src/main/java/com/sansen/yeweiyi/manager/SerialPortManager.java` 的 `RequestThread.run()` 内分发 `switch`（约 `:279`–`:300`）
- **受影响行为**：`probeType=1`（LD）油罐的液位/温度读数；以及探针地址为 `01`/`02` 的油罐 —— 它们此前可能被 LD 帧误写
- **触及硬件/协议层，必须真机验证**：本机只能验证编译与单测通过，**无法验证真实探头读数**。未经真机回归不得视为完成
- **与 golden master 的关系**：`ProbeReplayTest` 打印的解析器分布可作为现场实证。若采集数据中出现 `probeType=1 [LDCompany, LD2Company]`，即为本缺陷在现场触发的直接证据。修复后同一采集应只出现 `[LDCompany]`
- **对三条硬约束的态度**：respects 约束 1（单点改动，不做结构重写）；respects 约束 3（硬件协议层正确性优先于 UI 与架构工作）。约束 2（先织网）在此处以「先确认影响面 + 真机回归」替代 —— 该分发逻辑位于 `RequestThread` 内部、依赖 `MyApplication` 单例，纯 JVM 无法覆盖，这一限制本身正是 `layer-data-and-hardware-boundaries` 要解决的问题
- **相关记录**：issue #5、`docs/重构方案.md` 8.2、`CLAUDE.md` "Known landmines"
