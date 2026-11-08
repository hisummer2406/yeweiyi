## Why

远程配置下发（cloud#70/#71）的技术链路已在生产真机走通一次（2026-11-08，ZCT000001 单字段 `alarmHighLevelMm`，见
`docs/tank-settings-device-verification.md`）。但要把它交给后台与小程序作为可交付功能（#3 总方案，配套 #4 admin、#6 miniapp、
#9 android），云端还缺几块"提交保护"，客户端不能自己补：

1. 再次 POST 一定生成新命令：网络超时后客户端无法区分"没发出去"与"已发出但没收到响应"，只能重发或放弃。
2. 只能分页列命令，找不回第一页之外或超时前那条命令。
3. 受理时不复核油站与设备的关系：后台/小程序打开表单后发生换机，确认的是旧设备，命令却可能落到新的设备上（或反之）。
4. 同一罐可以同时有多条未终态配置命令，结果与回显无法对应。
5. `allowConfigCommands` 是全局开关，生产没有测试环境，打开即对所有客户管理员开放；每次真机验证都要改 compose 并部署两次。
6. 迟到的 `cmdResult`（命令已 `expired`）被静默丢弃，事后无从诊断"设备其实执行了"。
7. 整机设置（出入库判定阈值）设备已作为客户端属性上报，云端未镜像，整机编辑无法展示当前值、也无法核对回显。

本期产品范围（用户 2026-11-08 决定）：**只做配置类命令**；动作类（消音/清警/重启/泵阀）维持关闭；密度只读不开放编辑；
配置开关改为按设备白名单。

## What Changes

- 配置方法（`config.setTankSettings`、`config.setDeviceSettings`）提交**必须**带 `requestId`：同一操作人 + 设备 + `requestId`
  永远对应同一条命令；重放返回原命令且不再次派发；内容不同 `409 request_id_reused`。规则与出入库补录（#69）的 `requestId` 一致。
- 配置方法提交**必须**带 `expectedStationId`（整数或 `null`）：受理事务内按"油站行 → 设备行"加锁复核，不一致
  `409 device_station_changed`，不建单、不派发。
- 新增单条查询：`GET /api/devices/:device/commands/:commandId` 与 `GET /api/devices/:device/commands?requestId=`（列表形状，0 或 1 条）。
- 同一目标（某罐 / 整机）同时至多一条未终态配置命令，否则 `409 config_command_in_flight`（带在途 `commandId`），由部分唯一索引保证。
- 配置开关改为**两级**：部署级 `allowConfigCommands` 且 设备级白名单（平台管理员经接口开关，写审计）都开才放行；
  命令插入语句内再判一次。
- 命令插入语句内复核**方法权限等级**（当前角色，读库），不只靠 Go 侧判定。
- 迟到/无法应用的 `cmdResult` 写诊断审计 `control.result_late`，命令状态不变。
- 镜像整机设置：`device_settings` 表 + `GET /api/devices/:device/device-settings`（管理员可读）。
- 命令响应增加 `requestId`、`expectedStationId`、`configTarget`。
- **不变**：物理命令开关、动作类方法、状态机守卫、ACK≠成功、`terminal` 语义、员工只读、防枚举 403。

## Capabilities

### New Capabilities
- `device-config-commands`：远程配置命令的提交保护、查询、并发规则、两级开关、迟到结果诊断与整机设置镜像。

### Modified Capabilities
（无。`tenant-access-control` 的访问规则不变；本变更只在其上追加命令层的约束。）

## Impact

- 接口：`POST /api/devices/:device/commands` 新增两个配置方法必填字段（**对配置方法是破坏性变更**；当前没有任何客户端发送配置方法，
  小程序旧命令表不含它们）；新增 `GET .../commands/:commandId`、`?requestId=`、`PUT /api/devices/:device/config-commands`、
  `GET /api/devices/:device/device-settings`。新路由登记 `internal/api/tenancy_test.go` 的 `deviceScopedRoutes`。
- 数据库：`control_commands` 加 `request_id`、`expected_station_id`、`config_target` 与两个部分唯一索引；`devices` 加配置白名单列；
  新表 `device_settings`（**须加进删除清单** `db/queries/device_purges.sql` 与 `CountDeviceRemnants`）。
- 代码：`internal/control`（Issue 流程、事务）、`internal/api/control.go`、`internal/ingest`（整机属性镜像、迟到结果）、`internal/protocol`
  （整机属性解析）、`internal/config`（开关语义说明）。
- 协议：不新增设备字段。整机属性键取自设备端现有 `DeviceSettingsAttributeBuilder`，按 CLAUDE.md 例外条款"云端只做消费的可选字段"处理，
  在 open-items 登记并补共享 fixture。
- 发布：合并后生产行为与现在相同（全局关、白名单空）。是否把全局开关常开、改由白名单控制，是**单独的发布决定**，不随本变更自动发生。
- 文档：`docs/tank-settings-contract.md`（第二阶段补提交保护）、新 `docs/config-command-contract.md`、`CLAUDE.md`、open-items。
- 下游：admin #4、miniapp #6 依本契约接入；android #9 做其余真机验收。见 design.md "跨仓库路线"。
