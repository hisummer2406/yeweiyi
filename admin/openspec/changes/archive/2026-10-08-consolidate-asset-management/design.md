## Context

现状（读自本分支 `stations`，即 PR #8）：

- 路由：`/ops`（仅 `canPlatformAdmin`）有 设备管理 / 客户管理 / 账号管理 / 配对密钥 / 审计日志；`/console`（`canConsole`，菜单名「设备监控」）有 设备总览 / 记录查询 /
  远程控制 / 油站管理。「油站管理」`/console/stations` 单独要求 `canManageStations`（平台管理员或客户管理员），页面是两个页签：油站增删改、设备分配。
- `/ops/devices` 的行内操作有「转移客户」和「分配油站」，后者复用 `console/stations/AssignStationModal`；设备分配页签里也有同一个操作。
- 云端事实（`yeweiyi-cloud` `1d9d0be`，PR #53）：
  - `GET /api/customers`：**仅平台管理员**（否则 403）。设备行的 `customer_id` 也只有平台管理员的行才有，客户管理员的行里该字段缺失。
  - `GET /api/stations`：三种角色都可调用，可见范围由 `ListStationsForUser` 决定——平台管理员全部；客户管理员本客户全部（含空油站）；客户用户只有自己已绑定设备所在的油站。
  - 设备行（`GET /api/devices`）带 `station_id`（可空）；字段缺失 = 旧版云端。
  - `PUT /api/devices/:device/station`（`{stationId: N|null}`，键必须存在）；设备没有客户时不允许挂油站。
- 客户管理员进不了 `/ops/*`，所以任何客户管理员要做的管理动作都必须在 `/console` 路由下。

## Goals / Non-Goals

**Goals:**
- 客户 → 油站 → 设备三级管理集中在一个菜单组，按层级排列；每个动作只有一个入口，设备的登记、客户归属、油站归属在同一页完成。
- 监控页能按油站查看设备，且云端不支持油站时页面与现在完全一致。
- 现有行为（表单校验、错误归类、409/403 处理、鉴权转交）一律不变，只重排入口。

**Non-Goals:**
- 云端改动、新接口；油站管理员角色、邀请/员工管理、按用户授权。
- 给客户管理员开放设备登记或转移客户（云端只允许平台管理员；本变更只在 UI 上按角色隐藏，不改云端权限）。
- 重写登记/转移的业务规则（出厂编号规范化、幂等登记、冲突处理、客户内联创建都原样搬过去，只换位置）。
- 新的数据展示形式（图表、地图等）。

## Decisions

**D1 顶层「资产管理」菜单组，URL 仍在 `/console` 之下。** 在 `routes.ts` 里 `/console`（设备监控）之后再加一个顶层条目 `path: '/console/assets'`，
子路由 `customers / stations / devices`，整组 `access: 'canManageAssets'`，客户项再单独 `access: 'canPlatformAdmin'`。
这样菜单上与「设备监控」并列，URL 又落在 `/console/*` 下，客户管理员不会碰到 `/ops` 的拦截。
备选：做成 `/console` 的子菜单——否决，监控与管理是两种角色权限，混在同一个菜单里要再拆权限；备选：新前缀 `/assets`——否决，
`CLAUDE.md` 约定两个分区靠 `/ops` 与 `/console` 区分，不再引入第三个前缀。
`/console/assets` 本身重定向到第一个可见项：平台管理员 → 客户，客户管理员 → 油站（客户页对其不可见）。

**D1a 记录查询升为一级菜单。** 与 D1 同一做法：在 `routes.ts` 里为 `/console/records` 另起顶层条目（`access: canConsole`），从「设备监控」组里移出，顺序为 设备监控 → 记录查询 → 资产管理；URL、页面、`DevicePicker` 行为都不变，只有菜单层级和菜单文案键变化。备选：把 3 个记录页直接平铺进「设备监控」——否决，同组会有 5 项且记录页要先选设备，和总览性质不同。

**D1b 菜单名统一四个字。** 与既有的「设备监控」「记录查询」「运维管理」对齐，「资产管理」下三项为 客户管理 / 油站管理 / 设备管理（`menu.assets.*`，英文不变）。页面标题取自菜单名，会随之变化。

**D2 `canManageStations` 改名 `canManageAssets`，判定不变**（`platform_admin` 或 `customer_admin`）。名字跟着含义走，引用点只有 `routes.ts` 与测试。

**D3 设备页合并登记、客户归属与油站归属。** `/console/assets/devices` 是设备的唯一管理页，列：设备名、所属客户（仅平台管理员）、所属油站、在线状态、最近上线/下线、更新时间（取自现 `/ops/devices`，原「设备分配」页签只有设备名与油站，是它的子集）。
行内操作按角色：
- 平台管理员：「分配客户 / 转移客户」（`customer_id` 为 `null` 时文案是分配，否则是转移，沿用现有逻辑）与「分配油站」；
- 客户管理员：只有「分配油站」。
工具栏的「登记设备」只对平台管理员渲染（`access.canPlatformAdmin`）。云端权限不变：客户管理员即使绕过 UI 调用登记/转移也会被云端 403；前端隐藏只是不展示做不了的动作，不是权限边界。
`customer_id === null` 的设备不提供「分配油站」（云端不允许，先分配客户）。`customer_id` 字段缺失（客户管理员的行）时「分配油站」仍然提供，由云端裁决。
备选：保留 `/ops/devices` 作为登记页、资产管理里另做设备页——否决，两个设备列表要同时维护且分配油站入口难以做到唯一，用户已决定合并。

**D3a 登记成功后在同页分配油站（取代跨页跳转）。** 登记请求成功（201 首次或 200 已登记）后，列表刷新，并在成功提示里给出「分配油站」动作，点击打开 `AssignStationModal`（目标是 `{device_name: deviceName, customer_id: customerId}`，
候选油站取该客户的油站）。用户不点则什么也不发生，登记结果与提示文案不变。该客户还没有任何油站时，弹窗显示现有的空候选状态（沿用现状，本变更不新增逻辑），并提示可先到「油站」页新建。
这个动作只在登记成功时出现，409/400/403 等失败路径不出现；`AssignStationModal` 自身的 409/403 错误归类不变。
备选：登记成功后自动弹出分配油站——否决，一次登记多台设备时每台都弹窗会打断；备选：登记表单里直接加油站下拉——否决，油站候选依赖所选客户，会让登记表单与 `POST /api/devices` 的契约（只有 `deviceName`、`customerId`）耦合，且客户内联创建后油站列表要联动。

**D4 页面文件按菜单归位。**
- `src/pages/ops/customers/*` → `src/pages/console/assets/customers/*`（`git mv`，内容不变）。
- `src/pages/console/stations/{StationsTab,StationFormModal,errors}` → `src/pages/console/assets/stations/`，页面入口去掉 `Tabs`，直接渲染原 `StationsTab`（页签只剩一个就不需要页签；
  当初 `destroyOnHidden` 是为了在两个页签间切换时重新加载，页签没了这个理由随之消失）。
- `src/pages/ops/devices/*`（`index.tsx`、`RegisterDeviceModal`、`TransferDeviceModal`、`errors` 及测试）`git mv` 到 `src/pages/console/assets/devices/`，再把原「设备分配」页签的 `DevicesTab` 合并进来（不再单独存在）、`AssignStationModal` 一并移入。
- 旧路径（`/console/stations`、`/ops/customers`、`/ops/devices`）在 `routes.ts` 里用 `redirect` 保留，`hideInMenu` 无需设置（redirect 项本身不进菜单）。

**D5 `/ops` 只剩账号管理、配对密钥、审计日志。** `ops.customers`、`ops.devices` 菜单项都从 `/ops` 组移除；`/ops` 的默认重定向从 `/ops/devices` 改为 `/ops/users`。
`/ops/devices`、`/ops/customers` 作为重定向条目保留（不进菜单）。账号管理不动：它本来就是平台管理员专属页面，与资产层级无关。配对密钥、审计日志目前是空占位页，不动。
`/ops` 组整体仍 `access: canPlatformAdmin`。

**D6 监控侧油站筛选用同一个组件与 hook。**
- `useStationOptions()`：调用 `listStations({silent:true})`，返回 `{stations, ready}`；任何失败（含旧版云端 404）→ `stations=[]`、不弹错，筛选控件不渲染。成功但为空 → 同样不渲染（没有油站就没有筛选的意义）。
- `StationFilter`：antd `Select`，选项「全部油站」「未分组」+ 各油站；值写入 `?station=`（`all` 省略、`none` 表示未分组、数字为油站 id），用 `history.replace` 同步，与 `DevicePicker` 的 `?device=` 同一写法。
- 平台管理员的油站跨客户，重名无法区分，所以平台管理员下选项标签追加客户名（用 `listCustomers()`，失败退化为只显示油站名）；其他角色只显示油站名。
- 设备行 `station_id` 缺失（旧版云端）→ 不渲染筛选，也不分组；与「未分组」（`null`）严格区分。

**D7 设备总览：筛选 + 分段。** 选「全部油站」时按油站分段（每段标题=油站名，末尾「未分组」段）；选定一个油站或「未分组」时只显示该段设备，不再分段。分段与筛选只改变渲染，
遥测取数、30 秒轮询、`seq` 丢弃旧响应的逻辑保持不动（筛选后只对可见设备显示，已取的数据不重取，不触发额外请求）。

**D8 `DevicePicker`（报警/入库/出库）：筛选只缩小候选，不改变已选。** 选了油站后设备下拉只列该油站的设备；URL 里已有的 `?device=` 仍按**完整**设备列表判断是否可见，
不会因为换了油站筛选就把已选设备清掉或报「不可见」。换油站后若已选设备不在候选里，下拉仍显示已选设备（与候选集合无关），不自动切换。云端记录接口仍必须带 `device`，没有「按油站查询」。

**D9 文案与测试。** 菜单文案 `menu.assets`、`menu.assets.customers|stations|devices`；`pages.console.stations.*` 与 `pages.ops.customers.*` 的键名不改（只搬页面，不让翻译文件产生无谓 diff），
新增筛选相关键 `pages.console.stationFilter.*`；中英文键保持完全一致。测试随页面移动；新增：路由/权限与三条旧地址重定向、设备页两种角色下的列与操作差异、登记成功后的「分配油站」动作、总览筛选与分段、`DevicePicker` 筛选、`useStationOptions` 的失败退化。

## Risks / Trade-offs

- [`/ops/customers` 搬到 `/console/assets/customers`，平台管理员需要习惯新位置] → 旧地址重定向；菜单顺序（客户在最前）体现层级。
- [客户管理员在设备页看不到「所属客户」列，也不能「转移客户」] → 设计如此：该字段云端只对平台管理员返回，转移客户与登记是平台独有操作，设备页只对平台管理员显示这两个入口。
- [客户用户的 `GET /api/stations` 只返回自己设备所在的油站] → 筛选选项就是这个集合，不会出现「点进去没有设备」的油站；若云端日后改变可见规则，筛选自动跟着变。
- [设备登记、转移客户在 UI 上只对平台管理员显示，但与客户管理员共用同一个页面] → 隐藏靠 `access.canPlatformAdmin` 判定；云端权限不变，是真正的边界；测试要覆盖两种角色下的按钮差异。
- [登记表单搬到另一个路由后，原有的 `/ops/devices` 相关测试与文案键引用要跟着改] → `git mv` 保留历史，文案键名（`pages.ops.devices.*`）不改，只搬页面不让翻译文件产生无谓 diff。
- [分段显示在设备很多时页面变长] → 分段只在「全部油站」时出现，用户可用筛选收窄；本变更不引入折叠/虚拟滚动，设备量级目前是个位数到几十台。
- [PR #8 已写好的页面要搬家，评审者需要重读 diff] → 本变更建议在 `stations` 分支上继续（PR #8 尚未合并），PR 描述同步重写；搬家用 `git mv` 保留历史。
- [客户管理员「被无提示踢回登录页」的已知限制（云端 403 响应体与账号失效相同）] → 不受本变更影响，也不在本变更解决，仍等云端对象级拒绝改成不同响应。

## Migration Plan

纯前端重排，无数据迁移。在 `stations` 分支上实现并更新 PR #8；旧路径靠重定向兼容；回滚即回退该分支提交。云端无需配合发布。

## Open Questions

已决定（用户）：登记设备并入「资产管理」，登记成功后在同页提供「分配油站」；总览的油站分段先不折叠。

- 登记成功后的「分配油站」动作在客户还没有油站时的体验：沿用现有空候选状态并提示去「油站」页新建；是否要在弹窗里直接内联新建油站，留给实际使用后再定。
- `ops-device-registration` 这个 capability 名里的 `ops` 与新位置不符；改名需要 spec 改名流程，本变更不改，归档时只更新其 Purpose 里的路径。
- 设备量变大后总览分段要不要折叠或虚拟滚动，取决于真实设备量。
