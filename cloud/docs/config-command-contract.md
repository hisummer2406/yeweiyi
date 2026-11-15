# 远程配置命令：提交保护契约（#3，config-command-delivery）

本文是后台（#4）与小程序（#6）接入远程配置下发的云端契约。方法本身（`config.setTankSettings` /
`config.setDeviceSettings` 的参数、校验、设备拒绝原因）见 [tank-settings-contract.md](tank-settings-contract.md) 第二阶段，
`config.setTankTable` 见本文 §8；
本文只定义**提交保护**：幂等、找回、受理时复核、在途互斥、开关、迟到结果与整机设置镜像。
设计与取舍见 `openspec/changes/config-command-delivery/design.md`。

标注：【需求】产品/议题要求；【选择】云端在需求内的取舍，可改；【待确认】需负责人决定。

**状态（2026-11-08）**：云端已实现，真实 PostgreSQL + ThingsBoard + 设备模拟器测试通过；**未在真机上验证**，生产总闸仍关闭。

## 1. 提交：`POST /api/devices/:device/commands`

配置方法在原有字段（`method`、`args`、`reason`）之外**必须**带两个字段（其他方法可不带，行为不变）：

```json
{ "method": "config.setTankSettings",
  "args": { "tank": 1, "alarmHighLevelMm": 610 },
  "reason": "调整高液位报警",
  "requestId": "k3f9a2-0001",
  "expectedStationId": 12 }
```

| 字段 | 规则 |
|---|---|
| `requestId` | 1–64 个 `[A-Za-z0-9_-]`。【选择】客户端在**打开确认弹窗时**生成一次；同一次确认的所有重试复用它；用户改了表单再确认就换新的 |
| `expectedStationId` | 表单打开时设备所在油站的 id；设备不在任何油站时显式传 `null`。**缺字段与 `null` 不同**：缺字段 400 |

### 1.1 幂等【需求】

- 同一 (操作人, 设备, `requestId`) 至多对应一条命令。【选择】作用域含操作人：两个管理员恰好生成同一 id 不会互相"找回"。
- 内容（`method`、`args` 的 JSON 值、修剪后的 `reason`、`expectedStationId`）相同的重放 → **`200`** + 该命令**当前**状态，
  **不再次派发**，写审计 `control.replay`。首次受理仍是 `202`。
- 同 `requestId` 不同内容 → `409 request_id_reused`。
- 并发的重复提交只建一条、只派发一次。
- 重放前按数据库当前状态重新授权：账号停用、撤权、换客户后，旧 `requestId` 拿不到命令，错误与首次提交相同。
- 保留期：随命令永久保存；设备彻底删除时一并删除。

### 1.2 受理时复核油站【需求】

受理事务内按"油站行 → 设备行"加共享锁，复核设备当前所在油站 = `expectedStationId`；不同 →
`409 {"code":"device_station_changed","currentStationId":<id|null>}`，不建单、不派发。锁序与换机（`replace-device`）一致，
二者串行化，不会死锁。

**剩余风险**：建单提交之后、RPC 发出之前（毫秒级）仍可能换机。命令按设备名派发，所以仍去往**用户确认的那台设备**；
只是它此刻可能已不在那个油站。

### 1.3 同一目标至多一条未终态命令【需求】

目标：`config.setTankSettings` 为 `tank:<罐号>`，`config.setDeviceSettings` 为 `device`。同一设备同一目标已有
`pending`/`sent`/`received`/`executing` 的命令 → `409 {"code":"config_command_in_flight","commandId":"..."}`（`commandId` 是在途
那条，可能缺省：它恰好在查询前结束时，客户端直接重试即可）。【选择】不同罐之间、罐与整机之间可并行。命令终态或过期后自动释放。

### 1.4 开关【需求】

只有部署级 `control.allowConfigCommands`（`ALLOW_CONFIG_COMMANDS`）。关闭时 `409 {"code":"config_commands_disabled","scope":"deployment"}`；
判定在角色之后：员工一律先得 403。**生产自 2026-11-09 起常开**（用户决定：管理员打开参数页即可直接修改）。
曾有的设备级白名单（迁移 000038、`PUT /api/devices/:device/config-commands`、设备行 `config_commands_enabled`）已于同日删除（迁移 000039）。

旧配置方法 `config.setAlarmThresholds` / `config.setTank` / `config.setProbe` 不再经本接口下发（#18 F1，2026-11-09）：判定在角色之后、
不论开关，一律 `410 {"code":"method_retired","use":"config.setTankSettings"}`，写 `denied` 审计，不派发。原因：其参数云端读不出目标罐，
无法共用本节总闸与 §1 的 `requestId`、油站复核、同罐在途互斥。协议方法表与设备端 handler 不变（兼容影响：仅云端 REST；admin/小程序
均未调用）。

### 1.5 错误码（新增部分）

| HTTP | `code` | 何时 |
|---|---|---|
| 400 | `request_id_required` / `request_id_invalid` | 配置方法缺或格式不对的 `requestId` |
| 400 | `expected_station_required` / `expected_station_invalid` | 配置方法缺 `expectedStationId`，或不是正整数/`null` |
| 409 | `request_id_reused` | 同 `requestId` 不同内容 |
| 409 | `device_station_changed` | 受理时油站关系已变（带 `currentStationId`） |
| 409 | `config_command_in_flight` | 同目标已有未终态命令（带 `commandId`） |
| 410 | `method_retired` | 旧配置方法 `config.setAlarmThresholds` / `setTank` / `setProbe`（#18 F1） |
| 409 | `config_commands_disabled` | 部署级开关关闭，`scope=deployment` |

既有错误（`403`、`invalid_args`+`fields`、`reason_required`、`command_delivery_unconfirmed` 等）不变。

### 1.6 响应新增字段

命令对象（POST 与各 GET 同形）新增可空字段：`requestId`、`expectedStationId`、`configTarget`（如 `"tank:1"`）。

## 2. 查询

| 接口 | 说明 |
|---|---|
| `GET /api/devices/:device/commands/:commandId` | 单条命令。不存在、不属于该设备、对调用者不可见、id 不是 UUID → 同一 `404 command_not_found` |
| `GET /api/devices/:device/commands?requestId=<id>` | 调用者**本人**这次提交，列表形状 `{"commands":[...]}`，0 或 1 条 |

可见范围同命令列表：先设备级检查（防枚举 403），员工看不到任何命令。

## 4. 整机设置镜像：`GET /api/devices/:device/device-settings`

```json
{ "deviceName": "ZCT000001", "reportedAt": "2026-11-08T14:00:07.684Z",
  "inStockHeightThresholdMm": 20, "inStockTimeThresholdSec": 60,
  "outStockHeightThresholdMm": 30, "outStockTimeThresholdSec": 0 }
```

- 设备把整机设置作为顶层客户端属性上报（设备端 `DeviceSettingsAttributeBuilder`，键名同 `config.setDeviceSettings` 参数）；
  `internal/ingest` 与罐设置同周期（`ingest.tankSettingsInterval`）写入 `device_settings`。云端不改值。
- `0` = 设备未配置，原样保存；`null` = 未上报或类型不对（被跳过并记日志）。
- 可读：平台管理员、本客户客户管理员；员工 `403`（整机数据）。从未上报 `404 device_settings_not_reported`。
- 【待确认·设备端】键名与单位；整机设置变更后是否即时重发属性（罐设置已于 2026-11-08 真机确认即时重发）。
  共享 fixture：`fixtures/protocol/v1/client_attributes_device_settings.json`。

## 5. 迟到的命令结果

命令已终态（多为 `expired`）后才到的 `cmdResult`（`succeeded`/`failed`/`rejected`，且与已存状态不同）写一条审计
`control.result_late`（`outcome=failed`，detail 含设备报告的状态、错误码、原因、`detail` 与命令当前状态），**命令状态不变**，
每条命令至多一条。后台"操作记录"可经 `GET /api/audit?device=` 看到。设备上报别的设备的命令只记日志。
限制：`ingest` 的回读只覆盖未过期命令，过期后才到的结果只能经实时订阅捕获。

## 6. 客户端共同规则

1. 读实际值（`tank-settings` / `device-settings` 与 `reportedAt`），表单只提交**改动且开放**的字段；密度本期只读。
2. 确认弹窗生成 `requestId`，带上表单打开时的 `expectedStationId`。
3. 网络超时：用**同一 `requestId`** 重发或用 `?requestId=` 查找回；**不得换新 id 自动重发**。
4. 轮询 `GET .../commands/:commandId`，以 `terminal` 停止；`received` 不是完成。
5. `rejected`/`failed` 显示错误码对应的明确结论与 `errorReason`（设备拒绝不是"结果无法确认"）；只有 `expired` 才是
   "结果无法确认"，且不得拼接 ACK 阶段的"已受理"。旧固件的拒绝经 `cmdResult` 到达（见协议 §6.6 兼容规则）。
6. `succeeded` 后再比对镜像：`reportedAt` 晚于命令 `finishedAt` 且字段等于提交值才显示"已生效"；否则"等待设备回报"或"回报值不一致"。
6. `expired`、`command_delivery_unconfirmed`：显示"结果无法确认，请核对设备当前值"，不表示设备没执行。
7. `device_station_changed`：重新载入油站与设备，丢弃旧表单。`config_command_in_flight`：跳到在途命令。
8. 现场设置页保存会覆盖远程值（已接受的限制），"已生效"只表示读到了一致的回报。

## 7. 发布

2026-11-09 起生产 `ALLOW_CONFIG_COMMANDS=true`，没有设备白名单。真机只验证过单字段 `config.setTankSettings`（2026-11-08），其余字段、
整机设置、失败与离线路径仍待真机验收（#9）。物理命令（泵/阀/重启）仍关闭。

## 8. 罐表远程生成：`config.setTankTable`（#49）

**状态（2026-11-14）**：三端同 PR 实现；字段待设备端负责人确认（open-items §H）；**未真机验证**。

只下发罐型和尺寸，设备按现场同一公式生成罐表并整表覆盖该罐。提交保护与 `config.setTankSettings` 相同（`requestId`、
`expectedStationId` 必填），权限 `PermAdmin`（客户管理员、平台管理员），在途目标 `tank:<n>` 与罐设置共用。

| `shape` | 必填字段（mm） | 范围 |
|---|---|---|
| `horizontal` 卧罐 | `diameterMm`、`lengthMm`、`headMm`（单侧封头，0 = 平封头） | 100～20000；`headMm` 0～20000 |
| `vertical` 立罐 | `heightMm`、`diameterMm` | 100～20000 |
| `cuboid` 长方体 | `heightMm`、`lengthMm`、`widthMm` | 100～20000 |

`tank` 为 1～16 的整数。缺字段、非数字、越界、不属于该罐型的字段一律 `400`（`fields[]` 逐项说明），不派发。
设备 `succeeded` 的 `detail` 为 `{tank, shape, pointCount, maxHeightMm, maxVolumeL}`；写入后设备重发标定属性，
客户端以 `GET /api/devices/:device/tanks/:tank/calibration` 的 `reportedAt` 晚于命令受理时间、点数与最后一点和本地计算一致来确认回显。
