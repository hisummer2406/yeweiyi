## Purpose

油罐（`OilCanSet` 等实体）数据读写的唯一入口，取代业务代码对 `DBDataManager` 中油罐相关方法的直接调用。

## ADDED Requirements

### Requirement: 油罐数据只能经由 OilTankRepository 读写
业务层（Fragment、Manager、web handler）SHALL 只能通过 `OilTankRepository` 接口读写油罐相关数据，不得直接调用 `DaoHelp`/`DbUtils` 或已废弃的 `DBDataManager` 油罐相关方法。

#### Scenario: 新业务代码读取油罐配置
- **WHEN** 一段新业务代码需要读取某个油罐的配置信息
- **THEN** 它只能注入/调用 `OilTankRepository` 的方法获得数据，代码里不出现对 `DaoHelp`/`DbUtils`/`DBDataManager` 的直接引用

### Requirement: 迁移期间行为不变
`OilTankRepository` 的每个方法在输入输出行为上 SHALL 与其在 `DBDataManager` 中对应的原方法完全一致，仅调用路径改变。

#### Scenario: 迁移前后结果一致
- **WHEN** 用相同参数分别调用迁移前的 `DBDataManager` 方法与迁移后的 `OilTankRepository` 方法
- **THEN** 两者返回的数据与产生的持久化副作用完全相同
