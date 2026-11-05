# stock-repository Specification

## Purpose

出入库（`OilInOut` 等实体）数据读写的唯一入口，服务 `InOilFragment`/`OutStockFragment`/`StockFragment`，取代对 `DBDataManager` 出入库相关方法的直接调用。

## Requirements

### Requirement: 出入库数据只能经由 StockRepository 读写
业务层 SHALL 只能通过 `StockRepository` 接口读写出入库记录，不得直接调用 `DaoHelp`/`DbUtils` 或已废弃的 `DBDataManager` 出入库相关方法。

#### Scenario: 记录一次入库
- **WHEN** `InOilFragment` 触发一次入库记录写入
- **THEN** 写入操作只经过 `StockRepository`，不直接触达 `DaoHelp`/`DbUtils`

### Requirement: 迁移期间行为不变
`StockRepository` 的每个方法在输入输出行为上 SHALL 与其在 `DBDataManager` 中对应的原方法完全一致。

#### Scenario: 出库记录查询结果一致
- **WHEN** 用相同查询条件分别调用迁移前后的方法
- **THEN** 返回的出入库记录集合完全相同
