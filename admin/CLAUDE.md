# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件，不要将该链接替换为普通文件。

## 项目状态：2026-10-08 登录、/ops 管理页、/console 设备总览/详情/报警/出入库记录、「资产管理」（客户·油站·设备）已对齐 yeweiyi-cloud，远程控制等未接

结构沿用 2026-09-05 从 `ant-design/ant-design-pro` 裁剪出的骨架（`/ops/*`、`/console/*` 空页面，
路由与 `access.ts` 已就位）。**登录链路已对齐真实后端**（OpenSpec 变更 `align-login-with-cloud`）：

- 只对接 `POST /api/login`（`{username,password}` → `{token}`，失败为 HTTP 状态码 + `{error}`）。
  后端**没有** `/api/currentUser`、登出、刷新 token 接口。
- 当前用户（`API.CurrentUser = {userId, username, access}`）由 `src/utils/auth.ts` 解码 JWT claims
  （`uid`/`sub`/`role`/`exp`）得到，**不验签、仅用于菜单显隐**；真正鉴权在后端每次读库。角色/停用变更
  最长 24h（token TTL）后菜单才刷新，越权请求仍会被后端 403。
- token 存 `localStorage`（`yeweiyi_admin_token`），`requestErrorConfig.ts` 为 `/api/*` 请求附加
  Bearer；非登录请求收到 401 会清 token 并回登录页。退出登录只清本地 token（后端无登出）。
- 响应没有 `{success,data}` 信封，别再加 `errorThrower` 之类按信封判成败的逻辑。

**菜单与入口分工（OpenSpec 变更 `consolidate-asset-management`）**：客户 → 油站 → 设备三级管理集中在顶层菜单「资产管理」
（`/console/assets/{customers,stations,devices}`，整组 `access.canManageAssets` = 平台管理员或客户管理员，客户项只对平台管理员），
与「设备监控」并列；URL 留在 `/console` 下，是为了客户管理员进不了 `/ops/*`。`/ops` 只剩账号管理、配对密钥、审计日志。
旧地址 `/ops/devices`、`/ops/customers`、`/console/stations` 在 `config/routes.ts` 里重定向到新位置，别删。
`/console/assets` 入口由 `pages/console/assets/index.tsx` 按角色分流（平台管理员→客户，客户管理员→油站）。
一级菜单顺序是 设备监控 → 记录查询（报警·入库·出库，URL 仍是 `/console/records/*`）→ 资产管理 → 运维管理；记录查询是单独的顶层路由，不嵌在设备监控下面。
设备页是设备**登记、客户归属、油站归属**的唯一入口：登记设备、分配/转移客户、「所属客户」列只对平台管理员显示（UI 隐藏，
权限边界在云端）；「分配油站」对两种管理员都有，是分配油站的唯一入口。登记成功的提示里带「分配油站」动作（点了才打开弹窗）。
客户管理员的设备行没有 `customer_id`，所以设备页对它不请求 `GET /api/customers`（云端只允许平台管理员，请求必然 403 并弹错）。

**设备登记（页面现位于 `/console/assets/devices`，原 `/ops/devices`；OpenSpec 变更 `register-device-and-assign-customer`，GitHub issue #1）**：

- 列表 `GET /api/devices`（行是 snake_case；`customer_id` **只有平台管理员的行才有**，null 为未分配，字段缺失是
  旧版云端，显示「未知」而不是「未分配」）；转移/分配走 `PUT /api/devices/:device/customer`（同一事务清掉该设备的
  所有绑定）；客户 `GET/POST /api/customers`；
  登记 `POST /api/devices`（`{deviceName,customerId}`，201 首次 / 200 已登记 / 409 属于其他客户）。
  这个接口是幂等登记，**不碰任何配对密钥**；不要改用 `pairing-secret`（它每次调用都会轮换密钥）。
- 出厂编号一律按字符串处理、保留前导零（`src/utils/deviceCode.ts`）。`ZCT` 前缀只用于给短码生成
  待确认的候选，各型号规则尚未确认，云端也不强制前缀或长度，不要把它写成协议。
- 列表另有「所属油站」列（`station_id` 缺失显示「未知」，null 为「未分组」），行内「分配油站」给 `customer_id` 不是 null 的设备
  （平台管理员行是数字，客户管理员行没有该字段；见下方油站段落）。
- 自己处理错误提示的请求设了 `skipErrorHandler`，这会**跳过全局的鉴权失败处理**（401、账号已失效的
  403），必须在失败时转交 `forwardAuthFailure`（见 `src/services/ant-design-pro/apiError.ts` 的
  `toApiError`）；返回的 `ApiError.authHandled` 为 true 时已在回登录页，调用方不要再提示。
- 只完成了管理员登记页面。**小程序扫码绑定仍未完成**：授权规则（管理员预分配，还是同客户申请加管理员
  批准）待产品/后端确认，出厂编号是公开可猜的标识，不能当凭证用。
- 本地联调需要含 cloud #49 的 `yeweiyi-cloud` `main`；该接口不在 `feat/tank-shape-attribute` 分支上。设备列表的
  `customer_id` 需要云端 `feat/device-list-customer-id` 分支（PR 合并前，管理后台的「所属客户」列会显示「未知」）。

**客户管理（页面现位于 `/console/assets/customers`，原 `/ops/customers`）、`/ops/users` 已接**（未走 OpenSpec）：客户管理是列表、新建、重命名、删除（真删除不可恢复，云端只允许删没有账号/设备/历史的客户，否则 409；名为
`default` 的内置客户不能改名也不能删，界面不给这两个操作）；账号管理是列表、新建、重置密码、
换客户（清设备绑定）、停用/启用（不能停用自己）。云端**没有改角色接口**（`PUT /api/users/:id` 只收 `disabled`
和 `password`），角色只能在创建时定，建错了只能停用后重建。密码限制是 8-72 **字节**，不是字符数。

**`/console` 只读页已接**（OpenSpec 变更 `console-readonly-records-and-detail`）：设备详情
（`/console/devices/:deviceName`，页签 实时数据/报警/出入库，`?tab=snapshot|alarms|stock`）、报警记录、入库/出库
记录。共用组件在 `src/pages/console/components/`（`AlarmRecords`、`StockRecords`、`TankCard`、`DevicePicker`、
`RecordsFrame`、`useRecordsQuery`），降级提示的判定在 `src/utils/records.ts`（纯函数，改提示规则先改它的测试）。
几个必须知道的云端行为，别凭记忆改：

- 报警/出入库接口**必须带 `device`**，没有跨设备列表；默认窗口 24 小时、**默认升序**，所以前端显式传
  `sort=false`；一次取 `limit=500`（云端上限）前端分页，没有总数。出入库**没有方向过滤**，入库/出库页在前端过滤。
- 查询窗口下界是设备归属当前客户的时间（`customer_assigned_at`），转移前的记录看不到，平台管理员也一样。
- 出入库的 **502（`STOCK_EVENT_FORMAT_UNSUPPORTED`）不是通用失败**，body 仍带 `rows` 和计数，`listStockEvents`
  把它当降级结果返回；`unparsable`/`legacySkipped`/`partial`/`truncated` 和报警的 `X-Records-Partial` 头都必须如实
  提示，**不能把「读不到/不完整」显示成「没有记录」**。
- 403 的 body 若是 `not authorized for this device` 是「无权看这台设备」，只有恰好 `not authorized` 才是账号失效
  （回登录页），别用前缀/包含去判断。
- 未做：历史趋势图（没装图表库）、远程控制与指令列表、WebSocket 报警推送、交班/库存记录。

**油站（`/console/assets/stations`，GitHub issue #6，云端 yeweiyi-cloud#53）**：油站是 客户 → 油站 → 设备 的中间一层，只存在云端业务库。
油站页只管油站增删改（对平台管理员和客户管理员开放，`access.canManageAssets`）；给设备分配油站在设备页。云端契约：
`GET /api/stations`（`{rows}`，平台管理员看全部，客户管理员看本客户全部含空油站）、`POST/PUT/DELETE /api/stations(/:id)`、
`PUT /api/devices/:device/station`（`{stationId: N | null}`，**键必须存在**，清空发显式 `null`）。必须知道的行为：

- 平台管理员新建油站必须带 `customerId`，客户管理员不带（界面从不发；带别的客户的 → `400 unknown customerId`，和带不存在的客户相同）。
- 删除仍挂有设备的油站 → 409，提示先把设备移走；它和创建时的 409（同客户重名）含义不同，错误归类分开写（`errors.ts`）。
- 客户管理员面对"别的客户的"和"不存在"的油站/设备得到完全相同的 `404`（`station not found` / `device not found`，
  yeweiyi-cloud#54、PR #55），界面按 404 提示"不存在，请刷新列表"。**更早的云端构建（PR #55 之前）对这两种情形答
  `403 not authorized`**，这个 body 恰好是上面"账号失效"的判据，会被 `toApiError` 当成账号失效而清 token、回登录页（例如和别的
  管理员竞争删除了油站）；联调时若看到这种莫名登出，先确认云端构建包含 PR #55。现在仍答 403 的只有 `customer_user` 调管理接口
  （角色问题，且该页面对他们不可见），它同样是 `not authorized`，按账号失效处理回登录页是合理的。因此
  `pages.console.stations.error.forbidden` 实际到不了，只是防御性兜底；别在界面上对 403 特判（会掩盖真正的账号失效）。
- 分配接口的 409：平台管理员是"设备没有客户"，客户管理员只会遇到"设备或油站刚被修改"的竞争，文案两种都覆盖。
- 联调真实云端需要包含 yeweiyi-cloud PR #53（油站）和 PR #55（对象级拒绝改为 404/400）的构建；没有油站接口的旧版云端 `/api/stations` 答 404：设备页和监控页筛选的油站请求是
  静默的（`listStations({ silent: true })`，失败退化为空列表，不弹错误），油站页的列表请求不静默，会显示全局错误提示。
- 设备行的 `station_id` 字段缺失 = 旧版云端，显示"未知"，不要当成"未分组"。设备没有客户（`customer_id: null`）时云端不允许挂油站，界面不给操作。
- 分配时候选油站按设备所属客户过滤（平台管理员看到多个客户的油站），这只是不让用户选到，云端的复合外键才是最后防线。
- 油站管理员角色（cloud#44）、邀请与员工管理、按用户授权不在本仓库范围。
- **监控页按油站筛选/分段**：设备总览与报警/入库/出库的 `DevicePicker` 有油站筛选（`?station=`：省略=全部，`none`=未分组，数字=油站 id，
  非法或不可见回退为全部）；逻辑在 `src/pages/console/components/useStationFilter.tsx` 与纯函数 `src/utils/stationFilter.ts`。
  `GET /api/stations` 对三种角色都能调（客户用户只看到自己设备所在的油站）。没有油站、请求失败（旧版云端 404）、或设备行都没有
  `station_id` 时不出现筛选和分段。总览选「全部油站」时按油站分段，筛选只改渲染，不重取遥测；`DevicePicker` 的筛选只缩小设备候选，
  `?device=` 的可见性仍按完整列表判断，换油站不清已选设备。平台管理员下油站标签追加客户名（跨客户可能重名）。

`src/services/ant-design-pro/` 按页面逐个补请求函数和类型，先读 `yeweiyi-cloud` 的
`internal/api/router.go`。

## 项目概要

液位仪云平台的 Web 前端，**一个仓库、两个分区，靠路由和角色区分**（不是两个仓库、两次部署）：

- **`/ops/*` 运维/管理控制台**：账号管理、跨客户设备全量视图、配对密钥铸造、审计日志。仅
  `platform_admin` 角色可见（`src/access.ts` 的 `canPlatformAdmin`）。对应
  `yeweiyi-cloud` 的 `openspec/changes/add-admin-console` 提案与 `internal/api/admin.go`。
- **`/console/*` 客户 PC 监控台**：小程序（`yeweiyi-miniapp`）的桌面版——同一批客户账号
  （`customer_admin`/`customer_user`）在 PC 浏览器上看设备列表、遥测详情、下发控制指令、
  查进销存记录。`platform_admin` 也能进（权限包含关系，见 `canConsole`）。

两个分区共用同一套登录（`/user/login`）、同一份 JWT、同一个 `yeweiyi-cloud` 后端，唯一区别是
`config/routes.ts` 里的 `access` 字段和菜单分组——不要为了"隔离"而拆成两个应用，这不是这次的
设计目标。

**这不是需求A（PC 端直连设备局域网）。** `zhongchengyeweiyi` 的
`docs/小程序与PC端集成设计.md` 里区分了两种"PC 端"：需求A是 PC 直连设备所在局域网的 AndServer
（8080 端口、零鉴权、不经过云端，已经能用，不需要这个项目），需求B是云端中转的远程监控
（这个项目的 `/console/*` 和 `yeweiyi-miniapp` 都是需求B的客户端）。写代码前先确认自己在解决
哪一个，别把两者的鉴权模型和数据源搞混。

## 四个仓库怎么关联

```text
zhongchengyeweiyi (Android 设备)
        │  ThingsBoard 原生 MQTT
        ▼
yeweiyi-cloud (Go 后端，唯一的业务 REST/WS 网关)
        │  REST/WebSocket，JWT 鉴权
        ├──▶ yeweiyi-miniapp（微信小程序，customer_admin/customer_user）
        └──▶ yeweiyi-admin（本仓库，Ant Design Pro，
             platform_admin 走 /ops/*，客户角色走 /console/*）
```

- `~/Developer/zhongchengyeweiyi` — Android 设备端。本仓库不直接对接它；它的行为通过
  `yeweiyi-cloud` 中转。只有在某个 `/console/*` 页面的数据看起来"设备侧才能解释"时才需要读它
  （例如控制指令为什么被拒绝、出入库记录为什么缺失）。
- `~/Developer/yeweiyi-cloud` — **唯一的后端，本仓库要对接的一切 API 都在这里**。它的
  `CLAUDE.md` 是 REST/WS 接口的权威来源，路由列表见该仓库 `internal/api/router.go`；写任何
  请求函数前先读一遍，不要凭记忆假设接口形状。它的 `openspec/changes/add-admin-console/
  specs/*/spec.md` 记录了 `/ops/*` 对应的账号管理与设备全量视图两块能力的行为约定
  （谁能调、失败时返回什么、审计覆盖哪些结果）——这是 `/ops/*` 页面唯一的规格来源。
- `~/Developer/yeweiyi-miniapp` — 同一批客户角色（`customer_admin`/`customer_user`）的
  微信小程序客户端。`/console/*` 是它的 PC 端等价物，理应覆盖相同的业务能力集合（设备列表、
  设备详情/遥测、下发指令、进销存记录）——加新页面或改交互前，看一眼它的 `pages/` 目录，
  两端的信息架构不需要照抄 UI，但覆盖的能力集合应该一致，不要一端有一端没有。
- 本仓库（`yeweiyi-admin`）— 两个分区都在这里。

跨仓库协议/接口变更（新增字段、改路径、改鉴权模型）必须先在 `yeweiyi-cloud` 侧落地并更新其
`CLAUDE.md`/`docs/`，本仓库跟着改，不要反过来假设一个还不存在的接口形状。

## 快速启动

```bash
npm install
npm run dev   # UMI_ENV=dev，代理 /api/ 到 http://localhost:8080（见 config/proxy.ts）
```

先按 `yeweiyi-cloud` 的 CLAUDE.md 把后端跑起来（`docker compose up -d` + 迁移 + `go run
./cmd/server`），再跑本仓库的 `npm run dev`，否则所有 `/api/*` 请求都会 404/连接失败。

```bash
npm run tsc     # 类型检查
npm run lint    # biome + tsc
npm run test    # vitest
npm run build   # 生产构建
```

## 目录

```text
config/routes.ts        路由表，access 字段是 /ops 与 /console 的唯一分界
src/access.ts           canPlatformAdmin / canManageAssets / canConsole 角色判断
src/pages/ops/          运维控制台：users / pairing-secret / audit（设备与客户已并入 console/assets）
src/pages/console/      客户 PC 监控台：devices / device-detail / alarms / oil-in-out（入库、出库共用）/ issue-command
src/pages/console/assets/  资产管理：customers（仅平台管理员）/ stations / devices（登记·客户归属·油站归属）
src/pages/user/login/   登录（两个分区共用）
src/pages/account/      个人设置（占位）
src/pages/exception/    403 / 404 / 500
src/services/           对接后端的请求函数（登录、设备、客户等，按页面逐个补）；src/utils/auth.ts 管 token 与 JWT 解码
```

## 仍未定案或未做的事

- 除登录和设备登记（设备、客户）外，其余业务接口（账号、控制、进销存、审计等）的类型与请求函数
  还没写。接哪个页面就先把对应类型和请求函数按真实接口补上，不要在没对齐类型的情况下假装接口已经通了。
- 设备登记、客户、账号、设备转移的接口已用 curl 对真实后端验证过（2026-10-07），但页面本身没有在浏览器里走过
  （Chrome 扩展未连上）。
- 登录页没有联调过真实后端（见 `align-login-with-cloud` 任务 5.4）；需要 `platform_admin` 与客户
  角色账号各验证一次。
- 生产环境的反向代理目标（`config/proxy.ts` 的 `test`/`pre`）还是占位符，部署方式和域名都没有
  定案，跟 `yeweiyi-miniapp`/`yeweiyi-cloud` 的部署决策一样悬而未决。
- `/console/*` 的趋势图、远程控制（`/console/issue-command`）、报警实时推送还没做；图表和告警的展示形式需要产品侧
  决定，不要自己臆造。
- 本仓库已接入 OpenSpec（`openspec/config.yaml`，变更放在 `openspec/changes/`），与 `yeweiyi-cloud` 各用各的配置，
  不共享。
