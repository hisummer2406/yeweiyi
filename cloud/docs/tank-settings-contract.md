# 油罐配置镜像契约（cloud#70，第一阶段）

小程序要展示油罐配置。**设备是配置的权威来源**：联网和变更时把罐设置页内容作为 ThingsBoard 客户端属性
（`tank{N}.*`）上报；云端读出后存一份镜像到 `tank_settings`，接口只读。本阶段云端**不写任何配置值**；
小程序改配置并经云端下发是第二阶段（#71），不在此契约内。

**状态：键名与单位未冻结。** 键名取自设备端 `docs/远程配置下发-字段清单.md`（分支
`feature/thingsboard-device-protocol`，PR #38 未合并、零真机验证），待设备端确认，见
`docs/protocol-v1-open-items.md` §E。共享 fixture：`fixtures/protocol/v1/client_attributes_tank_settings.json`。

## 数据流

```text
设备 -- 客户端属性 tank{N}.* --> ThingsBoard --(internal/ingest 轮询)--> tank_settings --> GET /api/devices/:device/tank-settings
```

- 每个设备的订阅建立时立即读一次（即设备连接后），之后每 `ingest.tankSettingsInterval`（默认 `1m`）重读一次；
  设备改配置后云端最迟晚一个间隔看到。读失败不改动现有镜像。
- 整行以最新上报为准；`reported_at` 没变则不写库。设备停用某个罐，旧行保留（最后一次镜像）。
- 键类型不对（如 `density` 是字符串、`tankHeightMm` 带小数）的值被跳过并记日志，不影响同一罐的其他键。
  罐号超出 1–16 的属性被忽略。云端不补默认值：设备没报的键为 `null`，与上报的 `0`（= 不启用）不同。

## 接口

`GET /api/devices/:device/tank-settings`

可见范围同 `GET /api/devices/:device/tanks`：先过设备级检查（跨客户、不存在、无权对非平台管理员是同一个 403），
`customer_user` 只看到被授权的罐，管理员看到镜像里的全部罐。读业务库，设备离线时照常返回。从未上报过则
`tanks` 为空数组。

```json
{
  "deviceName": "ZCT000001",
  "tanks": [
    {
      "tank": 1,
      "reportedAt": "2026-10-17T08:21:05.123Z",
      "tankHeightMm": 2500,
      "sensorLengthMm": 2600.5,
      "tankShape": 0,
      "oilType": "92#",
      "oilTypeIndex": 3,
      "alarmHighHighLevelMm": 2300,
      "alarmHighLevelMm": 2200,
      "alarmLowLevelMm": 300,
      "alarmLowLowLevelMm": 0,
      "alarmHighWaterMm": 50,
      "alarmHighTempC": 0,
      "alarmLowTempC": -10,
      "levelCorrectionMm": -1.5,
      "waterCorrectionMm": 0,
      "tempCorrectionC": 0.5,
      "density": 0.56,
      "capacityL": 30000
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `reportedAt` | 该罐配置属性在 ThingsBoard 的最近更新时间（设备上报时刻），RFC 3339 UTC |
| `tankShape` | 0 卧罐 / 1 立罐（覆盖 #45 的云端部分） |
| `oilTypeIndex` | 0 未设置，1–31 内置，32–39 自定义（旧固件 18–25 为自定义，见 #11）；**以它为准** |
| `oilType` | 设备界面语言下的名称，仅供展示。**云端不内置名称表**（产品决定 2026-10-17） |
| `alarm*Mm` / `alarmHighTempC` | `0` = 不启用 |
| `alarmLowTempC` | 设备上报，但设备端低温检测从未被调用，**改了不生效** |
| `capacityL` | 油罐公称容积，L（#71 起镜像，因可远程修改） |
| `density` | **原样透传，单位未与设备端对齐**（设置页标 kg/m³，实际存 0.56 这类值）；不要据此换算 |
| 任一字段 `null` | 设备未上报该键 |

不提供：低水位报警、物料颜色（设备端没有，也不上报）。标定表见下一节，不在本接口。

## 授权与错误

- 无权 / 跨客户 / 设备不存在：`403`（与 `/tanks` 完全相同，先授权后查存在，防设备名枚举）。
- 配置中的客户（#60）下非平台管理员账号同样被拒。
- 路由登记在 `internal/api/tenancy_test.go` 的 `deviceScopedRoutes`。

## 实现位置

`db/migrations/000033_tank_settings.*`、`db/queries/tank_settings.sql`、`internal/protocol/tank_settings.go`
（解析）、`internal/ingest/tanksettings.go`（同步）、`internal/api/tanksettings.go`（接口）。

---

# 第二阶段：经云端下发配置（cloud#71）

小程序改配置 → 云端校验授权、预检参数、审计 → 走现有控制状态机下发 → 设备应用后**重新上报属性**，镜像随之更新。
云端不乐观更新镜像；小程序在命令未终态、或镜像还不等于提交值时显示"待生效"。

## 接口

沿用 `POST /api/devices/:device/commands`（`202`、`terminal` 标志、审计、`GET .../commands` 轮询，**ACK 不等于成功，
成功以 `cmdResult` 为准**），新增两个方法：

> **2026-11-08 起（#3）**：两个配置方法的提交还必须带 `requestId` 与 `expectedStationId`；幂等、找回、
> 受理时油站复核、同目标在途互斥、整机设置镜像见 [config-command-contract.md](config-command-contract.md)。

| 方法 | 权限 | 说明 |
|---|---|---|
| `config.setTankSettings` | 平台管理员、客户管理员（本客户设备） | 改单个罐。员工（`customer_user`）一律 403 |
| `config.setDeviceSettings` | **仅平台管理员** | 改整机出入库判定阈值；客户管理员 403 |

不属于物理动作，不受 `allowPhysicalCommands` 影响；但受独立闸门 `control.allowConfigCommands`（环境变量
`ALLOW_CONFIG_COMMANDS`，默认 false；生产自 2026-11-09 常开）约束：关闭时返回 `409 code=config_commands_disabled`（`scope=deployment`，
见 [config-command-contract.md](config-command-contract.md) §1.4）。旧方法 `config.setAlarmThresholds` 保留不动（语义不同：
要求四级严格递减、不能设 0），新代码请用 `config.setTankSettings`。

请求（`reason` 必填，同其他命令）：

```json
{ "method": "config.setTankSettings",
  "args": { "tank": 1, "alarmHighLevelMm": 2700, "density": 0.7 },
  "reason": "调整高液位报警阈值" }
```

### `config.setTankSettings` args

只改出现的字段；任一字段不合法则整条拒绝，什么都不写。`tank` 必填，1–16。

| 字段 | 类型 | 范围 | 说明 |
|---|---|---|---|
| `alarmHighHighLevelMm` `alarmHighLevelMm` `alarmLowLevelMm` `alarmLowLowLevelMm` | int, mm | ≥ 0（设备另限 ≤ 罐高） | 0 = 不启用；非 0 的必须 低低 < 低 < 高 < 高高 |
| `alarmHighWaterMm` | int, mm | ≥ 0（设备另限 ≤ 罐高） | 0 = 不启用 |
| `alarmHighTempC` | int, ℃ | −50～100 | 0 = 不启用 |
| `levelCorrectionMm` `waterCorrectionMm` | number, mm | −100～100 | |
| `tempCorrectionC` | number, ℃ | −10～10 | |
| `density` | number | > 0 且 ≤ 2 | **单位未对齐，原样写入**，云端不换算 |
| `oilTypeIndex` | int | 0～39 | 1–31 内置，32–39 自定义；自定义油品为空时是否允许由设备决定（产品未定）。旧固件把 18–25 当自定义，向旧固件下发 18–31 的含义不同（#11） |
| `capacityL` | int, L | 0～1,000,000 | 只影响空容/可添油量计算 |

**认识但不开放远程修改的字段会被拒绝，不是忽略**：`alarmLowTempC`（设备低温检测从未被调用）、`tankHeightMm`、
`probeAddress`、`tankShape`、`sensorLengthMm`。不认识的字段云端也拒绝（拼错字段名不会静默无效）；设备端对未知字段
是忽略并列在 `cmdResult.detail.ignored`。

### `config.setDeviceSettings` args

`inStockHeightThresholdMm` / `outStockHeightThresholdMm`（int，1～10000 mm）、`inStockTimeThresholdSec` /
`outStockTimeThresholdSec`（int，1～86400 s）；至少一个。当前值读 `GET /api/devices/:device/device-settings`（#3 起镜像，见
[config-command-contract.md](config-command-contract.md) §4）。

## 错误

- 参数不合法：`400 {"code":"invalid_args","error":"...","fields":[{"field","code","message"}]}`，`fields[].code` 为
  `tank_required` `unknown_field` `field_not_editable` `invalid_value` `no_editable_fields` `level_order_violation`
  `invalid_args`。校验在授权之后、记录/发送之前：无权的调用者看不到参数问题，被拒的参数不产生命令行、不发给 ThingsBoard
  （写一条 `denied` 审计）。
- 设备拒绝（ACK 阶段，`status=rejected`）：`errorCode` + `reason` 在命令里，例如 `INVALID_PARAMS`（超过罐高、
  与设备现存阈值合并后顺序不对）、`EXPIRED`。（`LOCAL_MODE_LOCKED` 在协议里保留，但**设备当前没有"现场手动模式"这一状态**，
也不上报 `localControlMode`，实际不会出现，#9。）小程序直接展示 `reason`。
- 成功：`status=succeeded`，`resultDetail` 为设备的 `detail`：`{"tank":1,"updated":[...],"ignored":[...]}`。
  线上字段名是 `detail`（设备端字段清单文档里写的 `data` 不对，见 open-items §E）。

## 已接受的限制

- **并发覆盖**：现场设置页开着时远程改，现场点"保存"会用页面里的旧值覆盖（设备端已知，产品决定接受，命令不带版本）。
  所以"待生效 / 已生效"以设备回传的属性为准，不以命令成功为准。
- 云端预检只看到命令里的值；"≤ 罐高"、与设备现存阈值的顺序校验由设备做，拒绝原因照实返回。

## 参考实现与测试

设备侧规则的参考实现是 `internal/devicesim/settings.go`（按设备端字段清单独立手写，**不**复用
`protocol.ValidateArgs`，两份实现互相对照）。端到端测试：`internal/ingest/config_commands_test.go`。
共享 fixture：`rpc_request_set_tank_settings.json`、`cmd_result_set_tank_settings.json`。
**真机验证清单**见 [tank-settings-device-verification.md](tank-settings-device-verification.md)。

---

# 罐体标定表（cloud#14）

设备把每个启用罐的标定表（高度→体积，1cm 步长）作为客户端属性 `tank{N}.calibrationHeightMm` /
`calibrationVolumeL`（两个等长数组）**只在 MQTT 连接建立时**发布一次。云端与罐配置同一套机制镜像（`tank_calibration`
表，迁移 000035）：`internal/ingest` 在读客户端属性时一并解析，`protocol.ParseTankCalibration` 校验（严格升序、体积不减、
至少 2 点）后才落库；校验失败的曲线记日志并忽略，**不会覆盖已有的好镜像**。

`GET /api/devices/:device/tanks/:tank/calibration`

```json
{ "deviceName": "ZCT000001", "tank": 2, "reportedAt": "2026-10-17T08:21:05.123Z",
  "pointCount": 3, "heightMm": [10, 20, 30], "volumeL": [100, 200, 300.5] }
```

- 可见范围：设备级检查 → 罐级检查（`customer_user` 无该罐授权、或罐号越界 → `403 tank_not_authorized`）→ 管理员罐号越界
  `400 tank_invalid`。未上报 `404 calibration_not_reported`。读业务库，设备离线照常返回。
- **滞后**：设备保持在线时不会重发，现场改了标定表后云端要等设备下次重连才更新；`reportedAt` 是这份曲线被设备发布的时间。
- 不含罐型分类/尺寸（设备端不持久化）；写（小程序生成罐表并下发）无协议支持，不在此范围。
- 键名与数组形状仍待设备端确认（#14）；共享 fixture `client_attributes.json` 已含这两个键并有契约测试。
