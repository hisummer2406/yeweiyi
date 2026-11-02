## Purpose

交班数据读写的唯一入口，服务 `CommitWorkRecortFragment`/`CommitWorkManager`，取代对 `DBDataManager` 交班相关方法的直接调用。

## ADDED Requirements

### Requirement: 交班数据只能经由 ShiftRepository 读写
业务层 SHALL 只能通过 `ShiftRepository` 接口读写交班记录，不得直接调用 `DaoHelp`/`DbUtils` 或已废弃的 `DBDataManager` 交班相关方法。

#### Scenario: 提交一次交班记录
- **WHEN** `CommitWorkRecortFragment` 提交一次交班
- **THEN** 写入操作只经过 `ShiftRepository`，不直接触达 `DaoHelp`/`DbUtils`

### Requirement: 迁移期间行为不变
`ShiftRepository` 的每个方法在输入输出行为上 SHALL 与其在 `DBDataManager` 中对应的原方法完全一致。

#### Scenario: 历史交班记录查询结果一致
- **WHEN** 用相同查询条件分别调用迁移前后的方法
- **THEN** 返回的交班记录集合完全相同
