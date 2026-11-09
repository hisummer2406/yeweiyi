# 介质库接口契约（#27）

字典只保存名称和归属：新增、改名、停用都**不会**修改任何油罐或设备配置。选用并下发见下文“选用与下发”。

全部接口仅平台管理员，按库内当前角色判定；其他角色 `403 not authorized`。

| 接口 | 说明 |
|---|---|
| `GET /api/media` | `{rows:[介质]}`，含停用项 |
| `POST /api/media` | `{name, nameEn?, scope, customerId?}`；手动新增，`201` 返回介质 |
| `PUT /api/media/:id` | `{name?, nameEn?, enabled?}`；缺省字段不变，归属不可改 |
| `GET /api/devices/:device/media-options` | `{public:[], customer:[]}`；只含启用项，客户取设备当前归属 |

介质：`{id, name, nameEn|null, scope, customerId|null, builtinIndex|null, enabled, updatedAt}`。`nameEn` 为 null 时显示 `name` 原文。

`scope` 为 `public` 时不带 `customerId`；为 `customer` 时必带。`name` 1–20 字符，`nameEn` 最多 40 字符。

| 错误 | code |
|---|---|
| 400 名称不合法 | `invalid_media_name` |
| 400 范围与客户不匹配 | `invalid_media_scope` |
| 400 客户不存在 | `unknown_customer` |
| 404 介质不存在 | `media_not_found` |
| 404 设备不存在 | `device_not_found` |
| 409 同范围同名（公共内，或同一客户内） | `media_name_exists` |
| 409 内置条目不可改名 | `media_builtin_readonly` |

内置编号 1–24 初始化为公共介质。25–31（二厂四种、排水三种）归属待业务核实，暂不进入可选库，旧编号的显示兼容不变。

`config.setTankSettings` 的 `oilTypeIndex` 仅平台管理员可提交；其他角色提交（含与别的字段混合）整条 `403`，不派发。

## 选用与下发

后台只提交介质 ID，复用 `POST /api/devices/:device/commands`：`config.setTankSettings` 的 `args` 带 `mediaId`（可与其他字段同时提交）。幂等、油站复核、在途互斥同配置命令契约。

云端在设备行锁内校验并换算后再下发：

- 介质须启用，且是公共介质或属于设备**当前**客户；否则 `400 media_not_selectable`。
- 内置介质下发 `oilTypeIndex`（1–24）。
- 手动新增的介质占用该设备 32–39 的一个位置，同一介质在同一设备上位置不变，下发 `oilTypeIndex` + `customOilName` + `customOilNameEn`（无翻译时为原名）。8 个位置用尽 `409 media_slots_exhausted`，不覆盖在用位置。
- 直接提交 `oilTypeIndex`、`customOilName`、`customOilNameEn` 一律 `400 media_id_required`。

设备在同一命令内写入名称和编号：任一失败都不生效，名称持久化，重启后保留。

**固件能力**：新固件上报 client attribute `customMediaSync = 1`。未上报的设备，手动新增的介质和编号 ≥ 18 的内置介质（旧固件 18–25 是自定义位置）一律 `409 media_sync_unsupported`，不下发。

设备换客户时清空其位置映射；再次选用时重新下发名称。设备彻底删除时映射随设备行删除。

受理和 ACK 不代表生效，以 `cmdResult` 和设备回报的 `oilTypeIndex` 为准。

业务记录沿用设备上报的 `oilType` 名称和 `oilTypeIndex` 快照，改名、停用不改写历史；第一版未记录介质稳定 ID。
