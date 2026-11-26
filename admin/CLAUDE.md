# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件，不要将该链接替换为普通文件。

## 项目状态：2026-10-08 登录、/ops 管理页、/console 设备总览/详情/报警/出入库记录、「资产管理」（客户·油站·设备）已对齐 yeweiyi-cloud；2026-11-08 远程配置（参数设置/操作记录）已接，未联调

结构沿用 2026-09-05 从 `ant-design/ant-design-pro` 裁剪出的骨架（`/ops/*`、`/console/*` 空页面，
路由与 `access.ts` 已就位）。**登录链路已对齐真实后端**（OpenSpec 变更 `align-login-with-cloud`）：

- 只对接 `POST /api/login`（`{username,password}` 或 `{phone,password}`，**恰好给一个** → `{token, user}`，失败为 HTTP 状态码 + `{error, code?}`；
  见下方「手机号账号」）。
  后端**没有** `/api/currentUser`、登出、刷新 token 接口。
- 当前用户（`API.CurrentUser = {userId, username, access}`）由 `src/utils/auth.ts` 解码 JWT claims
  （`uid`/`sub`/`role`/`exp`）得到，**不验签、仅用于菜单显隐**；真正鉴权在后端每次读库。角色/停用变更
  最长 24h（token TTL）后菜单才刷新，越权请求仍会被后端 403。
- token 存 `localStorage`（`yeweiyi_admin_token`），`requestErrorConfig.ts` 为 `/api/*` 请求附加
  Bearer；非登录请求收到 401 会清 token 并回登录页。退出登录只清本地 token（后端无登出）。
- 响应没有 `{success,data}` 信封，别再加 `errorThrower` 之类按信封判成败的逻辑。

**菜单与入口分工（OpenSpec 变更 `consolidate-asset-management`）**：客户 → 油站 → 设备三级管理集中在顶层菜单「资产管理」
（`/assets/{customers,stations,devices}`，整组 `access.canManageAssets` = 平台管理员或客户管理员，客户项只对平台管理员），
与「设备监控」并列；URL 不在 `/ops` 下，所以客户管理员不会被 `/ops/*` 拦截；也**不能放在 `/console/` 下**（见下方「菜单高亮」）。`/ops` 只剩账号管理、审计日志。
旧地址 `/ops/devices`、`/ops/customers`、`/console/stations`、`/console/records/*`、`/console/assets/*` 在 `config/routes.ts` 里重定向到新位置，别删。
`/assets` 入口由 `pages/assets/index.tsx` 按角色分流（平台管理员→客户，客户管理员→油站）。
**菜单高亮（别踩）**：ProLayout 用 `@umijs/route-utils` 的 `getMatchMenu` 算选中项，路径 `P` 会匹配 `P/(.*)`，而分组 path 又是菜单 key。
「设备监控」分组的 path 是 `/console`，所以记录查询、资产管理曾放在 `/console/records/*`、`/console/assets/*` 时，点它们会把「设备监控」一并点亮；
现在它们是 `/records/*`、`/assets/*`。**新增菜单组时，别把 URL 放在另一个分组的 path 前缀下**，分组里也别放 path 等于分组 path 的无名重定向。
`src/routes.test.ts` 的「菜单高亮」用同一个 `getMatchMenu` 守着这一点。分组 path 也不能随便改成别的值：react-router 要求嵌套的绝对子路径以父路径开头。
一级菜单顺序是 资产管理 → 设备监控 → 记录查询（报警·入库·出库·交班·库存，URL 是 `/records/*`）→ 运维管理（2026-10-15 资产管理与运维管理互换了位置）；记录查询是单独的顶层路由，不嵌在设备监控下面。
设备页是设备**登记、客户归属、油站归属**的唯一入口：登记设备、分配/转移客户、「所属客户」列只对平台管理员显示（UI 隐藏，
权限边界在云端）；「分配油站」对两种管理员都有，是分配油站的唯一入口。登记成功的提示里带「分配油站」动作（点了才打开弹窗）。
客户管理员的设备行没有 `customer_id`，所以设备页对它不请求 `GET /api/customers`（云端只允许平台管理员，请求必然 403 并弹错）。

**设备登记（页面现位于 `/assets/devices`，原 `/ops/devices`；OpenSpec 变更 `register-device-and-assign-customer`，GitHub issue #1）**：

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
- **扫码绑定的授权依据是「管理员先登记并分配客户」**（yeweiyi-cloud #56，OpenSpec `bind-device-by-short-code`）：
  设备二维码只有短码（如 `000001`），出厂编号是 `ZCT000001`；`POST /api/devices/bind` 的 `pairingToken` 可选，不带时云端
  只在调用者所属客户的设备里解析短码，`customer_admin`/`customer_user` 可用，`platform_admin` 不可，失败一律 401。
  所以**设备必须先在设备页登记并分配客户，客户账号才能扫码绑定**；没分配的设备、别的客户的设备都绑不上。
  绑定本身是小程序的事，本仓库不做；配对密钥不是绑定的必经步骤。云端铸造接口（`POST /api/devices/:device/pairing-secret`）还在，
  只用于带密钥绑定（含平台管理员跨客户的场景），每次铸造都会轮换；管理后台已于 2026-10-08 删除原先的空「配对密钥」页和菜单，暂无入口，
  需要时再按真实需求重做。
  出厂编号是公开可猜的标识，不能当凭证；`ZCT` 前缀由云端配置 `pairing.codePrefixes`，不是协议。
- 本地联调需要含 cloud #49 的 `yeweiyi-cloud` `main`；该接口不在 `feat/tank-shape-attribute` 分支上。设备列表的
  `customer_id` 需要云端 `feat/device-list-customer-id` 分支（PR 合并前，管理后台的「所属客户」列会显示「未知」）。

**客户管理（页面现位于 `/assets/customers`，原 `/ops/customers`）、`/ops/users` 已接**（未走 OpenSpec）：客户管理是列表、新建、重命名、删除（真删除不可恢复，云端只允许删没有账号/设备/历史的客户，否则 409；**一客户一管理员**（产品决定，界面约束，云端不强制）：新建客户弹窗（`CreateCustomerModal`）同时收 客户名 + 管理员手机号 + 初始密码，先 `POST /api/customers` 再 `POST /api/users`（云端没有一次建两个的接口）；管理员建失败就 `DELETE` 刚建的空客户回滚，弹窗保留表单，回滚也失败才留下孤儿并提示。账号管理里已有管理员的客户不能再建第二个管理员、也不能把管理员换入（`adminCustomerIds`）；员工不受限。旧数据里可能已有多个管理员或没有管理员，界面不改动它们。；名称界面上限 20 个字符（`src/utils/customerName.ts`，按字符计，新建/重命名/设备登记里的内联新建都用它；云端上限是 100，别以为 20 是协议）；名为
`default` 的内置客户不能改名也不能删，界面不给这两个操作）；账号管理是列表、新建、重置密码（仅有密码的账号）、
换客户（清设备绑定）、停用/启用（不能停用自己）。云端**没有改角色接口**（`PUT /api/users/:id` 只收 `disabled`
和 `password`），角色只能在创建时定，建错了只能停用后重建。密码限制是 8-72 **字节**，不是字符数。

**品牌设置（#86，云端契约 `cloud/docs/brand-logo-contract.md`）**：客户行「品牌设置」（仅平台管理员，`BrandLogoModal`）上传/替换/恢复小程序 Logo，
`GET/PUT/DELETE /api/customers/:id/brand-logo`，图片以 base64 收发。预览按小程序首页蓝底标题栏、设置页白底账号行两种样式；默认图
`public/images/brand-default-{mark,avatar}.png` 是小程序同名默认图的拷贝，换默认图两边一起换。本地预检（`utils/brandLogo.ts`：PNG 文件头、
≤256 KiB、宽高 64–1024）只为早提示，云端整图解码才是裁决。审计由云端写。只有 mock/组件测试，未对真实云端联调。

**手机号账号（GitHub issue #13，云端 yeweiyi-cloud#59，契约 `docs/account-phone-wechat-contract.md`）**：

- 客户侧账号（`customer_admin`/`customer_user`）以**手机号**标识，没有用户名；平台管理员仍是用户名。登录框一个输入，
  `src/utils/loginIdentifier.ts` 按内容决定发 `phone` 还是 `username`（云端不替你猜，两个都发或都不发是 400）。
- **令牌 `sub` 现在是用户 ID，不是用户名**。显示标签来自登录响应的 `user`（`setProfile`，按 `userId` 核对，换账号不串），
  旧令牌里的用户名只是兜底；取不到时界面显示空串/`#id`，不要再从 `sub` 取名字。
- **员工（`customer_user`）没有网页后台**：`access.canConsole` 只给两种管理员，`getCurrentUser` 遇到员工令牌（小程序登录得来的、
  或旧本地状态）会清掉并回登录页，登录页拿到员工 `user` 也不保存令牌。这只是界面边界，云端对管理接口的角色校验才是安全边界，
  员工的业务 REST 仍对小程序开放，别因此在云端或这里封禁。
- **账号只有平台管理员能创建/停用/启用/重置密码**（云端对客户管理员一律 403）。创建表单按角色出字段：平台管理员=用户名+密码；
  客户管理员=手机号+密码+客户；员工=手机号+客户，**没有密码框，请求体也不带 `password`**（带了 400 `password_not_allowed`），
  员工行没有"重置密码"（云端 409 `no_password_for_role`）。`buildCreateUserParams` 按角色挑字段，别让残留表单值漏进请求。
- 云端新接口带机器码 `code`（`phone_exists` 等），`ApiError.code` 承载；`describeUserError` 先按 code 再按状态码。
  重复手机号的提示不说明在哪个客户。客户管理员列员工的手机号是脱敏的；油罐分配（#17/cloud#63）自云端 2026-10-14 起仅平台管理员，见下文「员工按罐授权」。
- **账号列表筛选**（`/ops/users`）：一次 `GET /api/users` 取全，前端按 手机号/用户名（子串）、客户、角色、状态过滤，条件进 URL（`?q=&customer=&role=&status=`，纯函数 `src/utils/userFilter.ts`）。按客户筛选后「新建账号」自动带入并锁定该客户；**账号入口分工（2026-10-15）**：只有「平台管理员账号」收进右上角账号菜单（仅平台管理员显示，`/ops/platform-admins`，`UsersPanel platformOnly`，不在侧边栏，只列/只建平台管理员）；「运维管理 → 账号管理」（`/ops/users`）仍管**客户侧账号**（客户管理员、员工）的跨客户视图，不含平台管理员。「新建账号」不再有「客户管理员」选项（客户管理员随「新建客户」创建，旧数据缺管理员的客户走开通向导），在账号管理里新建的是员工（手机号+客户，无密码）。**员工建成功后自动跳到 `/assets/staff?user=<id>`，员工授权页加载完自动打开该员工的「分配油罐」弹窗（只一次，随后清掉参数）**——没授权的员工什么都看不到，所以建完就该授权。日常入口是「资产管理 → 客户 → 账号」（`/assets/customers/:id/accounts`，仅平台管理员，不进菜单）：同一个 `UsersPanel` 锁定到该客户，只列该客户的管理员/员工，新建自动带入客户；账号管理（`/ops/users`）保留为跨客户的全局视图和平台管理员的管理入口。云端没有服务端查询，账号上万再让云端加。列表平铺：客户管理员和员工在同一张表里（员工不藏在管理员行下面），按客户分组、同一客户里管理员在前。
- **编辑账号**（账号管理行内「编辑账号」一个框，取代原「重置密码」「修改手机号」两个入口；手机号字段仅手机号账号出现，密码留空不改，只发实际改动的字段，一次 `PUT`）：`PUT /api/users/:id {phone}`。契约见云端 `docs/account-phone-wechat-contract.md` §4.7（`phone_exists` 409 / `invalid_phone` 400 / `phone_not_applicable` 409 含用户名账号，传当前号码是 200 无操作）；改号会同时清掉该用户的微信绑定（需重新微信授权）、已签发令牌不失效。云端分支 `feat/change-user-phone` 合并前只有 mock 测试。
- 微信登录与客户开通（#60）、油罐授权（#63）不在本仓库这一批；网页只走手机号/用户名 + 密码，离线可用。

**`/console` 只读页已接**（OpenSpec 变更 `console-readonly-records-and-detail`）：设备详情
（`/console/devices/:deviceName`，页签 实时数据/报警/出入库，`?tab=snapshot|alarms|stock`）、报警记录、入库/出库
记录。共用组件在 `src/pages/console/components/`（`AlarmRecords`、`StockRecords`、`TankCard`、`DevicePicker`、
`RecordsFrame`、`useRecordsQuery`），降级提示的判定在 `src/utils/records.ts`（纯函数，改提示规则先改它的测试）。
几个必须知道的云端行为，别凭记忆改：

- 报警/出入库接口**必须带 `device`**，没有跨设备列表；默认窗口 24 小时、**默认升序**，所以前端显式传
  `sort=false`。**分页在云端做**（2026-11-04 起，四类记录都是）：每页 20 条（`PAGE_SIZE`），`limit`/`offset` 每页一次请求，
  云端不给总数，只有上一页/下一页；出入库、交班、库存的 `truncated` 表示「这一页之后还有」（云端多取一条判断），报警接口没有该字段，
  `listAlarms` 多要一条、只显示前 20 条，**回了 >= 20 条就算还有**（云端先 LIMIT 再跳过解析不了的行，要 21 回 20 时后面可能还有）。
  `offset` 一律 `(页码-1)×20`，**不按实际显示行数算**（各接口都会在分页后跳过坏行，一页可能不足 20 条）。换设备/时间窗/筛选回到第 1 页，轮询刷新当前页，翻页时保留旧行直到新页到达。
  罐号、方向由云端先筛选后分页（#84），前端按行再过滤一遍只为兼容旧云端。
- 查询窗口下界是设备归属当前客户的时间（`customer_assigned_at`），转移前的记录看不到，平台管理员也一样。
- 出入库的 **502（`STOCK_EVENT_FORMAT_UNSUPPORTED`）不是通用失败**，body 仍带 `rows` 和计数，`listStockEvents`
  把它当降级结果返回；`unparsable`/`legacySkipped`/`partial`/`truncated` 和报警的 `X-Records-Partial` 头都必须如实
  提示，**不能把「读不到/不完整」显示成「没有记录」**。
- 403 的 body 若是 `not authorized for this device` 是「无权看这台设备」，只有恰好 `not authorized` 才是账号失效
  （回登录页），别用前缀/包含去判断。
- **设备总览分页与请求控制**（`pages/console/devices`，纯函数 `src/utils/overviewPaging.ts`）：设备列表一次取全，前端每页 12 台，
  **只对当前页取遥测**（翻到才取，已取过的沿用缓存），遥测请求并发上限 5；按油站分段时分页单位是油站（不拆开，超过一页的分段切开并重复标题）；
  标签页不可见时不轮询，回到前台立即刷新。云端没有批量遥测接口，有了再把「逐台请求」换成一次请求。
  **读数 5 秒一刷（#62，`LIVE_TELEMETRY_MS`）**：总览只刷当前页遥测、不重拉列表（列表仍 30 秒），并发闸门 `createLimiter` 跨轮共享；
  详情「实时数据」同为 5 秒。同一台设备上次请求未回就跳过本轮（不堆积，也不让慢请求被后来者作废），失败静默保留上次读数和上报时间。
- **交班记录、库存记录（cloud#85，契约 `docs/shift-stock-records-contract.md` §4/§5；小程序 miniapp PR #24 同规则）**：
  `/records/shift`、`/records/inventory`，设备详情也有同名页签（`?tab=shift|inventory`）。接口 `GET /api/shift-records`、
  `GET /api/stock-records`，参数同出入库（device 必填、tank 可选，显式 `sort=false`，`limit`/`offset` 云端分页），响应 `{rows, truncated, partial,
  unparsable, syncStatus}`，每个 (记录, 罐) 一行。提示规则在 `summarizeSynced`（`utils/records.ts`）：`UNSUPPORTED`「设备暂不支持该类记录」，
  `SYNCING`/`SYNCED+partial`「同步中」，不认识或缺失的状态「无法确认」，只有 `SYNCED` 且没有行才说没有记录；`unparsable` 是整机计数，文案写「本设备」。
  缺失值一律「—」（`num`/`DASH`），0 照常显示。交班列顺序：罐号、介质、交班时间（`endTime`）、接班时间（`beginTime`）、前高、前量、后高、后量、
  出库量(L/kg)；进行中的班（`OPEN`）交班时间显示「—」并带「进行中」标签。库存：罐号、介质、库存生成时间、液位(mm)、容积、水高(mm)、温度、质量。
  同一班/同一次库存的行同底色、相邻交替（`groupStripes`）。**介质**（含报警、出入库）按 `oilTypeIndex` 1–17 显示本地名称（`utils/oilType.ts`，
  文案 `pages.console.oil.N`，以中文表为准，设备英文表有错位 zhongchengyeweiyi#52），其余显示 `oilType` 原文，再没有就「—」。
  出入库多一列「实际进出量(kg)」= `deliveredWeightKg`，null 显示「—」，**不能用前后重量差代替**；报警多一列「介质」，取报警自带的值，不拿罐当前油品代替。
- **云端补录（cloud#69，契约 `cloud/docs/stock-manual-entry-contract.md`）**：出入库记录（入库/出库页、设备详情出入库页签）工具栏有「补录」，
  `ManualStockEntryModal` 发 `POST /api/devices/:device/stock-events`（方向在入库/出库页固定；罐号来自 `/tanks`；时间不晚于现在，带时区
  RFC 3339；原因必填）。打开弹窗生成一次 `requestId`，超时（status 0）只给「用同一提交编号重试」（云端重放 200，不会重复保存）。只有
  `origin === 'cloud'` 的行有「撤销」（`DELETE .../stock-events/:eventId`，软删除）；设备上报的记录（含设备端补录）不能撤销。
  补录行没有的量是 null，显示「—」。只有 mock 测试，未对真实云端联调。
- 未做：历史趋势图（没装图表库）、WebSocket 报警推送。
- **远程配置（#4，云端契约 `cloud/docs/config-command-contract.md` 与 `tank-settings-contract.md`，依赖 cloud PR #13；OpenSpec
  `device-parameter-settings`）**：设备详情新增「参数设置」（`?tab=settings`）、「操作记录」（`?tab=commands`）；菜单「远程控制」
  （`/console/issue-command`）是设备列表入口。本期只有配置类，动作类（消音/清警/重启/泵阀）不提供入口。密度可写（g/cm³，0<v≤2；回报 >2 多半是按旧标注 kg/m³ 填的，只提示核对）；颜色（`oilColor`，20 色色板 `utils/oilColor.ts`，对照云端共享 fixture）只在 `media-options.oilColorSync` 为真时可改（2026-11-14）。
  - 当前值只来自设备回报（`tank-settings` / `device-settings` + `reportedAt`），null 显示「—」、0 照常显示，**不乐观更新**。
  - **没有逐台开放**（2026-11-09 用户决定）：管理员打开参数页即可直接修改；云端只剩部署级总闸，关闭时提交得到
    `config_commands_disabled`。整机设置只有平台管理员能改。
  - 只发改动字段；本地预检在 `utils/deviceConfig.ts`（范围同云端 `config_args.go`，四级液位顺序用当前值+改动合并，0 = 不启用），云端/设备才是裁决。三项修正不限范围（#28，用户决定）。`InputNumber` 不传 `min/max` 和小数 `step`（否则失焦静默改值、1000 显示成 1000.0）；修正框带 ± 切换（#30）。
  - 提交（`ConfigSubmitModal`）：打开确认弹窗生成一次 `requestId`，这次确认的重试都复用；确认时重读设备列表，油站变了就不发并刷新；
    `expectedStationId` 发**表单打开时**的值。超时/`command_delivery_unconfirmed`：先 `?requestId=` 找回，找不到只给「用同一提交编号重试」，
    **绝不换新编号自动重发**。`config_command_in_flight` 跳到在途命令。
    恢复的旧快照**不做前端油站预检**，原样重发同一编号（云端先重放、再复核油站）；只有重放之后的结论（`device_station_changed`、在途、
    `request_id_reused`）或 `invalid_args` 才了结它（`pendingSettledBy`），总闸关闭/403/5xx 仍保留（#18 F6，否则换站后旧快照会反复恢复、挡住新提交）。
  - 跟踪（`useCommandTracker`）：轮询以 `terminal` 停止，页面隐藏不发请求；`succeeded` 后读回镜像，`reportedAt` 严格晚于命令 `finishedAt`
    且字段相等才显示「设备回报值已一致」（`finishedAt` 缺失/非法一直等待，同小程序与契约 §6.5）；`expired` 显示「结果无法确认」。
  - 只有 mock/组件测试，**没有在浏览器里对真实云端联调，也没有真机验证**。

**油站（`/assets/stations`，GitHub issue #6，云端 yeweiyi-cloud#53）**：油站是 客户 → 油站 → 设备 的中间一层，只存在云端业务库。
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

**油站与大屏一对一、换机（GitHub issue #15，云端 yeweiyi-cloud#61，契约 `docs/station-device-replacement-contract.md` v1.3，
云端仓库内、SHA256 `bb1cb5ba…fdfc93`（v1.3），基准 cloud `08c0524`；云端已实现；后台已对隔离测试实例做 API 级联调，并用真实 Chrome 对生产构建做过 UI 级验收，见 `docs/handoffs/station-device-replacement.md`）**：这条**更正了 #6 的"一站多设备"假设**——
一个油站同一时刻至多一台大屏，一台大屏只属于一个油站；一台大屏下有多个罐，按「设备码 + 罐号」定位；换大屏用**新设备码**，
从新设备重新采集，不迁移/合并/接续旧设备历史，不删旧设备记录，不自动复制旧设备的用户授权。

- 错误按 `ApiError.code` 分支，不按 `error` 文案；`403 not authorized` 表示账号失效，**例外**是 yeweiyi-cloud#79 起带 `code: role_not_allowed` 的同一 body（账号正常、角色不允许）：不清 token，全局提示 `app.request.roleNotAllowed`（判定集中在 `isAccountRevoked`，别在页面里另写）。**不把云端 `error` 原文显示给用户**（yeweiyi-cloud#79）：页面归类不到的失败用完整的本地化兜底文案，`ErrorMessage.detail` 只放业务信息（如占用油站的设备编号）；全局兜底按 code/状态走 `fallbackMessage`。`ApiError` 带 `occupiedBy` /
  `currentDeviceName`（冲突响应附带）。契约新增的码：`station_occupied`、`station_device_changed`、`device_in_other_station`、
  `device_customer_mismatch`、`device_without_customer`；v1.1 起 404 带 `station_not_found` / `device_not_found`（对客户管理员，
  别客户的与不存在的完全相同），换机 400 带 `invalid_request` / `same_device`。没有 code（旧版云端）时退回合并提示。
- 展示：契约**不给** `GET /api/stations` 加设备字段（Q4），油站页「当前大屏」列由 `GET /api/devices` 的 `station_id` 推导
  （`src/utils/stationOccupancy.ts`）：一台显示编码、零台「未安装」、多台是历史冲突数据（迁移后不应再出现），标「异常」并给出数量，
  **不聚合、不挑一台**；设备列表取不到或旧版云端不带 `station_id` 时显示「未知」。
- 分配：`PUT /api/devices/:device/station`。`AssignStationModal` 把已被**别的设备**占用的油站禁用并标出占用者（预提示，列表可能过期）；
  云端 `409 station_occupied` 才是裁决，文案带 `occupiedBy`；400/404/409 之后**关闭弹窗并刷新**。普通分配不会顶替旧设备。
- 换机：设备页行内「换机」（只对 `station_id` 是数字的行，即旧设备）调用 `POST /api/stations/:id/replace-device`
  （`{deviceName, expectedCurrentDeviceName}`，后者必填，传页面看到的旧设备码；空油站传显式 `null`）。云端事务化，没有半成品，
  所以前端**没有**两步编排。新设备必须已按完整出厂编码**独立登记**（未登记 → 404），弹窗候选只列同客户、未分配油站的已登记设备；
  不提供改旧编码模拟换机的入口。弹窗显示旧/新编码和「新设备重新采集，不接续旧设备历史」，授权不自动复制。`changed:false` 是幂等重试。
- **解除油站关联（#69，取代原「删除设备」）**：设备页行内「解除油站关联」（`station_id` 是数字的行，两种管理员都有），Popconfirm 确认后
  `PUT /api/devices/:device/station {stationId: null}`，设备显示「未分组」、可再分配；设备档案、历史、ThingsBoard 实体与接入凭据都保留，终端照常上报。
  原「删除设备」`PurgeDeviceModal` 及 purge 请求函数已删除：云端对 `POST /api/devices/:device/purge` 恒 `410 device_purge_disabled`（平台管理员也是），
  永久清除由运维单独处理。`device_purge_in_progress` 的提示仍保留（运维执行中的任务仍会挡写入）。
- **油罐罐型展示（Issue #40，2026-11-10）**：监控页通过现有 `tank-settings` 独立读取设备回报罐型，由 `useTankShapes` 每分钟复核当前可见设备，隐藏停止、恢复／手动刷新重读，账号或设备切换丢弃旧响应。总览与详情复用 `TankPicture`：卧罐用 `tank-generic.png`，立罐用从设备端复制的 `tank-vertical.png`（CSS 裁掉透明边）；未上报、未知值或读取失败一律按卧罐，不出现「罐型未知」。总览罐卡不显示罐型文字，详情显示罐型与上报时间；配置读取失败不阻断遥测，401/403/404 清除罐型缓存。配置时间来自 `reportedAt`。
  缺失读数用「--」，报警、探针未连接用读数下方文字；保持 `TankDisplay.module.less` 布局与总览 `xl` 起每行四罐（#29）。实现及验证边界见 `openspec/changes/sync-reported-tank-shape/`。
  **右侧读数按设备显示器样式（#21，`components/TankReadout.tsx`，总览与详情共用）**：深蓝底白字标题条「罐号 + 介质」，报警/探针标签置于读数下方，避免挤压标题；
  下面固定五行 液位 → 容积 → 水高 → 温度 → 质量，标签/数值/单位三列对齐（真 `<table>`），一位小数，0 显示 0.0、缺失「--」；
  中文单位 毫米/升/℃/千克，英文 mm/L/℃/kg（`pages.console.tank.unit.*`）。详情页的水体积、空容、占比放在下方「更多读数」。
  **液位填充（2026-11-02）**：`TankPicture` 在两道箍之间的窗口里按该罐真实的 `oilRatioPct`/`waterRatioPct` 画油和水（水在下、油在上，
  `utils/tankFill.ts`，规则同小程序）：探针断线（`probeConnected=false`，设备按自身断线阈值才报，#75）灰色保留上次液位并标「通讯异常」「上次读数」，`hasReading=false`（开机后未采到读数）不画、读数显示「--」并标「暂无读数」，占比异常夹到 0..100；正常时油按 `tank-settings` 回报的罐颜色 `oilColor` 画（同设备，未上报用默认橙），报警红、故障灰；窗口位置是对着 `tank-generic.png` 逐像素量的，
  **换图要重新量**（`TankPicture.tsx` 里的 `WINDOW`）。
  **设备详情一次只显示一个罐（同小程序「点谁是谁」）**：总览点哪个罐就带 `?tank=<协议罐号>`（不是列表位置）进详情，`SnapshotTab` 只显示这个罐
  （`TankDetail`：液位、容积、质量、温度、水高、水体积、空容、油品占比、水占比、报警状态、探针），缺省最小罐号；请求的罐不可见就提示并改显示最小罐号
  （`utils/tankPick.ts`）；多个罐时上方有切换，只改 URL 的 `tank`（replace，保留 `station`/`tab`）。
- 监控数据隔离：`useRecordsQuery` 的状态记录归属设备，换设备的那一帧也不暴露旧设备的数据/错误；设备详情页签按设备编号做
  `key`，路由换设备即整体重建。迟到的旧响应由请求序号丢弃。仓库里没有罐选择、WebSocket 订阅、跨页缓存；以后加了，必须按设备码归属。
  已停报超保留期的设备可能误报 404（云端既有缺陷 #28），按「无数据」处理，不当错误。
- **油站入口的监控页自动跟随换机（契约 §8.1）**：带 `?station=N` 打开的设备详情（`/console/devices/:device?station=N`，来源：设备总览的
  卡片、油站页「查看监控」）用 `useStationCurrentDevice` 每 30 秒刷新 `GET /api/devices`，油站当前设备变成 B 就 `replace` 跳到 B（保留
  `tab`/`station`），页签按设备编号 key 整体重建，A 的数据/缓存/定时器清掉。记录页的 `DevicePicker` 选定油站时同理：当前设备由 A 变 B 且
  正在看 A 才自动切，用户手动选了别的不动；多台（历史冲突）或暂无设备不据此切换。不带 `?station=` 的独立入口按设备编码直接看 A，保留。
  云端不推送换机事件，只能靠轮询得知，所以切换最长有一个轮询周期的延迟。
- **员工按罐授权（#17，云端契约 `docs/tank-grants-contract.md` v1.1，cloud#63；v1.1 起**仅平台管理员**读写授权，客户管理员 `GET/PUT tank-grants` 一律 403，SHA256 待按 v1.1 重算）**：「资产管理 → 员工授权」
  （`/assets/staff`，仅平台管理员，路由 `access: canPlatformAdmin`）。列出员工（`GET /api/users` 过滤 `customer_user`，全部客户），
  行内「分配油罐」弹窗按 **油站 → 设备 → 罐** 勾选：罐号来自 `GET /api/devices/:device/tanks`（设备已上报的罐），已授权来自
  `GET /api/users/:id/tank-grants`，保存是对**单台设备**的整组替换 `PUT /api/users/:id/tank-grants/:device {tanks:[...]}`；空选择 = 撤销，
  不自动全选；设备从未上报时只能撤销（否则 409 `tanks_unknown`）；授权键是 `(员工, device_name, 罐号)`，没有按油站/整机授权。错误按 `code`
  （`invalid_tank`/`tanks_unknown`/`device_customer_mismatch`/`grant_not_applicable`/`user_not_found`/`device_not_found`），过期结果刷新可选罐，
  `user_not_found` 关闭弹窗。设备列表里看不到但仍有授权的设备在「其它设备上的既有授权」下可撤销。本页**不创建/停用/改客户**员工（仍只在 `/ops/users`，平台管理员）。
  旧 `user_devices` 绑定不转换（P2）：升级后员工在被明确分配前什么都看不到。后台不调 `POST /api/devices/bind`（已停用 410，扫码用 resolve，归小程序）。
- **客户开通向导（#14，云端契约 `docs/customer-onboarding-contract.md`，cloud#60；云端未联调前只有 mock 测试）**：客户有
  `onboardingStatus`（`configuring`|`active`，字段缺失是旧版云端，列表显示「未知」不当成已开通）。配置中客户下**所有**账号不能登录/续期
  （`403 customer_not_onboarded`）：这是业务提示不是账号失效，登录页和全局错误处理单独提示、不清 token。向导页
  `/assets/customers/:id/onboarding`（仅平台管理员，不进菜单）串起现有接口：客户管理员（手机号+密码）→ 油站 → 登记设备并分配油站，
  每步即时保存，进度一律以 `GET /api/customers/:id/onboarding` 为准（刷新恢复），「完成开通」按钮仅在 `complete` 时可点，
  `409 onboarding_incomplete` 刷新并按 `missing` 提示。新建客户成功后自动进入向导；客户列表对配置中的客户给「开通向导」入口。
  向导只收完整出厂编号（不做短码补全），设备登记与分配油站是两次请求，分配失败时设备留在该客户下，提示去设备页重试。
- **监控页以油站为主体（2026-10-15）**：一油站一台大屏，所以总览**不再按油站分段**，每张卡片以油站名为标题、设备编码为副标题（未分组的只显示设备编码，排在最后）；
  平台管理员在筛选条第一位多一个「客户」筛选（`?customer=`，换客户清掉 `?station=`，客户管理员不显示，逻辑在 `useStationFilter`）。记录页（报警/入库/出库）：选定油站后设备由油站决定、
  设备下拉隐藏（显示「当前大屏」，换机自动跟随）；选「全部油站/未分组」时仍是设备下拉；什么都没指定时默认选第一个有设备的油站。
- **新建客户一步到位（2026-10-15）**：`CreateCustomerModal` 收 客户名 + 管理员手机号/初始密码 + 第一个油站名 + 大屏出厂编号，依次请求
  客户 → 管理员 → 油站 → 登记设备 → 分配油站 → `complete`，成功即「已开通」，不再进向导。管理员失败回滚空客户（表单保留）；管理员建好后
  客户已有账号不能再整体回滚，后面任一步失败只提示并进向导接着做（设备登记失败会先删刚建的空油站）。更多油站在「资产管理」里加。
  向导现在只用于这类中途失败和旧数据里「配置中」的客户。
  向导页本身也只有一个动作：管理员已建时第 2 步按钮是「添加并开通」，油站和大屏建好、条件一齐就自动 `complete` 并回客户列表；「完成开通」按钮只在条件已齐但上次开通没成功时出现，不再有灰着的第二个按钮。
- **开通向导合并「油站」与「设备」（2026-10-15）**：一油站一台大屏，所以向导只有两步——1 客户管理员、2 油站与大屏。第 2 步一次提交 = 新建油站 + 登记设备 + 分配油站（云端没有一次建三样的接口，前端串起来）：
  设备登记失败就 `DELETE` 刚建的空油站回滚，分配失败时设备已登记、提示去设备页重试。已有油站列在下面并标出各自的大屏。「完成开通」成功后回客户列表。
- **介质库（#27，契约 `cloud/docs/media-library-contract.md`）**：`/assets/media` 仅平台管理员，手动维护公共/客户专属介质，保存不影响设备；客户列表有「专属介质」入口（`?customer=` 锁定）。参数设置里介质由管理员（平台、客户，2026-11-14 起）选，提交 `mediaId`，回显按命令实际的 `oilTypeIndex`（`trackedArgs`）；`media-options` 两种管理员都请求。
- 没做：扫码导航（归 #63）。短码跨前缀多重匹配风险 R1 归 #63，后台不处理。

`src/services/ant-design-pro/` 按页面逐个补请求函数和类型，先读 `yeweiyi-cloud` 的
`internal/api/router.go`。

## 项目概要

液位仪云平台的 Web 前端，**一个仓库、两个分区，靠路由和角色区分**（不是两个仓库、两次部署）：

- **`/ops/*` 运维/管理控制台**：账号管理、审计日志（设备全量视图已并入 console/assets；配对密钥铸造暂无页面）。仅
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

- `../android/` — Android 设备端。本仓库不直接对接它；它的行为通过
  `yeweiyi-cloud` 中转。只有在某个 `/console/*` 页面的数据看起来"设备侧才能解释"时才需要读它
  （例如控制指令为什么被拒绝、出入库记录为什么缺失）。
- `../cloud/` — **唯一的后端，本仓库要对接的一切 API 都在这里**。它的
  `CLAUDE.md` 是 REST/WS 接口的权威来源，路由列表见该仓库 `internal/api/router.go`；写任何
  请求函数前先读一遍，不要凭记忆假设接口形状。它的 `openspec/changes/add-admin-console/
  specs/*/spec.md` 记录了 `/ops/*` 对应的账号管理与设备全量视图两块能力的行为约定
  （谁能调、失败时返回什么、审计覆盖哪些结果）——这是 `/ops/*` 页面唯一的规格来源。
- `../miniapp/` — 同一批客户角色（`customer_admin`/`customer_user`）的
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
src/pages/ops/          运维控制台：users / audit（设备与客户已并入 console/assets）
src/pages/console/      客户 PC 监控台：devices / device-detail / alarms / oil-in-out（入库、出库共用）/ issue-command
src/pages/assets/  资产管理：customers（仅平台管理员）/ stations / devices（登记·客户归属·油站归属）
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
- **2026-10-08 已部署 ECS**：与 cloud 共用 `8.154.38.176` 上的 Nginx，静态产物位于
  `/var/www/yeweiyi-admin/current`；同源 `/api/`、`/ws/`、`/healthz` 转发到 `127.0.0.1:8080`。
  备案期间入口为 `http://8.154.38.176/user/login`；HTTPS 域名 `api.zhongchengelec.com` 已接入静态配置，
  但外网 HTTPS 当前连接失败，不能视为可用。发布/回滚见 `deploy/README.md`。GitHub Actions `.github/workflows/ci.yml` 在 PR 上执行检查与构建，
  main 更新后通过检查自动部署同一构建产物；部署失败恢复前一静态版本。依赖锁文件必须提交，CI 使用 `npm ci`。
  `config/proxy.ts` 的 `test`/`pre` 仅用于本地开发，仍是占位符，与生产 Nginx 无关。
- `/console/*` 的趋势图、报警实时推送还没做（远程配置已接，见上文「远程配置」）；图表和告警的展示形式需要产品侧
  决定，不要自己臆造。
- 本仓库已接入 OpenSpec（`openspec/config.yaml`，变更放在 `openspec/changes/`），与 `yeweiyi-cloud` 各用各的配置，
  不共享。
