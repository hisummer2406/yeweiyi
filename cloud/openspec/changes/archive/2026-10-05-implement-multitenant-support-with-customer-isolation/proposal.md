## Why

Issue #3 要求“所有 REST 查询、WebSocket 订阅和命令接口执行租户/设备隔离”，且“跨租户读取、订阅和控制均被自动化测试拒绝”。当前只有角色（`users.role`）加 `user_devices` 绑定：没有客户实体，设备不属于任何客户，所以“客户管理员只管本客户”无法表达，公有云多客户场景下无法上线。

## What Changes

- 新增 `customers` 表；非平台管理员用户恰好属于一个客户，设备至多属于一个客户。
- 设备访问判定收敛为**一个**基于数据库的函数，REST、WebSocket、控制命令、审计全部走它；租户边界不进 JWT。
- `customer_admin` 可访问本客户全部设备（现状：必须逐台绑定，与 issue #1 的“只管理本客户设备”不符）。
- 配对绑定增加租户校验；设备登记（`pairing-secret` 铸造）时指定归属客户。
- `control_commands`、`audit_log` 写入时快照操作者所属客户，设备改归属后历史不迁移。
- 审计查询：`customer_admin` 可不指定设备查看本客户审计。
- 第二阶段：客户/用户/设备分配管理 API（现状生产代码中**没有任何创建用户的路径**，用户只能手工 SQL 插入）。
- **BREAKING**：`GET /api/devices` 对 `platform_admin` 由“仅已绑定设备”改为“全部设备”；`devices` 行新增 `customer_id` 字段（加性，需小程序负责人确认，见 Impact）。

## Capabilities

### New Capabilities
- `tenant-model`: 客户实体、用户/设备归属不变量、历史记录的租户快照、存量数据迁移。
- `tenant-access-control`: 统一设备访问判定，及其在 REST、WebSocket、控制命令、审计、配对绑定上的落实与自动化覆盖。
- `tenant-administration`: 客户、用户、设备分配的管理接口与审计（第二阶段）。

### Modified Capabilities
<!-- openspec/specs/ 目前为空，没有既有 spec 可修改 -->

## Impact

- **数据库**：迁移 `000013`（新表 + `users`/`devices`/`control_commands`/`audit_log` 加列、存量回填）；`db/queries/` 与 sqlc 输出。
- **代码**：`internal/api`（`userMayAccessDevice`、`listDevicesHandler`、`bindDeviceHandler`、`mintPairingSecretHandler`、`listAuditHandler`、`ws.go`）、`internal/control`（`authorize`、命令/审计写入）。
- **不变**：ThingsBoard 侧不引入 customer 概念；遥测仍只在 ThingsBoard；`internal/ingest` 仍按全部设备订阅（系统进程，非用户视角）。
- **跨仓库**：`devices` 行加字段属 REST 契约变更；`platform_admin` 设备列表语义变化——按 CLAUDE.md 需小程序负责人确认后才能发布。
- **不在本变更内**：7 天原始遥测保留与分钟/小时汇总（issue #3 另一半，属 ThingsBoard 侧数据策略，与租户正交，应单独立项）。

## Non-goals

- 一个用户属于多个客户；跨客户数据共享。
- 数据库行级安全（RLS），理由见 design D6。
- 把客户映射为 ThingsBoard customer，理由见 design D1。
