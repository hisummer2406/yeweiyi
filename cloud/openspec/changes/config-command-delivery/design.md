## Context

- 现有链路（2026-11-08 按 main `8250bd3` 核对）：`POST /api/devices/:device/commands` → `internal/control.Service.Issue`：
  `resolveAccess`（一条语句定访问与客户快照）→ 方法存在 → 原因非空 → `authorize`（角色等级、物理开关、配置开关，**Go 侧**）→
  `protocol.ValidateArgs` → ThingsBoard 查设备 → `CreateControlCommand`（INSERT…SELECT，语句内复核 `user_may_access_device`，
  **不复核方法等级与开关**）→ 必需审计 → RPC → `MarkControlCommandSent` / `Acked`（SQL 状态守卫）。
- 终态走遥测 `cmdResult` → `ApplyResult` → `MarkControlCommandFinished`（`status IN (pending,sent,received,executing)`）；
  0 行时静默返回，不留痕迹。过期由 `ExpireStaleControlCommands` 扫描。
- 查询只有 `GET .../commands`（分页，`ListControlCommandsVisibleToUser`，员工看不到）。
- 换机 `POST /api/stations/:id/replace-device` 锁序：油站行 `FOR UPDATE` → 设备行（名升序）。
- 已有先例：出入库补录 `requestId`（#69，`(device, requestId)` 幂等，`409 request_id_reused`）。
- 设备端 `DeviceSettingsAttributeBuilder` 已把 `inStockHeightThresholdMm` `inStockTimeThresholdSec` `outStockHeightThresholdMm`
  `outStockTimeThresholdSec` 作为顶层客户端属性上报；云端 `ingest` 读客户端属性时未解析它们。
- 生产单实例；没有测试环境；平台管理员只有 `admin`；`control_commands` 只有 2 条（都是 2026-11-08 验证命令）。

## Goals / Non-Goals

**Goals：** 客户端能安全地"提交一次、超时后找回、不重复派发"；受理时挡住换机与权限变化；同一目标结果可对应；生产能按设备逐台开放配置；
迟到结果可诊断；整机设置可读。

**Non-Goals：**
- 动作类方法（`alarm.*`、`device.reboot`、`pump.*`、`valve.*`）的开放——维持现状。
- 设备侧配置版本/因果标记、现场页面旧值覆盖远程值（已接受的限制，不改协议）。
- 密度单位对齐：云端仍按协议透传 `density`，是否在客户端开放编辑由客户端规格决定（本期不开放）。
- 罐高/罐型/探针/标定表/记录设置/报表设置的远程写入。
- 受理之后、派发之前的换机竞态彻底消除（见 D3 剩余风险）。

## Decisions

### D1 `requestId`：配置方法必填，按 (操作人, 设备, requestId) 幂等

- 请求体新增 `requestId`（1–64 个 `[A-Za-z0-9_-]`）。**对 `config.setTankSettings` / `config.setDeviceSettings` 必填**，缺失
  `400 request_id_required`；其他方法可选（不改变旧行为）。
- 唯一键 `(operator_id, device_name, request_id)`，部分唯一索引 `WHERE request_id IS NOT NULL`。不用 `(device, requestId)`：
  两个管理员恰好生成同一 id 不应互相"找回"对方的命令。
- 内容指纹 = `method` + 规范化 `args`（`jsonb` 相等比较，键序无关）+ 修剪后的 `reason` + `expectedStationId`。
  同键同指纹 → `200` + 原命令当前状态，**不再次派发**、不写新的 `control.issue` 审计（写一条 `control.replay`）；
  同键不同指纹 → `409 request_id_reused`。
- 重放前**重新走完整授权**（访问规则、方法等级、开关）：已被撤权/停用的人不能凭旧 `requestId` 读到命令（与"不读 JWT"一致）。
  授权失败时与首次提交同样的 403/409，不透露该 `requestId` 是否存在。
- 并发：INSERT 用 `ON CONFLICT DO NOTHING`，0 行则读既有行比较指纹。两个并发请求只有一个建单、只有一个派发。
- 保留期：随命令行永久保存（命令表本来不清理）；设备彻底删除时随设备删除。
- 进程在"已建单、未标 sent"时崩溃：行停在 `pending`，扫描到期后 `expired`。RPC 可能已发出，所以 `expired` **不表示没执行**；
  重放同一 `requestId` 拿到 `expired`，客户端须提示"结果无法确认，请核对设备当前值"，**不得自动换新 `requestId` 重发**。
- 【选择】`requestId` 由客户端在**打开确认弹窗时**生成一次，同一次确认的重试复用；用户改了表单再确认则换新 id。

### D2 单条查询

- `GET /api/devices/:device/commands/:commandId`、`GET /api/devices/:device/commands?requestId=<id>`（后者只查**调用者本人**的提交，
  沿用列表形状 `{"commands":[...]}`，0 或 1 条，客户端复用同一解析）。
- 权限同列表：先 `requireDeviceOwnership`（防枚举 403），再在 SQL 内按 `ListControlCommandsVisibleToUser` 同一规则过滤。
  员工一律看不到（与"员工不读命令历史"一致）。命令不存在 / 不属于该设备 / 不可见 → `404 command_not_found`（同一响应）。
- `commandId` 非 UUID → `404 command_not_found`（不给 400，避免区分）。

### D3 `expectedStationId`：受理事务内复核油站—设备关系

- 配置方法必填 `expectedStationId`（整数，或 `null` 表示"设备当前不在任何油站"）；缺字段 `400 expected_station_required`。
- 建单改为一个事务：`SELECT stations … FOR SHARE`（若期望非空）→ `SELECT devices … FOR SHARE` → 比较
  `devices.station_id IS NOT DISTINCT FROM expected` → INSERT（语句内照旧复核访问规则）。锁序与换机一致（油站 → 设备），
  不会死锁；`FOR SHARE` 与换机的 `FOR UPDATE` 互斥，所以换机要么在建单之前提交（复核失败），要么等建单提交后才开始。
- 不一致 → `409 device_station_changed`，带 `currentStationId`（调用者已通过设备访问检查才会走到这里，不构成枚举）。不建单、不派发，
  写 `denied` 审计。
- **剩余风险（写入契约）**：建单提交后到 RPC 发出之间（毫秒级，含 ThingsBoard 往返）仍可能换机。换机不改设备名、命令按设备名派发，
  所以命令仍去往**用户确认过的那台设备**；风险只在于"它此刻已不在那个油站"。这是可接受的：设备是确认对象，油站是上下文。

### D4 同一目标至多一条未终态配置命令

- 新列 `config_target`：`config.setTankSettings` 为 `tank:<n>`，`config.setDeviceSettings` 为 `device`，其他方法 `NULL`。由 Go 根据
  已校验的 args 计算并写入。
- 部分唯一索引 `(device_name, config_target) WHERE config_target IS NOT NULL AND status IN ('pending','sent','received','executing')`。
  冲突 → `409 config_command_in_flight` + 在途 `commandId`（调用者已有该设备的命令读取权）。
- 【选择】不跨目标互斥：同设备 1 号罐与 2 号罐可以并行；整机设置与罐设置也可并行（设备端分属不同存储）。
- 在途命令到期由扫描关闭后索引自动释放；不提供"强制取消"。

### D5 两级配置开关：部署级 AND 设备级（**2026-11-09 已撤销**：用户决定去掉设备级白名单，迁移 000039；生产总闸常开）

- 保留 `control.allowConfigCommands`（部署级总闸，默认 false）。新增 `devices.config_commands_enabled_at`（`NULL` = 未开放）与
  `config_commands_enabled_by`。两者都开才放行。
- 关闭时一律 `409 config_commands_disabled`，带 `scope: "deployment" | "device"`，客户端据此提示"系统未开放"或"该设备未开放"。
  判定位置在授权之后（无权者先得 403，不知道开关状态）。
- 开关接口 `PUT /api/devices/:device/config-commands` `{"enabled": bool, "reason": "..."}`，**仅平台管理员**（其他角色解析请求体前 403，
  写 `denied` 审计）；幂等；写 `device.config_commands` 审计（含前后值、原因）。状态在 `GET /api/devices` 行内以
  `config_commands_enabled` 暴露给管理员（该接口沿用下划线命名）。
- 命令 INSERT 语句内再判一次设备级开关（与访问规则同语句），挡住"判定后被关闭"的窗口；部署级是进程配置，不需要。
- 用列而不是新表：随设备行删除，不需改删除清单；换客户时**清空**（新客户须重新开放）。换机的新设备天然未开放。
- 【待确认·发布】合并后生产仍是总闸关、白名单空，行为不变。之后是否把总闸常开、改由白名单逐台开放，单独评审后执行。

### D6 命令插入语句内复核方法等级

- `CreateControlCommand` 增加参数 `required_level`（`control` / `admin` / `platform`），语句内按 `users.role` 判定。Go 侧 `authorize`
  保留（给出准确错误与审计），SQL 是最后一道（防"判定后被降级"）。语句 0 行仍按 `ErrForbidden` 处理。

### D7 迟到/无法应用的结果写诊断审计

- `ApplyResult` 的守卫 0 行时，若命令存在且属于该设备，写审计 `control.result_late`（`outcome=failed`，detail 含设备报告的
  `status`/`errorCode`/`reason`/`detail` 与命令当前状态），**不改状态**（约束 4）。命令不存在或不属于该设备：只记日志（防设备刷审计）。
- 同一命令的迟到结果审计只写一次（`ingest` 会重复回读）：用 `audit_log` 上 `(command_id, action)` 的存在性判断。
- 命令响应不新增字段；后台"操作记录"可经 `/api/audit?device=` 看到。

### D8 整机设置镜像

- `internal/protocol` 新增整机属性解析（四个键，整数），类型不对跳过并记日志；不做范围检查（设备以 `0` 表示未配置，原样保存）；不补默认值。
- 新表 `device_settings(device_name PK, in_stock_height_threshold_mm, in_stock_time_threshold_sec, out_stock_height_threshold_mm,
  out_stock_time_threshold_sec, reported_at, updated_at)`，`ingest` 与罐设置同周期读写，`reported_at` 不变不写。**加入删除清单。**
- `GET /api/devices/:device/device-settings` → `{deviceName, reportedAt, inStockHeightThresholdMm, …}`，从未上报 `404 device_settings_not_reported`。
  可读：平台管理员、客户管理员（本客户）；员工 `403`（整机数据，与"整机级记录只对管理员可见"一致）。
- 共享 fixture 补 `client_attributes_device_settings.json`，open-items 登记"键名取自设备端代码，待负责人确认"。

### D9 响应形状

- `commandResponse` 增加 `requestId`（可空）、`expectedStationId`（可空）、`configTarget`（可空）。旧字段不变。

## 错误码汇总（新增）

| HTTP | code | 何时 |
|---|---|---|
| 400 | `request_id_required` / `request_id_invalid` | 配置方法缺/坏 `requestId` |
| 400 | `expected_station_required` | 配置方法缺 `expectedStationId` |
| 409 | `request_id_reused` | 同键不同内容 |
| 409 | `device_station_changed` | 受理时油站关系已变（带 `currentStationId`） |
| 409 | `config_command_in_flight` | 同目标已有未终态命令（带 `commandId`） |
| 409 | `config_commands_disabled` | 已有，新增 `scope` |
| 404 | `command_not_found` | 单条查询 |
| 404 | `device_settings_not_reported` | 整机镜像未上报 |

## Risks / Trade-offs

- 对配置方法是破坏性变更 → 现无任何客户端发送配置方法（已核对 admin/miniapp），只影响手工 curl 验证流程；更新验证清单。
- 白名单开关是"运营动作"，误开等于对该设备客户管理员开放下发 → 仅平台管理员、必填原因、审计、换客户清空。
- `FOR SHARE` 增加建单与换机的串行化 → 两者都是低频人工操作，可接受。
- 迟到结果审计依赖 ingest 回读窗口，窗口外到达的结果仍会丢 → 契约里写明，不宣称全覆盖。

## 跨仓库路线（本变更之后）

| 顺序 | 仓库 / change | 内容 | 依赖 |
|---|---|---|---|
| 1 | cloud `config-command-delivery`（本变更） | 契约 + 实现 | — |
| 2 | admin `device-parameter-settings`（#4） | 设备详情"参数设置/操作记录"；读 `tank-settings` / `device-settings`；单罐表单 → 确认 → 提交 → 轮询 → 回显核对；整机区仅平台管理员；白名单开关；**只读补齐标定表** | 1 |
| 2 | miniapp `tank-settings-remote-apply`（#6） | 设备回报 vs 本机草稿分栏；管理员下发；"参数修正"页并入同一流程；删除旧通用命令页；三语 | 1 |
| 2 | admin + miniapp `stock-manual-entry-ui` | 接入已有补录接口 `POST/DELETE stock-events`（#69），小程序补录页去掉"即将上线"，后台加入口 | 无（云端已有） |
| 3 | android `config-command-acceptance`（#9） | B5–B11、客户管理员、离线/超时/重复投递真机验收；整机多字段部分写入改整批或在 `detail` 写清 | 1、2 任一端可用 |
| 4 | 发布评审 | 总闸常开 + 白名单逐台开放 | 3 |

客户端共同规则（各自规格引用本契约，不复制字段表）：只发改动且开放的字段；密度只读；油品下标按 0–39（#11 已合并）；
`terminal` 决定停止轮询；`succeeded` 后再比对镜像 `reportedAt` 与字段才显示"已生效"；`expired`/超时显示"结果无法确认"，不自动重发。

## Open Questions

1. 【待确认·发布】总闸常开改由白名单控制的时机（D5）。
2. 【待确认·设备端】整机属性键名与单位（D8）；设备是否在整机设置变更后即时重发属性（罐设置已确认即时）。
3. 【选择，可改】`requestId` 作用域含操作人（D1）；不跨目标互斥（D4）；员工不可读整机镜像（D8）。
