## Why

`logic/ExportExcel.java`（1221 行）是 `重构方案.md` 里点名的第三个上帝类，但逐方法核查（`git grep -F`，每个方法名单独查，不用 `\|` 交替——见 CLAUDE.md 的 ugrep 地雷说明）后发现：它的体量主要不是职责过多，而是**约 60% 死代码**。7 个 `readExcelXxxContent` 系列导入方法中有 6 个在全代码库零调用方（`readExcelOilCanSetContent`/`readExcelAlarmContent`/`readExcelCommitWorkContent`/`readExcelInOutContent`/`readExcelSaveInfo` 完全无引用；`readExcelProbeToOilCanContent` 唯一的调用方 `test/TestExcel.java` 本身也是死代码——它在 `app/src/main/java`〔非 `androidTest`〕下，方法名 `TestExcel()` 不满足任何测试框架的入口约定，编译进 APK 但从未被执行）；`exportCommitWork`/`exportOilStroage` 两个导出方法同样零调用方。级联效应：`getValue(HSSFCell)` 辅助方法的所有调用点都在待删除的死方法内部，会随之变成死代码。

活着的部分（3 个 `exportExcel` 重载、`exportOilCanTable`、`readExcelOilCanTableContent`、`getCellDoubleValue`）一共约 450 行，被 6 个 Fragment + `DBDataManager` 共 9 处调用，职责边界清晰（通用反射导出 + 罐表导入），并不构成需要拆分的上帝类结构问题。这与 `MainActivity`/`DBDataManager` 两个已处理的上帝类不同——那两个是"职责过多"，这个是"体积虚高"。

## What Changes

- 删除 6 个零调用方的 `readExcelXxxContent` 方法（`readExcelOilCanSetContent`/`readExcelAlarmContent`/`readExcelCommitWorkContent`/`readExcelInOutContent`/`readExcelSaveInfo`/`readExcelProbeToOilCanContent`）
- 删除 2 个零调用方的导出方法（`exportCommitWork`/`exportOilStroage`）
- 删除级联死代码 `getValue(HSSFCell)`（唯一调用方均在上述待删方法内）
- 删除整个 `test/TestExcel.java`（唯一存在意义是调用即将删除的 `readExcelProbeToOilCanContent`；本身在 `app/src/main/java` 主源集下，从未被任何测试框架执行，是编译进正式 APK 的死代码）
- **不**拆分剩余的 `ExportExcel<T>` 类——删除死代码后规模降到约 450 行，职责边界（通用反射导出 / 罐表 Excel 导入）已经清晰，符合"职责搬移，不做设计升级"的项目硬约束，没有再拆分的必要
- 不改变任何存活方法的签名、行为或输出格式——纯删除，不是重写

## Capabilities

（无——纯删除死代码，不改变任何可观察行为，无 spec 变更。`.openspec.yaml` 已设置 `skip_specs: true`。）

## Impact

- **受影响代码**：`app/src/main/java/com/sansen/yeweiyi/logic/ExportExcel.java`（1221 → 约 450 行）；`app/src/main/java/com/sansen/yeweiyi/test/TestExcel.java`（整个文件删除）。
- **不受影响**：全部 9 个真实调用方（`AlarmFragment`/`CommitWorkRecortFragment`/`CreateOilTableFragment`/`InOilFragment`/`OutStockFragment`/`StockFragment`/`DBDataManager`）——它们调用的方法一个不删、签名不变。
- **验证方式**：`assembleDebug` 能确认删除后代码仍编译通过、无遗漏引用；导出的 Excel 文件格式/内容不因本次改动而改变（删除的方法从未被调用过，不存在"删除后行为变化"的风险）。由于是纯删除死代码，不需要真机回归——这与 `split-main-activity` 的 `onClick` 迁移不同，那里改变了触摸事件的分发路径，这里没有任何存活路径被触碰。
- **对三条硬约束的态度**：respects 约束1（无大爆炸重写，纯删除）；约束2 不直接适用（不改变任何被测试覆盖或可能被覆盖的行为路径）；约束3 不直接适用（UI/导出层工作，优先级本就低于硬件协议层，且此项工作量小、风险低，不与协议层工作抢资源）。
