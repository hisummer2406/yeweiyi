## ADDED Requirements

### Requirement: 配置命令提交须带 requestId 并按其幂等
`config.setTankSettings` 与 `config.setDeviceSettings` 的提交 SHALL 带 `requestId`（1–64 个 `[A-Za-z0-9_-]`），缺失或不合法 SHALL 返回
`400 request_id_required` / `request_id_invalid` 且不建单。同一 (操作人, 设备, `requestId`) SHALL 至多对应一条命令：内容（方法、args、原因、
`expectedStationId`）相同的重放 SHALL 返回 `200` 与该命令当前状态且 MUST NOT 再次派发；内容不同 SHALL 返回 `409 request_id_reused`。
重放 SHALL 先按当前数据库状态重新授权，未通过时与首次提交返回相同错误，不透露该 `requestId` 是否存在。其他方法的 `requestId` 可选。

#### Scenario: 提交超时后重试
- **WHEN** 客户管理员提交配置命令后网络超时，用同一 `requestId` 与相同内容再次提交
- **THEN** 返回 200 与第一次建立的命令，设备只收到一次 RPC

#### Scenario: 并发双击
- **WHEN** 同一操作人以同一 `requestId` 几乎同时发出两次提交
- **THEN** 只建立一条命令、只派发一次，两次响应的 `commandId` 相同

#### Scenario: 同一 requestId 改了内容
- **WHEN** 用同一 `requestId` 提交不同的 `alarmHighLevelMm`
- **THEN** 返回 409 `request_id_reused`，不建单、不派发

#### Scenario: 撤权后重放
- **WHEN** 客户管理员被停用后用旧 `requestId` 重放
- **THEN** 按账号失效规则拒绝，不返回命令内容

#### Scenario: 进程崩溃后命令过期
- **WHEN** 命令建单后、标记 `sent` 前服务崩溃，命令随后被扫描为 `expired`，客户端重放同一 `requestId`
- **THEN** 返回 `expired` 的原命令，不重新派发

### Requirement: 可按 commandId 或 requestId 查询单条命令
系统 SHALL 提供 `GET /api/devices/:device/commands/:commandId` 与 `GET /api/devices/:device/commands?requestId=`（后者只匹配调用者本人的提交，
以列表形状返回 0 或 1 条）。设备级检查 SHALL 先于命令查找（防枚举 403）；单条查询时命令不存在、不属于该设备、对调用者不可见或 `commandId`
格式不对 SHALL 一律返回 `404 command_not_found`。`customer_user` SHALL 不能读取任何命令。

#### Scenario: 找回不在第一页的命令
- **WHEN** 设备已有 60 条命令，客户端按第 1 条的 `commandId` 查询
- **THEN** 返回该命令及其 `terminal` 标志

#### Scenario: 用别的设备路径查命令
- **WHEN** 有权访问设备 A 与 B 的管理员以 `/devices/B/commands/<A 的命令>` 查询
- **THEN** 返回 404 `command_not_found`

### Requirement: 受理时复核油站与设备关系
配置命令提交 SHALL 带 `expectedStationId`（整数或 `null`），缺失 SHALL 返回 `400 expected_station_required`。系统 SHALL 在建单的同一事务中按
"油站行 → 设备行"加共享锁，复核设备当前 `station_id` 与期望值相同（`null` 与 `null` 相同）；不同 SHALL 返回 `409 device_station_changed`
（带 `currentStationId`），不建单、不派发，写 `denied` 审计。该复核 SHALL 在设备访问授权之后进行。

#### Scenario: 打开表单后换机
- **WHEN** 管理员在油站 S 打开设备 D1 的表单，期间 S 换成 D2，管理员确认提交 D1 的命令且 `expectedStationId=S`
- **THEN** 返回 409 `device_station_changed`，`currentStationId` 为 `null`，D1、D2 都不收到命令

#### Scenario: 换机与提交并发
- **WHEN** 换机事务与建单事务并发
- **THEN** 二者串行化：要么换机先提交且建单复核失败，要么建单先提交且命令去往用户确认的设备

### Requirement: 同一配置目标至多一条未终态命令
同一设备同一罐（`config.setTankSettings`）或同一设备整机（`config.setDeviceSettings`）SHALL 同时至多有一条状态为 `pending`、`sent`、`received`、
`executing` 的配置命令，由数据库部分唯一索引保证。冲突 SHALL 返回 `409 config_command_in_flight` 并带在途命令的 `commandId`。不同罐之间、
罐与整机之间 MAY 并行。

#### Scenario: 同罐连续提交
- **WHEN** 1 号罐的配置命令仍在 `received`，同一或另一管理员提交 1 号罐的新配置（不同 `requestId`）
- **THEN** 返回 409 `config_command_in_flight` 与在途 `commandId`

#### Scenario: 不同罐并行
- **WHEN** 1 号罐的命令在途时提交 2 号罐的配置
- **THEN** 正常受理

### Requirement: 配置命令受部署级与设备级两级开关约束
配置命令 SHALL 只在部署配置 `allowConfigCommands` 为真且该设备已被平台管理员开放时才被受理；否则 SHALL 返回 `409 config_commands_disabled`，
`scope` 为 `deployment` 或 `device`。开关判定 SHALL 在授权之后。设备级开关 SHALL 在命令插入语句内再判一次。设备级开关 SHALL 只能由平台管理员经
`PUT /api/devices/:device/config-commands`（`enabled` + 必填 `reason`）修改，其他角色 SHALL 在解析请求体之前得到 403；修改 SHALL 幂等并写审计。
设备换客户时设备级开关 SHALL 被清空。物理命令开关 SHALL 不受本要求影响。

#### Scenario: 总闸开、设备未开放
- **WHEN** 部署级开关为真，客户管理员对未开放的设备提交配置
- **THEN** 返回 409 `config_commands_disabled`，`scope=device`

#### Scenario: 员工提交
- **WHEN** 有罐查看权的 `customer_user` 提交配置命令
- **THEN** 返回 403，与开关状态无关

#### Scenario: 客户管理员尝试开放设备
- **WHEN** 客户管理员调用设备级开关接口
- **THEN** 返回 403 `not authorized`，开关不变，写 `denied` 审计

#### Scenario: 设备换客户
- **WHEN** 已开放的设备被转给另一客户
- **THEN** 该设备的设备级开关变为未开放

### Requirement: 建单语句内复核方法权限等级
命令插入语句 SHALL 按操作人当前角色复核方法所需等级（`config.setTankSettings` 需管理员，`config.setDeviceSettings` 需平台管理员），
与访问规则同一语句；不满足 SHALL 不插入并按无权处理。

#### Scenario: 判定后被降级
- **WHEN** 平台管理员提交 `config.setDeviceSettings`，Go 侧判定通过后、插入前其角色被改为客户管理员
- **THEN** 不建单、不派发，返回 403

### Requirement: 迟到的命令结果留作诊断证据
当设备上报的 `cmdResult` 因命令已终态而不能应用时，若该命令存在且属于该设备，系统 SHALL 写一条 `control.result_late` 审计（含设备报告的状态、
错误码、原因、detail 与命令当前状态），且 MUST NOT 改变命令状态。同一命令的迟到结果审计 SHALL 至多一条。命令不存在或不属于上报设备时 SHALL 只记日志。

#### Scenario: 过期后才到达的成功结果
- **WHEN** 命令已被扫描为 `expired`，随后设备上报该命令 `succeeded`
- **THEN** 命令仍为 `expired`，审计里有一条 `control.result_late`，detail 含 `succeeded`

### Requirement: 镜像设备整机设置
系统 SHALL 从设备客户端属性读取整机设置（`inStockHeightThresholdMm`、`inStockTimeThresholdSec`、`outStockHeightThresholdMm`、
`outStockTimeThresholdSec`），与罐设置同周期写入业务库；不是整数的键 SHALL 被跳过并记日志，未上报的键 SHALL 为 `null`，设备上报的 `0` SHALL 原样保存。
`GET /api/devices/:device/device-settings` SHALL 对平台管理员与本客户的客户管理员返回镜像与 `reportedAt`，对 `customer_user` 返回 403，
从未上报返回 `404 device_settings_not_reported`。该表 SHALL 在设备彻底删除清单内。

#### Scenario: 整机设置下发后回显
- **WHEN** 平台管理员成功下发 `outStockHeightThresholdMm`，设备重发属性
- **THEN** 下一个同步周期内镜像更新为新值且 `reportedAt` 前进

#### Scenario: 员工读整机设置
- **WHEN** 有罐授权的 `customer_user` 请求整机设置
- **THEN** 返回 403

### Requirement: 命令响应带提交上下文
命令响应 SHALL 增加可空字段 `requestId`、`expectedStationId`、`configTarget`，既有字段与语义（`202` 仅表示受理阶段、`terminal` 由服务端计算、
ACK 不代表成功）SHALL 不变。

#### Scenario: 查询返回提交上下文
- **WHEN** 管理员按 `commandId` 查询一条配置命令
- **THEN** 响应包含提交时的 `requestId`、`expectedStationId` 与 `configTarget`（如 `tank:1`）
