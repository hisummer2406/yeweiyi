# 油罐配置：真机验证清单（#70 + #71）

模拟设备（`internal/devicesim`）只证明云端与"按设备端文档实现的设备"能跑通；下面这些**只有真机能证实**。
设备端 PR #38 已于 2026-11-02 合并。**2026-11-08 首次真机下行验证通过 B1–B4 与重启持久化**（见文末"验证记录"），
其余各项仍未验证。

> 注意：生产没有独立测试环境。`control.allowConfigCommands` 是全局开关，打开期间所有客户管理员都能经 API 下发配置；
> 验证时只在短窗口内打开（改 `docker-compose.prod.yml` 并部署），只对测试设备发命令，完成后立即关回并部署。

## 前置

1. 真机刷入 `feature/thingsboard-device-protocol` 构建，用 ThingsBoard 设备凭据连上测试环境；云端 `ingest` 在运行，
   设备已登记在 `devices` 表并分配给一个客户（客户管理员或平台管理员各准备一个账号）。
2. 设备至少启用 1 个罐（罐高 > 0）。

## A. 镜像（#70）：设备上报 → 云端

| # | 操作 | 期望现象 | 要看什么 |
|---|---|---|---|
| A1 | 设备开机连网 | 1 分钟内 `GET /api/devices/:device/tank-settings` 有该罐一项 | ThingsBoard 设备页"客户端属性"应有 `tank1.tankHeightMm` 等键 |
| A2 | 对照设备设置页 | 罐高、油品序号、四级报警阈值、修正值、密度、容积与设置页一致 | `oilTypeIndex` 是否与设备下拉框序号一致；`density` 实际数值（0.56 还是 560）|
| A3 | 看值的 JSON 类型 | 整数是整数、小数是数字 | 若某键以字符串上报，日志出现 `skipped unusable attributes`（该键被云端丢弃）|
| A4 | 现场在设置页改一个阈值并保存 | 属性被重发，镜像在 `ingest.tankSettingsInterval`（默认 1 分钟）内更新，`reportedAt` 前进 | 设备是否在**变更时**就重发，还是只在重连时重发 |
| A5 | 设备断网 | 接口仍返回最后一次镜像，`reportedAt` 不变 | |
| A6 | 记录 `sensorLengthMm` | 设备文档说本地设置页存不进去 | 它的值是否有意义；是否一直缺失 |

## B. 下发（#71）：云端 → 设备

用平台管理员或客户管理员发 `POST /api/devices/:device/commands`，`method=config.setTankSettings`，先发**一个**字段：
`{"tank":1,"alarmHighLevelMm":<比当前值大 50>}`。

| # | 操作 | 期望现象 | 要看什么 |
|---|---|---|---|
| B1 | 发上面的命令 | 同步返回 `202`，`status=received`（ACK）| **ACK 时机**：设备文档说"received 在写库前后"？看 `ackedAt` 与 `sentAt` 间隔 |
| B2 | 等 `cmdResult` | 命令变 `succeeded`，`resultDetail={"tank":1,"updated":["alarmHighLevelMm"]}` | **cmdResult 线上字段名**是 `detail` 而不是文档里的 `data`；是否走遥测 `cmdResult` key；ThingsBoard 双向 RPC 是否只收到一条响应 |
| B3 | 看设备设置页 | 高液位阈值已是新值 | 设备缓存是否刷新（报警检测读缓存）|
| B4 | 等镜像 | 约 2 秒内设备重发属性，镜像随之更新为新值 | **属性回显时间**到底多久；`reportedAt` 前进 |
| B5 | 发一个越界值（如 `alarmHighLevelMm` > 罐高）| 命令 `rejected`，`errorCode=INVALID_PARAMS`，`reason` 是中文说明 | 设备是否真的拒绝而不是写入 |
| B6 | 发违反顺序的值（低 > 当前高）| `rejected`，`reason` 说明顺序 | 设备端按合并后的值校验 |
| B7 | 发 `tankShape` / `tankHeightMm` | **云端直接 400**（`field_not_editable`），不会到设备 | 如需验证设备自身的拒绝，用 ThingsBoard 控制台直接下发 |
| B8 | 设备切到"现场手动模式" 再发命令 | `rejected`，`errorCode=LOCAL_MODE_LOCKED` | `localControlMode` 属性是否随之变 true |
| B9 | 设置页开着时远程改，再现场点保存 | 远程值被覆盖（已知限制）| 确认现象，镜像随之回到现场值 |
| B10 | 再试多字段：`alarm*` + `density` + `oilTypeIndex` + `capacityL` | 全部 `updated` | `density` 写入后设置页显示的单位/数值是否合理 |
| B11 | 平台管理员发 `config.setDeviceSettings`（改一个出入库阈值）| `succeeded`，`detail.updated` | 设备上报的 `outStockHeightThresholdMm` 等顶层属性随之更新（云端暂不镜像）|

## C. 需要设备端确认/修正的点（验证中如发现差异，更新文档）

- 字段清单文档把 `cmdResult` 的结果载荷写成 `data`，实际代码和云端协议是 `detail`。
- `density` 的单位（设置页标 kg/m³，库里存 0.56）。
- 自定义油品（32–39；旧固件 18–25）为空时能否下发 `oilTypeIndex`。
- 属性变更是否即时重发（A4）。

## 验证记录

### 2026-11-08：ZCT000001 1 号罐高液位报警（B1–B4 + 重启持久化）

- 环境：生产（`api.zhongchengelec.com`），云端镜像 `sha-e086600`（`ALLOW_CONFIG_COMMANDS=true` 的临时窗口，完成后由 `72af967`
  关回）；设备 `ZCT000001`（客户"小程序测试账号"，油站"小程序测试站点"），固件版本未记录。操作人 `admin`（平台管理员）。
- 生产此前从未发过任何命令（`control_commands` 为空），这是第一条真机下行命令。

| 步骤 | commandId | 时间（UTC） | 结果 |
|---|---|---|---|
| 600 → 610 下发 | `036a22b3-e8c3-4e7d-aace-0bbb7057edc7` | sent 14:00:05.585 / acked 14:00:05.586 / finished 14:00:05.680 | B1 ✅ `202 received`；B2 ✅ `succeeded`，`detail={"tank":1,"updated":["alarmHighLevelMm"]}`，原因"罐设置已更新" |
| 设备设置页 | | | B3 ✅ 现场确认显示 610 |
| 属性回显 | | 设备重发 14:00:07.684；云端镜像 14:01:02 | B4 ✅ 执行后约 2 秒重发属性，镜像按 1 分钟轮询更新为 610，`reportedAt` 前进 |
| 重启设备 | | | ✅ 重启后设置页仍为 610（持久化） |
| 610 → 600 改回 | `513afe36-3964-4e2b-a986-0b9cb2dc1c60` | sent 14:14:58.830 / acked 14:14:58.831 | ✅ `succeeded` |

结论：

- A-1 的终态上行已在真机上成立：终态走遥测 `cmdResult`，ThingsBoard 双向 RPC 只收到一条 ACK，命令不再卡在 `received`。
- `cmdResult` 线上字段名是 `detail`（C 节第一条确认）。
- 设备在属性变更时即时重发（C 节最后一条 / A4 的远程部分确认），不必等重连。
- ACK 在写库前就发出（`ackedAt` 比 `sentAt` 晚约 1 毫秒，终态晚约 95 毫秒）。

未覆盖：B5–B11、客户管理员角色、离线/超时路径。
