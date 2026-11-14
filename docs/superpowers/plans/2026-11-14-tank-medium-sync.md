# 罐介质设置三端同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 介质类型、密度、颜色三项罐设置在后台和小程序都可显示、由管理员（平台/客户）修改并同步到设备，员工只读。

**Architecture:** 扩展现有 `config.setTankSettings`：介质继续走 `mediaId`（放开给客户管理员），密度开放可写，新增 `oilColor`
（整数 0xRRGGBB，20 色色板）。设备上报 `tankN.oilColor` 与整机能力位 `oilColorSync=1`；云端对无能力位设备拒绝 `oilColor`。

**Tech Stack:** Go + PostgreSQL（sqlc、golang-migrate）、React/Ant Design Pro（vitest）、微信小程序 TS（node check 脚本）、Android Java（JUnit）。

**Spec:** `docs/superpowers/specs/2026-11-14-tank-medium-sync-design.md`

## Global Constraints

- 权限铁律：修改只允许 `platform_admin`、`customer_admin`；`customer_user` 两端只读，云端 `roleAllows` 拒绝（不改）。
- 色板（序号 1–20 ↔ RGB，来自 `ColorFragment.yanseId`），唯一来源 `cloud/fixtures/protocol/v1/oil_color_palette.json`：
  `0xFF0000, 0xFFFF00, 0x0000FF, 0x008000, 0x800080, 0x254D2A, 0x6F1200, 0x000034, 0xF4B2D6, 0x4C0B71,
  0x00456C, 0x00ADEF, 0x342F93, 0xF4F08D, 0xF1645D, 0xFF00CD, 0x413100, 0x139300, 0xC1CD23, 0xFF9900`
- `oilColor` 上报：整数 `rgb & 0xFFFFFF`，设备存值为 0 时不上报；写入：必须在色板内，设备存 `0xFF000000 | rgb`。
- 能力位：整机属性 `oilColorSync = 1`；云端未见该值时含 `oilColor` 的提交 → `409 color_sync_unsupported`，不派发。
- 密度：单位 g/cm³（kg/L），可写范围 `0 < v ≤ 2`，协议值不变。
- 介质：客户只提交 `mediaId`；直接提交 `oilTypeIndex`/`customOilName*` 一律 `400 media_id_required`（所有角色）。
- 提交标题中文、带目录前缀；**仅在用户要求时提交**（本计划的 Commit 步骤在用户同意后执行）。
- android 代码由我写；交付时必须单列"请 android 同事审核 + 真机单独验收"。

## Review Focus

1. 员工令牌直接调 `GET /media-options` 或 POST 带 `mediaId`/`oilColor` 的命令 → 403，界面无入口（Task 3、5、6 测试）。
2. 客户管理员提交别的客户的专属 `mediaId` → `400 media_not_selectable`（Task 3 测试）。
3. 旧固件（无 `oilColorSync`）上 `oilColor` 与其他字段混合提交 → 整条 409、不派发；界面颜色只读（Task 3、5、6 测试）。
4. 设备回报密度 840（按 kg/m³ 填的旧值）→ 原样显示并提示核对；不改密度时不进入 args（Task 5、6 测试）。
5. 设备回报的颜色不在色板（旧自定义取色）→ 照常显示色块并标"非标准色"，仍可改选色板色；云端解析接受任意 0–0xFFFFFF（Task 1、5、6 测试）。

---

### Task 1: 共享 fixture 与云端协议层

**Files:**
- Create: `cloud/fixtures/protocol/v1/oil_color_palette.json`（`{"colors":[16711680, ...20 个]}`，顺序即序号 1–20）
- Modify: `cloud/fixtures/protocol/v1/client_attributes_tank_settings.json`（加 `tank1.oilColor: 16711680`）、
  `client_attributes_device_settings.json`（加 `oilColorSync: 1`）、`rpc_request_set_tank_settings.json`（args 加 `oilColor`）、
  `MANIFEST.json`（重算校验和，`verify-manifest.sh` 通过）、`README.md`（列出新文件）
- Modify: `cloud/internal/protocol/tank_settings.go`、`config_args.go`、`device_settings.go`
- Test: `cloud/internal/protocol/tank_settings_test.go`、新增 `cloud/internal/protocol/oil_color_test.go`

**Interfaces:**
- Produces: `protocol.AttrOilColor = "oilColor"`、`protocol.AttrOilColorSync = "oilColorSync"`、
  `TankSettings.OilColor *int32`、`DeviceSettings.OilColorSync *int32`、`protocol.OilColorPalette []int32`、
  `protocol.IsPaletteColor(v int32) bool`

- [ ] **Step 1: 写失败测试**
  - `TestOilColorPalette_MatchesSharedFixture`：读 fixture，`reflect.DeepEqual(OilColorPalette, fixture.colors)`，长度 20。
  - `TestParseTankSettings_OilColor`：`tank1.oilColor=16711680` → `*OilColor==16711680`；`0x1000000`、`-1`、`1.5`、`"red"` 均跳过（进 skipped）；
    `0x123456`（不在色板）照收。
  - `TestValidateArgs_TankSettings` 增例：`{"tank":1,"oilColor":16711680}` 通过；`{"tank":1,"oilColor":1193046}` → `invalid_value`；
    `{"tank":1,"density":0.84}` 通过（已有规则）。
  - `TestParseDeviceSettings_OilColorSync`：`oilColorSync:1` → `*OilColorSync==1`。
  - 共享 fixture 用例（`TestParseTankSettings_SharedFixture`、`TestValidateArgs_SharedRequestFixtureIsValid`）随 fixture 更新断言新字段。
- [ ] **Step 2: 运行确认失败** — `cd cloud && go test ./internal/protocol/` → FAIL（未定义符号）。
- [ ] **Step 3: 实现** — `isSettingsField`/`set` 加 `AttrOilColor`（整数分支，额外要求 `0 ≤ v ≤ 0xFFFFFF`）；
  `tankSettingRules` 不放 `oilColor`，在 `validateConfigArgs` 中单独校验（整数且 `IsPaletteColor`），计入 `editable`；
  `AttrDensity` 注释改为"g/cm³（kg/L），设备设置页 kg/m³ 标注有误"；`device_settings.go` 解析 `oilColorSync`（同 `customMediaSync`）。
- [ ] **Step 4: 运行确认通过** — `go test ./internal/protocol/` PASS；`bash fixtures/protocol/v1/verify-manifest.sh` OK。
- [ ] **Step 5: Commit** — `cloud: 协议新增罐颜色 oilColor 与能力位 oilColorSync，密度单位定为 g/cm³`

### Task 2: 云端镜像存储与读取接口

**Files:**
- Create: `cloud/db/migrations/000041_tank_oil_color.up.sql` / `.down.sql`
  （`tank_settings.oil_color INT`、`device_settings.oil_color_sync INT`，中文 COMMENT）
- Modify: `cloud/db/queries/tank_settings.sql`、`device_settings.sql`；`sqlc generate` 产物
- Modify: `cloud/internal/ingest/tanksettings.go`（`SaveTankSettings` 写 `OilColor`，`SaveDeviceSettings` 写 `OilColorSync`）
- Modify: `cloud/internal/api/tanksettings.go`（响应加 `OilColor *int32 \`json:"oilColor"\``；`Density` 注释改单位）
- Test: `cloud/internal/api/tank_settings_test.go`、ingest 对应测试

**Interfaces:**
- Consumes: Task 1 的 `TankSettings.OilColor`、`DeviceSettings.OilColorSync`
- Produces: `GET /api/devices/:device/tank-settings` 每罐 `oilColor: number|null`；`db.DeviceSetting.OilColorSync pgtype.Int4`

- [ ] **Step 1: 写失败测试** — `TestTankSettings_OilColorRoundTrip`：上报属性含 `tank1.oilColor=16711680` → 接口返回 `oilColor:16711680`；
  未上报 → `null`。device settings 存 `oilColorSync=1` 可读回。
- [ ] **Step 2: 运行确认失败** — `go test ./internal/api/ -run TankSettings -p 1` FAIL。
- [ ] **Step 3: 实现** — 迁移、查询、`sqlc generate`；迁移做一次 down/up 往返。
- [ ] **Step 4: 运行确认通过** — 同上 PASS；`go build ./... && go vet ./...`。
- [ ] **Step 5: Commit** — `cloud: 镜像保存并返回罐颜色与颜色同步能力`

### Task 3: 云端权限与校验（客户管理员可选介质、颜色能力位）

**Files:**
- Modify: `cloud/internal/control/control.go`（`authorizeFields`）
- Modify: `cloud/internal/api/media.go`（`deviceMediaOptionsHandler`、`resolveMediaArgs`，新增 `deviceSyncsColor`）
- Modify: `cloud/internal/api/control.go:157-161`（`config.setTankSettings` 先 `resolveMediaArgs` 再颜色能力检查）
- Modify: `cloud/docs/media-library-contract.md`、`cloud/docs/tank-settings-contract.md`、`cloud/docs/error-codes-contract.md`、
  `cloud/docs/protocol-v1-open-items.md`（登记 `oilColor`/`oilColorSync` 待 android 负责人确认）、`cloud/CLAUDE.md` 介质库条目
- Test: `cloud/internal/control/submission_test.go`、`cloud/internal/api/media_test.go`

**Interfaces:**
- Consumes: Task 2 的 `OilColorSync` 列
- Produces: `GET /api/devices/:device/media-options` 对 `platform_admin`/`customer_admin` 返回
  `{public, customer, customMediaSync, oilColorSync: bool, slotsUsed, slotsTotal}`；错误码 `color_sync_unsupported`（409）

- [ ] **Step 1: 写失败测试**
  - 改 `TestConfigSubmission_MediumIsPlatformAdminOnly` → `TestConfigSubmission_MediumIsAdminOnly`：customer_admin 提交 `oilTypeIndex`（control 层）通过 tier，
    customer_user 仍 `ErrForbidden`。
  - `TestMedia_CustomerAdminSelects`：客户管理员对本客户设备 `mediaId`=公共介质、=本客户专属 → 202/200 且派发的 args 含换算后的 `oilTypeIndex`；
    =别的客户专属 → `400 media_not_selectable`。
  - `TestMedia_OptionsAccess`：客户管理员本客户设备 200 且只含公共+本客户；别的客户设备、不存在设备 → 同一 403 body；员工 → 403。
  - `TestMedia_DirectIndexRefusedForAllAdmins`：客户管理员直接发 `oilTypeIndex` → `400 media_id_required`。
  - `TestConfigSubmission_OilColorNeedsCapability`：设备无 `oil_color_sync` → `{"tank":1,"oilColor":16711680,"capacityL":100}` 得 `409 color_sync_unsupported`，
    命令表无新行；设 `oil_color_sync=1` 后同请求受理。
  - 现有 `TestMedia_PlatformAdminOnly` 中 options 的断言按新规则改。
- [ ] **Step 2: 运行确认失败** — `go test ./internal/control/ ./internal/api/ -run 'Media|ConfigSubmission' -p 1` FAIL。
- [ ] **Step 3: 实现**
  - `authorizeFields`：放行 `RoleCustomerAdmin`（员工已被 `roleAllows` 拒，函数保留给未来字段级限制；注释说明 2026-11-14 用户决定）。
  - `resolveMediaArgs`：删除"非平台管理员改写成 oilTypeIndex=0"分支，两种管理员走同一解析；员工保持交给 control 拒绝
    （员工带 `mediaId` 时仍改写为 `oilTypeIndex:0` 以便 control 统一 403 + 审计）。
  - `deviceMediaOptionsHandler`：换掉 `requireMediaAdmin`——平台管理员照旧；客户管理员先 `UserMayAccessDevice`，否则 `refuseObject` 同一 403；
    员工 `refuse` 403。响应加 `oilColorSync`。
  - `deviceSyncsColor(ctx, q, device) (bool, error)`，与 `deviceSyncsMedia` 同形；`control.go` 在 args 含 `oilColor` 且为假时
    `apiError(c, 409, "color_sync_unsupported", ...)`（放在授权/存在性检查之后，遵守约束 6）。
  - 文档按 spec §5.2 更新。
- [ ] **Step 4: 运行确认通过** — `go test ./... -p 1`、`go build ./... && go vet ./...` 全过（跳过项须说明原因）。
- [ ] **Step 5: Commit** — `cloud: 客户管理员可选用介质，颜色修改需设备能力位`

### Task 4: 设备端（android）

**Files:**
- Create: `android/app/src/main/java/com/sansen/yeweiyi/logic/OilColors.java`（`PALETTE_RGB int[20]`、`argbOf(int index1to20)`、
  `isPaletteRgb(int rgb)`、`toArgb(int rgb)`）
- Modify: `android/app/src/main/java/com/sansen/yeweiyi/fragment/ColorFragment.java`（`yanseId` 改为调用 `OilColors.argbOf`）
- Modify: `.../logic/thingsboard/TankSettingsAttributeBuilder.java`（上报 `oilColor`，0 不报；密度注释改单位）
- Modify: `.../logic/thingsboard/DeviceSettingsAttributeBuilder.java`（`KEY_OIL_COLOR_SYNC = "oilColorSync"`）
- Modify: `.../manager/ThingsBoardManager.java:538` 附近（同时上报 `KEY_OIL_COLOR_SYNC, 1`）
- Modify: `.../logic/thingsboard/rpc/handler/SetTankSettingsHandler.java`（`validate` 加 `case "oilColor"`）
- Modify: `app/src/main/res/values*/strings.xml` 中 `density_change`/`tank_density` 带 kg/m³ 的文案改 g/cm³（无单位的不动）
- Test: `TankSettingsAttributeBuilderTest`、`SetTankSettingsHandlerTest`、新增 `OilColorsTest`（读共享 fixture，经 `FixtureLoader`）

**Interfaces:**
- Consumes: 共享 fixture `oil_color_palette.json`
- Produces: 设备上报 `tankN.oilColor`、`oilColorSync=1`；`config.setTankSettings` 接受 `oilColor`

- [ ] **Step 1: 写失败测试**
  - `OilColorsTest.paletteMatchesSharedFixture`；`argbOf(1) == 0xFFFF0000`。
  - `TankSettingsAttributeBuilderTest`：`oilColor=0xFFFF0000` → 属性 `tank1.oilColor == 16711680`；`oilColor=0` → 无该键。
  - `SetTankSettingsHandlerTest`：`{"tank":1,"oilColor":16711680}` 成功且存 `0xFFFF0000`；`oilColor:1193046` → 拒绝、库不变；
    `{"tank":1,"oilColor":16711680,"density":3}` → 整条拒绝、两者都不写。
- [ ] **Step 2: 运行确认失败** — `cd android && ./gradlew testDebugUnitTest --tests '*OilColors*' --tests '*TankSettings*'` FAIL
  （工具链见记忆：本机 JDK 11 损坏，需临时 JDK）。
- [ ] **Step 3: 实现** — `case "oilColor"`：`requireInt(field, value, 0, 0xFFFFFF)`，`!OilColors.isPaletteRgb(v)` 抛 `IllegalArgumentException`，
  `Change(s -> s.setOilColor(OilColors.toArgb(v)))`。
- [ ] **Step 4: 运行确认通过** — 同命令 PASS，另跑全量 `./gradlew testDebugUnitTest`。
- [ ] **Step 5: Commit** — `android: 罐颜色上报与远程写入，密度单位改 g/cm³`（PR 中请 android 同事审核）

### Task 5: 后台（admin）

**Files:**
- Modify: `admin/src/services/ant-design-pro/typings.d.ts`（`TankSettings.oilColor: number|null`；`MediaOptions.oilColorSync: boolean`）
- Create: `admin/src/utils/oilColor.ts`（`OIL_COLOR_PALETTE: readonly number[]`、`isPaletteColor(v)`、`toCss(v): string` → `#RRGGBB`）
- Modify: `admin/src/utils/deviceConfig.ts`（`FieldKind` 加 `'color'`；`TANK_FIELDS` 中 `density` 改 `editable: true`、`unit: 'g/cm³'`；
  新增 `{ key:'oilColor', kind:'color', min:0, max:0xFFFFFF, editable:true }`；`checkArgs` 对 `color` 校验 `isPaletteColor`；
  `densityLooksOff(v: number|null): boolean`（`v > 2`））
- Modify: `admin/src/pages/console/device-detail/SettingsTab.tsx`（`media-options` 对 `canManageAssets` 的两种管理员都请求；`canEdit` 去掉 `isPlatform` 限制）
- Modify: `admin/src/pages/console/device-detail/SettingsForm.tsx`（`kind==='color'` 渲染 20 色色块单选 + 当前色；`oilColorSync` 为假时只读并提示；
  密度超范围提示）
- Modify: `admin/src/locales/zh-CN/pages.ts`、`en-US/pages.ts`（`field.oilColor`、`color.unsupported`、`color.nonStandard`、`density.check`，
  删除"密度单位尚未与设备确认"那句）
- Modify: `admin/CLAUDE.md`（远程配置/介质库段：密度可写、颜色、客户管理员可选介质）
- Test: `admin/src/utils/deviceConfig.test.ts`、新增 `admin/src/utils/oilColor.test.ts`、`SettingsTab.test.tsx`

**Interfaces:**
- Consumes: Task 2/3 的 `oilColor`、`oilColorSync`、放开后的 `media-options`

- [ ] **Step 1: 写失败测试**
  - `oilColor.test.ts`：常量等于 `../../../cloud/fixtures/protocol/v1/oil_color_palette.json`；`toCss(16711680)==='#FF0000'`。
  - `deviceConfig.test.ts`：`changedArgs` 含 `oilColor` 改动；`checkArgs` 拒绝 `1193046`；`density` 未改时（当前 840）不进 args；
    `densityLooksOff(840)===true`、`densityLooksOff(0.84)===false`。
  - `SettingsTab.test.tsx`：客户管理员看到介质下拉与色块可选；`oilColorSync:false` 时色块禁用并显示 `color.unsupported`；
    回报颜色 `0x123456` 显示 `color.nonStandard`。
- [ ] **Step 2: 运行确认失败** — `cd admin && npm run test -- deviceConfig oilColor SettingsTab` FAIL。
- [ ] **Step 3: 实现**（`echoState` 已按 `===` 比较数值，颜色/密度无需特判）。
- [ ] **Step 4: 运行确认通过** — `npm run test`、`npm run tsc`、`npm run lint` 全过。
- [ ] **Step 5: Commit** — `admin: 管理员可改介质、密度、颜色`

### Task 6: 小程序（miniapp）

**Files:**
- Create: `miniapp/miniprogram/utils/oilColor.ts`（同 Task 5 的三个导出）
- Modify: `miniapp/miniprogram/utils/remoteConfig.ts`（`TANK_SEND_FIELDS` 加 `{form:'density', cloud:'density', integer:false, min:0, max:2}`
  （`buildArgs` 对 density 额外要求 `> 0`）；`TANK_READONLY` 去掉 density；`Unit` 的 `'kg/m³'` 改 `'g/cm³'`；
  新增 `buildMediumArgs(mediaId?: number, oilColor?: number, mirror): Record<string, number>`（未改不放）；
  新增 `ignoredFields(command): string[]`（读 `resultDetail.ignored`））
- Modify: `miniapp/miniprogram/utils/remoteConfigApi.ts`（`getMediaOptions(device): Promise<MediaOptions>`）
- Modify: `miniapp/miniprogram/components/param-editor/param-editor.{ts,wxml,wxss}`（管理员：介质 picker（公共/本客户分组，
  `media-options` 失败则只读并提示，不阻断其他字段）、密度输入、色块单选；员工：三项只读；`oilColorSync` 假时颜色只读）
- Modify: 命令结果展示处（`utils/configSubmit.ts` 的成功提示）追加 `ignoredFields` 提示
- Modify: `miniapp/miniprogram/locales/{zh,en,es}.ts`；`miniapp/CLAUDE.md`
- Test: `miniapp/scripts/remote-config.check.mjs`、`param-editor.check.mjs`、新增 `scripts/oil-color.check.mjs` 并加入 `package.json` 的 `check`

**Interfaces:**
- Consumes: Task 2/3 接口；`utils/roles.ts` 的 `isReadOnlyRole`

- [ ] **Step 1: 写失败测试**
  - `oil-color.check.mjs`：常量等于共享 fixture；`toCss`。
  - `remote-config.check.mjs`：`buildArgs` 填 `density:'0.84'` → `{density:0.84}`；`'0'`、`'2.5'` → `range`；未填且镜像 840 → 不出现；
    `buildMediumArgs(5, undefined, {oilColor:null})` → `{mediaId:5}`；颜色与镜像相同 → 不出现；
    `ignoredFields({resultDetail:{ignored:['oilColor']}})` → `['oilColor']`。
  - `param-editor.check.mjs`：员工角色三项只读；管理员 + `oilColorSync:false` → 颜色只读。
- [ ] **Step 2: 运行确认失败** — `cd miniapp && npm run check` FAIL。
- [ ] **Step 3: 实现**
- [ ] **Step 4: 运行确认通过** — `npm run check`、`npm run typecheck` 全过。
- [ ] **Step 5: Commit** — `miniapp: 管理员可改介质、密度、颜色，员工只读`

### Task 7: 收尾核对与交付清单

- [ ] **Step 1:** 全量：`cloud: go test ./... -p 1 && go vet ./...`；`admin: npm run test && npm run lint`；`miniapp: npm run check && npm run typecheck`；
  `android: ./gradlew testDebugUnitTest`；`bash cloud/fixtures/protocol/v1/verify-manifest.sh`。
- [ ] **Step 2:** 对照 spec §3 反目标逐条确认有测试覆盖（员工只读、无乐观更新、跨客户介质）。
- [ ] **Step 3:** 给用户的交付说明中单列：
  1. 请 android 同事审核 Task 4 代码与新协议字段 `oilColor`/`oilColorSync`；
  2. 请 android 同事真机单独验收：远程改介质（内置、自定义名）、密度、颜色各一次＋三项同次提交；重启保留；设备本地改后云端回报更新；
     新固件上报 `oilColorSync`；旧固件设备颜色只读。
  3. 云端/后台/小程序可先于固件上线（无能力位的设备颜色只读）。
