## Purpose

报警（`Alarm` 等实体）数据读写的唯一入口，服务 `AlarmFragment` 与 `AlarmManager`/`AlarmDetection`，取代对 `DBDataManager` 报警相关方法的直接调用。

## ADDED Requirements

### Requirement: 报警数据只能经由 AlarmRepository 读写
业务层 SHALL 只能通过 `AlarmRepository` 接口读写报警记录，不得直接调用 `DaoHelp`/`DbUtils` 或已废弃的 `DBDataManager` 报警相关方法。

#### Scenario: 写入一条新报警
- **WHEN** `AlarmDetection` 检测到异常并需要持久化一条报警记录
- **THEN** 写入操作只经过 `AlarmRepository`，不直接触达 `DaoHelp`/`DbUtils`

### Requirement: 迁移期间行为不变
`AlarmRepository` 的每个方法在输入输出行为上 SHALL 与其在 `DBDataManager` 中对应的原方法完全一致。

#### Scenario: 未处理报警列表一致
- **WHEN** 用相同筛选条件分别调用迁移前后的方法查询未处理报警
- **THEN** 返回的报警记录集合完全相同
