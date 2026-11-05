## Why

「客户 → 油站 → 设备」是三级管理关系，但现在的入口散在两个分区：客户在 `/ops/customers`，油站在 `/console/stations`，设备的客户归属在 `/ops/devices`，
设备的油站归属在 `/console/stations` 的「设备分配」页签**和** `/ops/devices` 的行内操作各有一份（重复入口）。
客户管理员进不了 `/ops/*`，所以管理动作又不能全放进运维分区；而「设备监控」是只读查看，把管理塞进去会让同一个菜单混用两种角色权限。
设备登记（`/ops/devices`）与设备的油站分配也是同一批设备的两件事，却要在两个分区之间来回切换。
同时油站这一层目前只用于管理，监控页（设备总览、报警、出入库）按油站查看设备的需求没有承接。

## What Changes

- **新增菜单组「资产管理」**（顶层菜单，与「设备监控」并列，路由前缀仍是 `/console/assets/*`），按层级排列三项：
  客户（仅平台管理员）→ 油站（平台管理员与客户管理员）→ 设备（平台管理员与客户管理员）。
  整组只对平台管理员和客户管理员可见（现有 `canManageStations` 改名为 `canManageAssets`，语义不变）。
- **设备管理合并成一页**：`/console/assets/devices` 同时承接 `/ops/devices` 的**登记设备**与**转移/分配客户**（仅平台管理员可见这两个操作）、
  以及原 `/console/stations`「设备分配」页签的**分配油站**（平台管理员与客户管理员）。设备分配油站只有这一个入口。
  客户管理员在这一页只能查看设备和分配油站，不能登记、不能转移客户。
  登记设备成功后，在同一页提供「分配油站」动作（跳过再去找设备那一行），不再跨页跳转。
  `/console/stations` 的页签页收敛成只剩油站增删改，路径改为 `/console/assets/stations`。
- **客户管理搬家**：`/ops/customers` 页面整体搬到 `/console/assets/customers`（行为不变，仍仅平台管理员）。
- **`/ops` 只留平台独有的事**：账号管理、配对密钥、审计日志。`/ops/devices` 退出菜单，旧地址重定向到 `/console/assets/devices`；`/ops` 默认落点改为账号管理。
- **监控侧按油站筛选/分组**：设备总览加油站筛选，选「全部油站」时按油站分段显示；报警/入库/出库的设备选择器（`DevicePicker`）加油站下拉，用来缩小设备候选。
  云端没有油站接口（旧版）或设备行没有 `station_id` 时，筛选与分组不出现，页面和现在一致。
- **记录查询升为一级菜单**：原先在「设备监控」下面要点三层（设备监控 → 记录查询 → 报警记录），现在与「设备监控」「资产管理」并列，URL（`/console/records/*`）和页面不变；菜单文案键由 `menu.console.records*` 改为 `menu.records*`。
- 旧地址保留重定向：`/console/stations` → `/console/assets/stations`，`/ops/customers` → `/console/assets/customers`，`/ops/devices` → `/console/assets/devices`（收藏夹、已有说明文档不失效）。
- **不做**：油站管理员角色（yeweiyi-cloud#44）、邀请与员工管理、按用户授权；云端不改（本变更只重排前端入口并新增前端筛选）；
  不动 `/ops/users`（账号管理）的位置。

## Capabilities

### New Capabilities
- `console-asset-management`: 「资产管理」菜单组及其三个页面（客户/油站/设备）的信息架构、角色可见性、设备页（含登记、转移客户、分配油站、登记后分配油站）、`/ops` 菜单的精简与旧地址重定向。
- `console-station-filter`: 监控页（设备总览、报警/入库/出库的设备选择）按油站筛选与分组，以及云端不支持油站时的退化。

### Modified Capabilities
- `ops-device-registration`: 登记能力的位置与入口变更——设备列表与登记表单从 `/ops/devices` 搬到 `/console/assets/devices`（仅平台管理员有登记入口）；列表增加「所属客户」（仅平台管理员）「所属油站」列（现行 spec 写的是不显示客户归属，已与实际不符）；非平台管理员访问登记入口的拒绝方式随之改变。登记规则本身（出厂编号、幂等登记、冲突处理、客户选择）不变。

## Impact

- 路由与权限：`config/routes.ts`（新增顶层「资产管理」组、`/ops/customers` 与 `/console/stations` 重定向、`/ops` 菜单精简）、`src/access.ts`（`canManageStations` → `canManageAssets`）、`src/locales/{zh-CN,en-US}/{menu,pages}.ts`。
- 页面：`src/pages/ops/customers` 搬到 `src/pages/console/assets/customers`；`src/pages/ops/devices`（含登记、转移客户弹窗与 `errors`）与 `src/pages/console/stations` 的设备分配页签合并为 `src/pages/console/assets/devices`；`src/pages/console/stations` 余下油站部分搬到 `assets/stations`；
  `src/pages/console/devices`（总览）与 `src/pages/console/components/DevicePicker.tsx` 增加油站筛选；新增共享的油站筛选组件与取数 hook；对应测试随文件移动并补充。
- 后端：**不需要改动**。依赖已合并的 yeweiyi-cloud #53（`GET /api/stations`、`PUT /api/devices/:device/station`）与设备行的 `station_id`。
  `GET /api/stations` 对三种角色都可调用且按角色限定可见范围（客户用户只看到自己设备所在的油站），筛选因此对所有角色可用。
- 依赖：不新增 npm 依赖。
- 文档：`CLAUDE.md` 的目录结构与状态说明；`openspec/specs/ops-device-registration/spec.md` 的 Purpose 段仍写着 `/ops/devices`（delta 无法修改 Purpose，归档时手工更新）；本变更落地后需要重写 PR #8 的描述（路径与入口都变了）。
- 联调限制（如实记录）：与 PR #8 相同——页面逻辑目前只被 mock 服务的单测覆盖，没有在浏览器里对着含 #53 的云端走过。
