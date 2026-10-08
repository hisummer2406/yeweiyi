## 1. 权限、路由与菜单

- [x] 1.1 `src/access.ts`：`canManageStations` 改名 `canManageAssets`（判定不变），更新引用与 `access` 相关测试
- [x] 1.2 `config/routes.ts`：在 `/console` 之后新增顶层「资产管理」组 `/console/assets`（`access: canManageAssets`），子项 customers（`access: canPlatformAdmin`）→ stations → devices，组入口重定向到第一个可见项
- [x] 1.3 `config/routes.ts`：`/ops` 去掉 devices、customers 菜单项，默认重定向改为 `/ops/users`；新增重定向 `/ops/devices` → `/console/assets/devices`、`/ops/customers` → `/console/assets/customers`、`/console/stations` → `/console/assets/stations`，并删除原 `/console/stations` 路由条目
- [x] 1.4 `src/locales/{zh-CN,en-US}/menu.ts`：新增 `menu.assets`、`menu.assets.customers|stations|devices`；删除 `menu.ops.devices`、`menu.ops.customers`、`menu.console.stations`；中英文键保持完全一致
- [x] 1.5 测试：三种角色下菜单与直达地址的可见性/403（含客户项仅平台管理员）、三条旧地址重定向、`/console/assets` 与 `/ops` 的落点、`/ops` 菜单只剩账号管理/配对密钥/审计日志
- [x] 1.6 `config/routes.ts`：`/console/records`（报警/入库/出库）从「设备监控」组移出成顶层「记录查询」；`menu.console.records*` 文案键改名 `menu.records*`；路由测试覆盖层级与顺序

## 2. 页面搬家（先搬后改，保持行为不变）

- [x] 2.1 `git mv src/pages/ops/customers src/pages/console/assets/customers`，修正 import 与相对路径，原测试随文件移动并通过
- [x] 2.2 `src/pages/console/stations` 的 `StationsTab/StationFormModal/errors`（及测试）移到 `src/pages/console/assets/stations/`；入口 `index.tsx` 去掉 `Tabs`，直接渲染油站列表
- [x] 2.3 `git mv src/pages/ops/devices src/pages/console/assets/devices`（含 `RegisterDeviceModal`、`TransferDeviceModal`、`errors` 及测试），修正 import；`AssignStationModal`（及测试）移入同目录；`pages.ops.devices.*`、`pages.console.stations.*` 文案键名不改；此步结束时 `/console/assets/devices` 的行为应与原 `/ops/devices` 完全一致，测试全绿
- [x] 2.4 删除原 `DevicesTab`（设备分配页签）：其列与「分配油站」操作已由合并后的设备页覆盖；删除后 `src/pages/console/stations` 目录不应残留

## 3. 设备页的合并与角色差异

- [x] 3.1 设备页「分配油站」对平台管理员与客户管理员都提供：`customer_id === null` 不给；`customer_id` 缺失（客户管理员的行）仍给；`station_id` 缺失显示「未知」；清空油站发 `{stationId: null}`
- [x] 3.2 设备页角色差异：「所属客户」列、工具栏「登记设备」、行内「分配客户 / 转移客户」只对 `platform_admin` 渲染（`useAccess().canPlatformAdmin`）；客户管理员只有「分配油站」；测试覆盖两种角色下的列与按钮
- [x] 3.3 登记成功（201 与 200）的提示带「分配油站」动作：点击打开 `AssignStationModal`，目标为 `{device_name, customer_id}`（取登记响应的 `deviceName`、`customerId`），候选为该客户的油站；不点不发请求，不自动弹出；失败路径（400/403/409）不出现该动作；客户没有油站时弹窗显示无候选并不能提交；测试覆盖以上各路径
- [x] 3.4 `/ops/devices` 不再有页面：确认旧路由条目只剩重定向，没有残留的 `src/pages/ops/devices`

## 4. 监控侧油站筛选与分段

- [x] 4.1 新增 `useStationFilter`（`src/pages/console/components/useStationFilter.tsx`，实现时把设计里的 `useStationOptions` 与 URL 状态合并成一个 hook；纯函数在 `src/utils/stationFilter.ts`）：`listStations({silent:true})`，失败/空 → 不显示筛选；平台管理员加载 `listCustomers()` 给标签追加客户名（失败退化）；单测覆盖成功、404、空、旧版云端无 station_id（在总览与 `DevicePicker` 的组件测试里；401 转交由 `listStations({silent:true})` 的服务层测试覆盖）
- [x] 4.2 新增 `StationFilter` 组件（与 hook 同文件）：「全部油站」「未分组」+ 各油站，`?station=` 同步（`none`/数字 id，非法或不可见回退为全部），`history.replace`；单测
- [x] 4.3 设备总览 `src/pages/console/devices/index.tsx`：接入筛选；「全部油站」按油站分段（`null` → 末尾「未分组」，不可见油站用 `#id`），筛选后不分段；`station_id` 全部缺失时不渲染筛选与分段；不改取数、轮询与 `seq` 逻辑；测试覆盖分段、筛选、URL 直达、不新增遥测请求
- [x] 4.4 `DevicePicker`：接入筛选，候选按油站缩小；`?device=` 的可见性仍按完整列表判断，换油站不清已选设备；测试覆盖缩小候选、换油站保持已选、未选设备不请求
- [x] 4.5 文案 `pages.console.stationFilter.*` 加入中英文

## 5. 文档与收尾

- [x] 5.1 `CLAUDE.md`：更新目录结构（`src/pages/console/assets/*`、`src/pages/ops` 只剩 users/pairing-secret/audit）、`/ops` 与「资产管理」的分工、三条旧地址重定向、设备分配油站的唯一入口、登记设备现在位于 `/console/assets/devices`（原写作 `/ops/devices` 的地方都要改）
- [x] 5.2 `npx vitest run`、`npm run lint`、`npm run build` 全部通过
- [x] 5.3 在浏览器里用平台管理员与客户管理员各走一遍：菜单、客户/油站/设备三页、登记设备（含登记成功后分配油站）、转移客户、分配与清空油站、客户管理员看不到登记与转移、三条旧地址重定向、总览筛选与分段、报警页油站筛选（对着含 PR #53 的云端；若未做，在 PR 描述里如实写明）
- [x] 5.4 重写 PR #8 的描述以匹配新结构（页面路径、入口、已知限制保持不变）；归档本变更时手工把 `openspec/specs/ops-device-registration/spec.md` 的 Purpose 里的 `/ops/devices` 改为 `/console/assets/devices`

> 归档备注：5.3 由验收人在浏览器里走查；走查之后又做了「记录查询升一级」「子菜单四字名」「两处弹窗换目标修复」，这三项只有单元测试与构建验证。5.4 的 PR 描述已重写，Purpose 里的路径已在同步主规格时改好。
