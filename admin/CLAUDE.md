# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件，不要将该链接替换为普通文件。

## 项目状态：2026-10-07 登录已对齐 yeweiyi-cloud，其余页面功能未接

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

`src/services/ant-design-pro/` 现在只剩 `login`；接新页面时按页面逐个补请求函数和类型，先读
`yeweiyi-cloud` 的 `internal/api/router.go`。

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
src/access.ts           canPlatformAdmin / canConsole 两个角色判断
src/pages/ops/          运维控制台：users / devices / pairing-secret / audit
src/pages/console/      客户 PC 监控台：devices / device-detail / issue-command / oil-in-out
src/pages/user/login/   登录（两个分区共用）
src/pages/account/      个人设置（占位）
src/pages/exception/    403 / 404 / 500
src/services/           对接后端的请求函数——目前只有登录；src/utils/auth.ts 管 token 与 JWT 解码
```

## 仍未定案或未做的事

- 除登录外，各业务接口（账号、设备、遥测、控制、进销存、审计）的类型与请求函数都还没写。接哪个
  页面就先把对应类型和请求函数按真实接口补上，不要在没对齐类型的情况下假装接口已经通了。
- 登录页没有联调过真实后端（见 `align-login-with-cloud` 任务 5.4）；需要 `platform_admin` 与客户
  角色账号各验证一次。
- 生产环境的反向代理目标（`config/proxy.ts` 的 `test`/`pre`）还是占位符，部署方式和域名都没有
  定案，跟 `yeweiyi-miniapp`/`yeweiyi-cloud` 的部署决策一样悬而未决。
- `/console/*` 要覆盖的具体交互（图表、告警展示形式等）还没有设计，只建了和小程序对应能力对齐的
  空页面，具体长什么样需要产品侧决定，不要自己臆造。
- 本仓库已接入 OpenSpec（`openspec/config.yaml`，变更放在 `openspec/changes/`），与 `yeweiyi-cloud` 各用各的配置，
  不共享。
