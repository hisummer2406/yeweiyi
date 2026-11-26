# 介质库接口契约（#27）

字典只保存名称和归属：新增、改名、停用都**不会**修改任何油罐或设备配置。选用并下发见下文“选用与下发”。

字典维护接口（`GET/POST/PUT /api/media`）仅平台管理员，按库内当前角色判定；其他角色 `403 not authorized`。
**选用**（`media-options` 与提交 `mediaId`）自 2026-11-14 起平台管理员与客户管理员都可以（用户决定：所有修改只限管理员，员工只读）；
客户管理员只能对本客户设备操作，别的客户的设备与不存在的设备同一 `403`；员工一律 `403`。

| 接口 | 说明 |
|---|---|
| `GET /api/media` | `{rows:[介质]}`，含停用项 |
| `POST /api/media` | `{name, nameEn?, scope, customerId?}`；手动新增，`201` 返回介质 |
| `PUT /api/media/:id` | `{name?, nameEn?, enabled?}`；缺省字段不变，归属不可改 |
| `GET /api/devices/:device/media-options` | `{public:[], customer:[], customMediaSync, oilColorSync, tankHeightLowTempSync, slotsUsed, slotsTotal}`；只含启用项，客户取设备当前归属；`oilColorSync` 为设备是否支持远程改颜色，`tankHeightLowTempSync` 为是否支持远程改罐高与低温报警（#65） |

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

编号 10 按用户决定统一为煤油（迁移 000042），沿用原米糠油的编号与介质 ID。旧固件仍可能显示米糠油；
后台、小程序按编号显示的旧记录也会显示为煤油，记录原文不改写。32–39 自定义介质逻辑不变。
安卓本地选择框仅开放编号 0–10 和原“空白”32，其他旧编号仍可显示、接受云端配置。

迁移 000042 遇到同名公共介质（含停用项）时，仅将内置条目命名为“煤油（内置）”，仍重名则追加序号。
down 对“米糠油”采用同一规则；自定义介质名称、ID 及位置映射保持原样。检查名称与改名在同一事务表锁内完成。
上述重名规则的 up/down 和自定义数据保留已在隔离 PostgreSQL 16 验证通过（2026-11-16），未部署到云端。

`config.setTankSettings` 的介质只有管理员（平台、客户）可改；员工提交任何字段都整条 `403`，不派发。
客户管理员选用尚无位置的专属介质时，与平台管理员一样按下文规则分配/回收位置（只回收不在用的位置）。

## 选用与下发

后台只提交介质 ID，复用 `POST /api/devices/:device/commands`：`config.setTankSettings` 的 `args` 带 `mediaId`（可与其他字段同时提交）。幂等、油站复核、在途互斥同配置命令契约。

云端在设备行锁内校验并换算后再下发：

- 介质须启用，且是公共介质或属于设备**当前**客户；否则 `400 media_not_selectable`。
- 内置介质下发 `oilTypeIndex`（1–24）。
- 手动新增的介质占用该设备 32–39 的一个位置，同一介质在同一设备上位置不变，下发 `oilTypeIndex` + `customOilName` + `customOilNameEn`（无翻译时为原名）。8 个位置都已映射时，回收一个不在用的位置给新介质（被回收的介质下次选用时重新分配位置、重新下发名称）；都在用才 `409 media_slots_exhausted`，不覆盖在用位置。
  “在用”：某罐镜像的 `oilTypeIndex` 是该位置；或有指向该位置的 `config.setTankSettings` 命令未终态，或已 `succeeded`/`expired` 但该罐镜像还早于命令签发。`rejected`/`failed` 不算。`media-options` 的 `slotsUsed` 只计在用位置。
- 直接提交 `oilTypeIndex`、`customOilName`、`customOilNameEn` 一律 `400 media_id_required`。

设备在同一命令内写入名称和编号：任一失败都不生效，名称持久化，重启后保留。

**固件能力**：新固件上报 client attribute `customMediaSync = 1`。未上报的设备，手动新增的介质和编号 ≥ 18 的内置介质（旧固件 18–25 是自定义位置）一律 `409 media_sync_unsupported`，不下发。

设备换客户时清空其位置映射；再次选用时重新下发名称。设备彻底删除时映射随设备行删除。

受理和 ACK 不代表生效，以 `cmdResult` 和设备回报的 `oilTypeIndex` 为准。

业务记录沿用设备上报的 `oilType` 名称和 `oilTypeIndex` 快照，改名、停用不改写历史；第一版未记录介质稳定 ID。
