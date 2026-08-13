> **任务顺序已按 `docs/重构方案.md` v2（2026-08-13）重排。** 原顺序是「4 个 Repository → 删 DBDataManager → 硬件边界 → factory/ 抽取」，
> 把 `factory/` 抽取放在最末尾。v2 把它提到最前，理由：它是「补测试」与「改结构」互相阻塞的解锁点，且它产出的是一套
> 可复制的迁移模板与一份真实耗时数据，应该用它来校准后面所有工作，而不是等一切做完再做。
> 同时补上原计划缺失的前置——第 1 组的 golden master 录制。原 §7.2 直接假设「现场抓取的真实报文」已存在，但没有任何任务产出它。

## 1. golden master：现场报文录制与重放基线（★ 全变更的前置，唯一依赖现场档期）

本组不改任何业务逻辑，只增加录制能力并采集数据。**没有这组产出，第 2 组的等价性验证无从谈起。**

- [ ] 1.1 在 `manager/SerialPortManager` 的 `ProbeReciveDataListener.reciveData(byte[])`（约 `:339`）加录制钩子：记录原始字节帧 + 时间戳 + 当时的探头类型
- [ ] 1.2 在 `IParseDataListener.hexDataParseFinish(OilCan)`（约 `:418`）加录制钩子：记录解析产出的 `OilCan` 全字段快照，与 1.1 的输入帧配对
- [ ] 1.3 录制开关默认关闭，经 `local.properties` 注入的 `BuildConfig` 标志启用（照抄现有四个凭据的注入模式），落盘到 `/sdcard/`
- [ ] 1.4 跑 `./gradlew :app:assembleDebug`，确认编译通过
- [ ] 1.5 **现场采集（不可跳过；档期不由本地控制，应最早排期）**：正常工况跑满一天（覆盖昼夜温差，使温度字段拿到有意义的范围）；尽量制造报警工况（高位、低位、探头断线）；**每种在用的探头型号都要采到**——这一条决定 golden master 的覆盖面
- [ ] 1.6 把采集结果整理为版本化 fixture 放入 `app/src/test/resources/`，并记录每份 fixture 对应的探头型号与采集工况
- [ ] 1.7 建立重放测试：喂入 1.6 的字节帧，断言解析出的 `OilCan` 逐字段与录制快照一致。**此时测的是尚未改动的现有代码**，作用是锁死当前行为作为基线（characterization test，不问「应该输出什么」，只锁死「现在输出什么」）
- [ ] 1.8 跑 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认新增重放测试全绿
- [ ] 1.9 若现场档期迟迟无法落实，启用降级方案：依据厂商协议文档反推构造 fixture，并**在本任务下明确记录覆盖面缺口**（构造报文覆盖不了现场的畸形帧与时序异常），不得默认它等价于真实采集

## 2. factory/ 纯解析逻辑抽取（依赖第 1 组；本变更的模板验证批次）

- [ ] 2.1 为 `LDCompany`/`LD2Company`/`ZhongChengCompany`/`VR350Factory` 各自抽取一个不依赖 `android.*` 的 `XxxParser` 类，承载字节级解析逻辑（9 个 `factory/` 文件中现有 6 个 `import android.*`）
- [ ] 2.2 把第 1 组的重放测试改为直接打在新抽出的 `XxxParser` 上，脱离 Android 运行时
- [ ] 2.3 抽取前后用同一批 fixture 报文跑输出 diff，**确认零差异后再合入**（对应 spec「抽取前后行为等价」；注意 `CLAUDE.md` "Known landmines" 记的负数温度、CRC 补零等边界）
- [ ] 2.4 原 `XxxCompany` 退化为薄适配层，只保留 `Context`/`SparseArray` 相关部分，内部委托 `XxxParser`
- [ ] 2.5 跑 `assembleDebug` + 全部单测（含新增），确认通过
- [ ] 2.6 **真机回归（不可跳过）**：确认现场读数与抽取前一致
- [ ] 2.7 记录本组的实际耗时，作为第 3–6 组工作量估算的校准依据
- [ ] 2.8 更新 `docs/重构方案.md`：把 v2 第 2 步标记为完成，并回填实测耗时

## 3. 交班领域（ShiftRepository）—— Repository 迁移的第一批，调用面最小

- [ ] 3.1 新建 `ShiftRepository` 接口与实现，内部直接调用现有 `DaoHelp`/`DbUtils`（与 `DBDataManager` 当前访问的是同一套存储，不改表结构）
- [ ] 3.2 把 `CommitWorkRecortFragment`、`CommitWorkManager`、`DetailCommitWorkAdapter` 等调用方逐个改为依赖 `ShiftRepository`
- [ ] 3.3 `DBDataManager` 中交班相关方法标记 `@Deprecated`（暂不删除）
- [ ] 3.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过
- [ ] 3.5 真机手工回归：提交交班、查看历史交班记录

## 4. 出入库领域（StockRepository）

- [ ] 4.1 新建 `StockRepository` 接口与实现
- [ ] 4.2 把 `InOilFragment`、`OutStockFragment`、`StockFragment`、`InOutAdapter`、`OutStockAdapter`、`StockSaveAdapter`、`OilDischarge`（web handler） 等调用方逐个改为依赖 `StockRepository`
- [ ] 4.3 `DBDataManager` 中出入库相关方法标记 `@Deprecated`
- [ ] 4.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 4.5 真机手工回归：入库、出库、库存记录查询

## 5. 报警领域（AlarmRepository）

- [ ] 5.1 新建 `AlarmRepository` 接口与实现
- [ ] 5.2 把 `AlarmFragment`、`AlarmManager`、`AlarmDetection`、`AlarmHandler`（web handler） 等调用方逐个改为依赖 `AlarmRepository`
- [ ] 5.3 `DBDataManager` 中报警相关方法标记 `@Deprecated`
- [ ] 5.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 5.5 真机手工回归：触发一次真实报警场景，确认记录、查询、报警声光联动均正常（本步骤不改动 `AlarmManager`/`SoundLightManager` 的报警触发逻辑本身，只验证数据层替换没有引入回归）
- [ ] 5.6 与 `harden-device-external-access` §3（报警上报诚实性修正）协调：两者都触及报警链路，避免同时改同一批代码

## 6. 油罐领域（OilTankRepository）—— 调用面最大，放最后

- [ ] 6.1 新建 `OilTankRepository` 接口与实现
- [ ] 6.2 把 `MainActivity`、`OilcanDataManager`、`SaveOilManager`、`CreateOilTableFragment`、`BuLuFragment`、`LoginUser`、`PrintRunable`、`ExportExcel`、`SideleakageDetection` 等调用方逐个改为依赖 `OilTankRepository`（`MainActivity` 部分可与 `split-main-activity` 变更协调，避免两边同时改同一批代码冲突）
- [ ] 6.3 `DBDataManager` 中油罐相关方法标记 `@Deprecated`
- [ ] 6.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 6.5 真机手工回归：新建油罐、查看油罐详情、Excel 导出

## 7. 收尾：删除 DBDataManager

- [ ] 7.1 确认全项目已无任何代码调用 `DBDataManager` 的非 `@Deprecated` 以外方法（用 `git grep -F DBDataManager` 核对，**不要用正则**——本环境 `git grep` 的 `\|` 与 `\b` 会静默匹配为空，见 `CLAUDE.md`；只应命中 `DBDataManager.java` 自身）
- [ ] 7.2 删除 `DBDataManager` 类，或如果仍有极少数无法归类的方法，将其保留为一个明确命名的兜底类（不叫 `DBDataManager`）并在 `CLAUDE.md` 里记录原因
- [ ] 7.3 更新 `CLAUDE.md` 的 `db/` 一节，反映新的 Repository 结构

## 8. 硬件边界：SerialPortManager 去 UI 依赖（放最后，需真机验证时序）

- [ ] 8.1 定义 `DataRefreshCallback` 接口（或等价机制），替代编排层直接引用 `BaseActivity.DATA_REFRESH`
- [ ] 8.2 `com.sansen.yeweiyi.manager.SerialPortManager` 改为持有回调而非直接 `import BaseActivity`
- [ ] 8.3 `BaseActivity` 实现该回调接口并注册
- [ ] 8.4 确认第 1 组的录制钩子在回调机制改动后仍然工作（它挂在同一批接缝上），必要时同步调整
- [ ] 8.5 在两个 `SerialPortManager` 类的类注释里分别标注职责（HAL vs 编排层），并在 `CLAUDE.md` 补一条"Known landmines"记录这对同名类的区别
- [ ] 8.6 跑 `assembleDebug` + 单测，确认通过
- [ ] 8.7 **真机验证（不可跳过）**：确认探头数据到达后 UI 刷新时机与改动前一致，无延迟或丢失
