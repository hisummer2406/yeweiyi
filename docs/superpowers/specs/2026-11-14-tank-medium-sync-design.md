# 罐介质设置三端同步（介质类型 · 密度 · 颜色）设计

日期：2026-11-14　状态：待用户审批　关联：#27（介质库）、#28（修正值）、#43（小程序参数页）

## 1. 目的

设备上每个罐有三项介质设置：**介质类型、介质密度、颜色**。用户要求：设备上有的介质设置，后台和小程序都要有，
并且都能修改、同步到设备。

**权限铁律（用户 2026-11-14）**：所有修改操作只有管理员（平台管理员、客户管理员）可以做；员工（`customer_user`）
在后台和小程序都只有只读权限。

## 2. 现状

| 设置 | 设备本地 | 设备上报（client attribute） | 设备接受远程写 | 后台 | 小程序 |
|---|---|---|---|---|---|
| 介质类型 | 下拉（内置 + 32–39 自定义名） | `tankN.oilTypeIndex` / `oilType` | 是（#27，含自定义名） | 仅平台管理员可选 | 只读（c8138b7） |
| 密度 | 输入框，标注 kg/m³ | `tankN.density` | 是（0 < v ≤ 2） | 只读 | 只读，标注 kg/m³ |
| 颜色 | 20 色固定色板（`ColorFragment.yanseId(1..20)`），存 ARGB int `oilColor` | **否** | **否** | 无 | 无 |

- 介质库：`media` 字典 + `device_media_slots`，客户端只提交 `mediaId`，云端换算（契约 `cloud/docs/media-library-contract.md`）。
  `control.authorizeFields` 拒绝非平台管理员提交介质字段；`GET /api/devices/:device/media-options` 仅平台管理员。
- 员工不能发任何命令：`control.roleAllows` 对 `customer_user` 恒 false；后台员工无网页入口；小程序 `param-editor` 按
  `isReadOnlyRole` 只读。
- **密度单位已可确定**：设备算重量用 `体积(L) × density = kg`（`MainActivity`、`ShiftRepositoryImpl`），存的是 0.56 这类值，
  所以真实单位是 **kg/L（= g/cm³）**，设备设置页的 "kg/m³" 标注是错的。

## 3. 目标 / 非目标 / 反目标

**目标**
1. 后台、小程序都显示每罐的介质类型、密度、颜色（设备回报值）。
2. 平台管理员和客户管理员在两端都能修改这三项，经设备回报确认生效。
3. 员工两端只读，云端同样拒绝。

**非目标**
- 客户管理员维护介质库（新增/改名/停用介质仍仅平台管理员；客户管理员只"选用"）。
- 在后台/小程序的罐图里用设备颜色画油（可后续做）。
- 任意自定义颜色（设备自定义取色器已被注释掉，只开放 20 色色板）。

**反目标（即使能跑也算失败）**
- 员工能通过任何入口（界面或直接调接口）改到设备设置。
- 乐观显示：未经设备回报就把新值当成当前值。
- 客户管理员选到别的客户的专属介质。

## 4. 方案比较

| 方案 | 说明 | 取舍 |
|---|---|---|
| **A. 扩展现有 `config.setTankSettings`（选定）** | 介质继续走 `mediaId`；密度开放；新增 `oilColor` 字段 | 复用提交保护（`requestId`、油站复核、在途互斥、`cmdResult`、回报一致判定），设备端一个命令内原子生效 |
| B. 新建 `config.setTankMedium` | 三项打包成独立命令 | 要重做一遍提交保护与状态机，没有收益 |

选 A：现有命令已原子、已真机走通（ZCT000001），三项可同次提交。

## 5. 设计

### 5.1 协议（cloud ↔ android）

- 新增罐属性 **`tankN.oilColor`**：整数 `0xRRGGBB`（0–16777215），设备按 `oilColor & 0xFFFFFF` 上报。
  `oilColor == 0`（从未设置）时**不上报**该键（两端显示"—"）。
  *（我的决定：用 RGB 整数而不用色板序号——设备库里存的是颜色值，旧数据可能不在色板上，整数能无损上报；
  用整数而不用 "#RRGGBB" 串，是为了直接复用三端现有的"数值字段"草稿、差异、回显判定。）*
- 20 色色板放进共享 fixture `fixtures/protocol/v1/oil_color_palette.json`，四端各自的常量都用测试对照它。
- `config.setTankSettings` 新增可写字段 **`oilColor`**：值必须是 20 色色板之一（色板常量在 cloud、admin、miniapp、android 各一份，
  均对照共享 fixture；云端预检，设备是最终裁决）。设备写入 `0xFF000000 | rgb`，与本地选色一致，持久化。
- **设备能力位**：旧固件对未知字段是**忽略**（`SetTankSettingsHandler.validate` 返回 null，和别的字段一起提交时命令仍
  `succeeded`，`oilColor` 只出现在 `ignored` 里），而 `oilColor==0` 又不上报，所以不能靠"有没有上报颜色"判断新旧。
  新固件在整机属性上报 **`oilColorSync = 1`**（同 `customMediaSync` 的做法，`DeviceSettingsAttributeBuilder`）。
  云端：未上报该能力的设备提交 `oilColor` → `409 color_sync_unsupported`，不派发；后台/小程序在该设备上把颜色显示为只读并提示
  "设备固件暂不支持远程修改颜色"。*（我的决定。）*
- 小程序补上对 `cmdResult.ignored` 的提示（后台已有），防御其他字段也出现"部分成功"。*（我的决定。）*
- `density`：语义改为 **g/cm³（kg/L）**，范围不变 `0 < v ≤ 2`，协议值不变，只改文档与界面单位。
  设备本地输入框没有范围检查，现场可能存在按 kg/m³ 填的值（如 840）：界面照原值显示并标"数值超出 g/cm³ 常见范围，请核对"；
  只发改动字段，所以不改密度时不会挡住其他字段；要改则必须填 `0 < v ≤ 2`。*（我的决定。）*
- 共享 fixture `fixtures/protocol/v1/` 增加含 `oilColor` 的 tank settings 属性与命令样例，两端测试同 PR 通过。
- 按 `cloud/CLAUDE.md`："设备端负责人提出、云端只消费"的例外不适用——这是云端发起的新字段，**需 android 负责同事在 PR 中确认**
  并在 `docs/protocol-v1-open-items.md` 登记。

### 5.2 云端（cloud/）

1. `protocol/tank_settings.go`：解析 `oilColor`（0–16777215 整数，非法值跳过）；`density` 注释改为 g/cm³。
2. `protocol/config_args.go`：`oilColor` 加入可写字段，校验色板；`density` 规则不变。
3. `control.authorizeFields`：**客户管理员**可提交介质字段（`mediaId`）；员工仍在 `roleAllows` 被拒（不变）。
   直接提交 `oilTypeIndex`/`customOilName*` 仍 `400 media_id_required`（所有角色）。
4. `GET /api/devices/:device/media-options`：放开给客户管理员（按 `user_may_access_device` 判定，跨客户/不存在返回同一 403，
   约束 6/9），只返回公共介质 + 设备**当前**客户的专属介质（现逻辑即如此）。员工 403。
5. `resolveMediaArgs` 的归属校验沿用（公共或设备当前客户），客户管理员因此选不到别的客户的介质。
   客户管理员选用尚无位置的介质时，同样会按现有规则分配/回收 32–39 位置（只回收不在用的位置，不覆盖在用位置），
   不对客户管理员另设限制。*（我的决定：回收只动空闲位置，对客户无副作用。）*
6. `oilColor` 能力位校验（见 5.1），新增错误码 `color_sync_unsupported` 写进错误码契约。
7. 审计：沿用命令审计；`media-options` 读取不审计（与现状一致）。
8. 更新 `docs/media-library-contract.md`（权限段）、`docs/tank-settings-contract.md`（`oilColor`、密度单位）、
   `cloud/CLAUDE.md` 介质库条目。

### 5.3 后台（admin/）

- 参数设置（`SettingsForm` / `utils/deviceConfig.ts`）：
  - 介质：平台管理员 **和客户管理员** 都可选（分组：公共 / 本客户专属）；
  - 密度：改为可编辑，单位 g/cm³，本地预检 `0 < v ≤ 2`；
  - 颜色：新增字段，20 色色块单选，显示当前回报色；设备未回报时显示"—"且仍可选。
- 回报一致判定沿用 `useCommandTracker`（`oilColor`、密度都按数值比较）。
- 员工无后台入口（不变）。

### 5.4 小程序（miniapp/）

- `param-editor`（储罐设置）：
  - 介质：管理员可选（调用 `media-options`，分组），员工只读显示（撤销 c8138b7 的"一律只读"，改为按角色）；
  - 密度：管理员可编辑，单位改 g/cm³（现标 kg/m³ 一并纠正）；
  - 颜色：管理员 20 色色块单选，员工只读色块。
- 提交走现有 `configSubmit`（`mediaId`、`density`、`oilColor` 可与其他字段同次提交）。
- `TANK_READONLY` 去掉 density；`TANK_SEND_FIELDS` 加 `density`、`oilColor`、`mediaId`。

### 5.5 设备端（android/）——由我同步开发

1. `TankSettingsAttributeBuilder`：上报 `tankN.oilColor`（`rgb & 0xFFFFFF`，0 不报）；密度注释改单位。
   `DeviceSettingsAttributeBuilder`：上报能力位 `oilColorSync = 1`。
2. `SetTankSettingsHandler`：新增 `oilColor` 字段，校验在 20 色色板内，写 `0xFF000000|rgb`，与其他字段同一事务、同一 `cmdResult`。
3. 色板抽成常量（`ColorFragment.yanseId` 复用同一份），避免两处漂移。
4. 设置页密度标注 kg/m³ → g/cm³（各语言 strings）。
5. 单测读共享 fixture。

## 6. 验收与分工

- 我：cloud/admin/miniapp/android 开发与自动化测试（`go test ./... -p 1`、admin `npm run test/tsc`、miniapp 测试、android 单测）。
- **需提醒用户安排 android 同事**：
  1. 审核 android 部分代码与协议新增字段 `oilColor`；
  2. **真机单独验收**：远程改介质（内置、自定义名）、密度、颜色各一次 + 三项同次提交；重启后保留；设备本地改后云端回报更新；
     新固件上报 `oilColorSync`；旧固件设备上颜色为只读。
- 上线顺序：云端/后台/小程序可先于固件上线（没有能力位的设备颜色只读，介质与密度照常可改），无需等固件。
- 云端/后台/小程序在生产（唯一环境）上的联调由用户安排；我不宣称真机已验证。

## 7. 留给实现者

- 色板常量的具体文件位置、四端命名。
- 后台/小程序色块组件样式（色块 + 选中态，20 色可一屏放下）。
- 小程序 `media-options` 请求的缓存/失败降级（失败时介质只读并提示，不阻断其他字段）。
