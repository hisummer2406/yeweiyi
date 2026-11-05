# tenant-model Specification

## Purpose
TBD - created by archiving change implement-multitenant-support-with-customer-isolation. Update Purpose after archive.
## Requirements
### Requirement: 客户实体
系统 SHALL 维护 `customers` 表，客户名称唯一；迁移完成后 SHALL 存在名为 `default` 的客户。

#### Scenario: 客户名称唯一
- **WHEN** 插入与已有客户同名的客户
- **THEN** 数据库拒绝该插入

#### Scenario: 迁移后存在默认客户
- **WHEN** 迁移 `000013` 应用完成
- **THEN** `customers` 中存在 `default`

### Requirement: 用户租户归属不变量
`platform_admin` 用户的 `customer_id` MUST 为空；`customer_admin` 与 `customer_user` 的 `customer_id` MUST 非空。该约束 SHALL 由数据库 CHECK 保证，而非仅靠应用代码。

#### Scenario: 平台管理员不能属于客户
- **WHEN** 写入 `role='platform_admin'` 且 `customer_id` 非空的用户
- **THEN** 数据库拒绝该写入

#### Scenario: 客户用户必须属于客户
- **WHEN** 写入 `role='customer_user'` 且 `customer_id` 为空的用户
- **THEN** 数据库拒绝该写入

### Requirement: 设备租户归属
设备 SHALL 至多属于一个客户；`customer_id` 为空表示已登记未分配。有用户或设备的客户 MUST NOT 被删除。

#### Scenario: 删除仍有设备的客户
- **WHEN** 删除一个仍有设备归属的客户
- **THEN** 数据库拒绝删除

### Requirement: 历史记录租户快照
`control_commands` 与 `audit_log` SHALL 在写入时记录一个客户快照：操作者所属客户；操作者是平台管理员（无客户）时取设备当时的客户；设备不存在则为空。设备/系统触发的记录（结果上报、过期清扫）SHALL 取对应命令的客户。此后设备改归属 MUST NOT 改变既有记录的客户。

#### Scenario: 设备改归属后历史不迁移
- **GIVEN** 客户 A 的用户对设备 D 下发过命令
- **WHEN** 设备 D 被改归属到客户 B
- **THEN** 该命令与其审计记录的客户仍为 A

#### Scenario: 越权尝试记在操作者的客户名下
- **WHEN** 客户 B 的用户对客户 A 的设备发起控制并被拒绝
- **THEN** 该条 `denied` 审计的客户为 B，客户 A 的审计查询看不到它

#### Scenario: 平台管理员的操作记在设备的客户名下
- **WHEN** 平台管理员对客户 A 的设备下发命令
- **THEN** 命令与其审计的客户为 A，客户 A 的管理员能在审计中看到这次操作

#### Scenario: 针对不存在设备的尝试
- **WHEN** 平台管理员对数据库里不存在的设备发起控制
- **THEN** 该命令相关的审计记录的客户为空

### Requirement: 存量数据迁移
迁移 SHALL 把所有非 `platform_admin` 用户与全部已有设备归属到 `default` 客户，且 SHALL 提供可往返的 down 迁移。

#### Scenario: 升级后存量用户可见原有设备
- **GIVEN** 升级前用户 U 绑定了设备 D
- **WHEN** 迁移完成
- **THEN** U 与 D 同属 `default`，U 仍可访问 D

#### Scenario: 迁移可往返
- **WHEN** 依次执行 up、down、up
- **THEN** 三次均成功且最终 schema 与首次 up 一致

