## 1. 交班领域（ShiftRepository）—— 第一批，验证迁移模式本身

- [ ] 1.1 新建 `ShiftRepository` 接口与实现，内部直接调用现有 `DaoHelp`/`DbUtils`（与 `DBDataManager` 当前访问的是同一套存储，不改表结构）
- [ ] 1.2 把 `CommitWorkRecortFragment`、`CommitWorkManager`、`DetailCommitWorkAdapter` 等调用方逐个改为依赖 `ShiftRepository`
- [ ] 1.3 `DBDataManager` 中交班相关方法标记 `@Deprecated`（暂不删除）
- [ ] 1.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过
- [ ] 1.5 真机手工回归：提交交班、查看历史交班记录

## 2. 出入库领域（StockRepository）

- [ ] 2.1 新建 `StockRepository` 接口与实现
- [ ] 2.2 把 `InOilFragment`、`OutStockFragment`、`StockFragment`、`InOutAdapter`、`OutStockAdapter`、`StockSaveAdapter`、`OilDischarge`（web handler） 等调用方逐个改为依赖 `StockRepository`
- [ ] 2.3 `DBDataManager` 中出入库相关方法标记 `@Deprecated`
- [ ] 2.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 2.5 真机手工回归：入库、出库、库存记录查询

## 3. 报警领域（AlarmRepository）

- [ ] 3.1 新建 `AlarmRepository` 接口与实现
- [ ] 3.2 把 `AlarmFragment`、`AlarmManager`、`AlarmDetection`、`AlarmHandler`（web handler） 等调用方逐个改为依赖 `AlarmRepository`
- [ ] 3.3 `DBDataManager` 中报警相关方法标记 `@Deprecated`
- [ ] 3.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 3.5 真机手工回归：触发一次真实报警场景，确认记录、查询、报警声光联动均正常（本步骤不改动 `AlarmManager`/`SoundLightManager` 的报警触发逻辑本身，只验证数据层替换没有引入回归）

## 4. 油罐领域（OilTankRepository）—— 调用面最大，放最后

- [ ] 4.1 新建 `OilTankRepository` 接口与实现
- [ ] 4.2 把 `MainActivity`、`OilcanDataManager`、`SaveOilManager`、`CreateOilTableFragment`、`BuLuFragment`、`LoginUser`、`PrintRunable`、`ExportExcel`、`SideleakageDetection` 等调用方逐个改为依赖 `OilTankRepository`（`MainActivity` 部分可与 `split-main-activity` 变更协调，避免两边同时改同一批代码冲突）
- [ ] 4.3 `DBDataManager` 中油罐相关方法标记 `@Deprecated`
- [ ] 4.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 4.5 真机手工回归：新建油罐、查看油罐详情、Excel 导出

## 5. 收尾：删除 DBDataManager

- [ ] 5.1 确认全项目已无任何代码调用 `DBDataManager` 的非 `@Deprecated` 以外方法（`grep -rl DBDataManager` 只应命中 `DBDataManager.java` 自身）
- [ ] 5.2 删除 `DBDataManager` 类，或如果仍有极少数无法归类的方法，将其保留为一个明确命名的兜底类（不叫 `DBDataManager`）并在 `CLAUDE.md` 里记录原因
- [ ] 5.3 更新 `CLAUDE.md` 的 `db/` 一节，反映新的 Repository 结构

## 6. 硬件边界：SerialPortManager 去 UI 依赖

- [ ] 6.1 定义 `DataRefreshCallback` 接口（或等价机制），替代编排层直接引用 `BaseActivity.DATA_REFRESH`
- [ ] 6.2 `com.sansen.yeweiyi.manager.SerialPortManager` 改为持有回调而非直接 `import BaseActivity`
- [ ] 6.3 `BaseActivity` 实现该回调接口并注册
- [ ] 6.4 在两个 `SerialPortManager` 类的类注释里分别标注职责（HAL vs 编排层），并在 `CLAUDE.md` 补一条"Known landmines"记录这对同名类的区别
- [ ] 6.5 跑 `assembleDebug` + 单测，确认通过
- [ ] 6.6 **真机验证（不可跳过）**：确认探头数据到达后 UI 刷新时机与改动前一致，无延迟或丢失

## 7. factory/ 纯解析逻辑抽取

- [ ] 7.1 为 `LDCompany`/`LD2Company`/`ZhongChengCompany`/`VR350Factory` 各自抽取一个不依赖 `android.*` 的 `XxxParser` 类，承载字节级解析逻辑
- [ ] 7.2 用现场抓取的真实报文作为 fixture，为每个 `XxxParser` 写 JVM 单测（延续 `CRCheckTest` 的模式），断言解析出的液位/温度值
- [ ] 7.3 抽取前后跑同一批 fixture 报文做输出 diff，确认零差异后再合入
- [ ] 7.4 跑 `assembleDebug` + 全部单测（含新增），确认通过
- [ ] 7.5 更新 `docs/重构方案.md` 阶段1状态：factory/ 部分从"受阻"改为"已通过阶段2完成"
