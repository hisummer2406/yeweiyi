## 1. 契约

- [x] 1.1 写 `docs/config-command-contract.md`：`requestId`、`expectedStationId`、单条查询、在途互斥、两级开关、迟到结果、整机镜像、错误码表、
  剩余风险（建单后到派发前换机）、客户端共同规则；标注【需求】【选择】【待确认】
- [x] 1.2 `docs/tank-settings-contract.md` 第二阶段指向新契约；修正"`LOCAL_MODE_LOCKED` / 现场手动模式"——设备没有该状态（#9）
- [x] 1.3 open-items 登记整机属性键（取自设备端 `DeviceSettingsAttributeBuilder`，待负责人确认）与新 fixture
- [ ] 1.4 Open Questions 的决定写回 design.md（用户 2026-11-08 授权按【选择】实施；发布与设备端两项仍【待确认】）

## 2. 数据库

- [x] 2.1 迁移：`control_commands` 加 `request_id`、`expected_station_id`、`config_target`（中文注释）；部分唯一索引
  `(operator_id, device_name, request_id)` 与 `(device_name, config_target) WHERE 未终态`；验证存量 2 条命令迁移无冲突
- [x] 2.2 迁移：`devices.config_commands_enabled_at` / `_by`；`SetDeviceCustomer` 清空
- [x] 2.3 迁移：`device_settings` 表；加入 `db/queries/device_purges.sql` 与 `CountDeviceRemnants`
- [x] 2.4 down/up 往返；改 `CreateControlCommand`（`ON CONFLICT DO NOTHING`、方法等级、设备级开关、新列）；新增按 id / requestId 查询、
  油站/设备 `FOR SHARE`、开关读写、整机镜像 upsert/读取查询；`sqlc generate`

## 3. 控制服务

- [x] 3.1 `Issue` 改为事务：锁油站 → 锁设备 → 复核 `expectedStationId` → 建单；`requestId` 冲突读既有行比较指纹 → 重放 / `request_id_reused`
- [x] 3.2 `authorize` 增加设备级开关与 `scope`；计算 `config_target`；在途冲突映射 `config_command_in_flight`
- [x] 3.3 `ApplyResult` 0 行时写一次 `control.result_late`
- [x] 3.4 重放写 `control.replay` 审计，不写新的 `control.issue`

## 4. 接口

- [x] 4.1 `POST .../commands` 新字段与错误码；`commandResponse` 新字段
- [x] 4.2 `GET .../commands/:commandId`、`?requestId=`
- [x] 4.3 `PUT /api/devices/:device/config-commands`；`GET /api/devices` 行内 `config_commands_enabled`（仅管理员）
- [x] 4.4 `GET /api/devices/:device/device-settings`
- [x] 4.5 新路由登记 `tenancy_test.go` 的 `deviceScopedRoutes`

## 5. 镜像

- [x] 5.1 `internal/protocol` 整机属性解析 + 共享 fixture `client_attributes_device_settings.json`（MANIFEST 更新，Android CI 同步通过）
- [x] 5.2 `internal/ingest` 同周期写 `device_settings`

## 6. 验证（真实 PostgreSQL + ThingsBoard，`internal/devicesim`）

- [x] 6.1 幂等：重放同内容只派发一次；并发同键只建一条；改内容 409；撤权后重放被拒；崩溃后 `expired` 重放不派发
- [x] 6.2 换机：表单期间换机 409；与换机并发不死锁、结果符合串行化
- [x] 6.3 在途互斥：同罐 409、异罐并行、到期后释放
- [x] 6.4 两级开关各组合；员工 403 先于开关；客户管理员改开关 403；换客户清空
- [x] 6.5 SQL 内方法等级复核（模拟判定后降级）
- [x] 6.6 迟到结果审计只写一次、状态不变
- [x] 6.7 整机镜像：坏类型跳过、未上报 404、员工 403、删除清单计数为 0
- [x] 6.8 `go test ./internal/protocol/`、`go test ./... -p 1`、`go build ./... && go vet ./...`；跳过项如实记录

## 7. 文档与收尾

- [x] 7.1 `CLAUDE.md`："仍未定案"一节更新开关语义与提交保护；`tank-settings-device-verification.md` 的 curl 步骤加 `requestId` / `expectedStationId`
  与"先开放设备白名单"
- [x] 7.2 `docs/engineering-journal.md` 记录本次决策（两级开关、`requestId` 作用域）
- [ ] 7.3 （待用户要求提交时）PR 写 "Refs #3"，通知 admin #4 / miniapp #6 契约已定
