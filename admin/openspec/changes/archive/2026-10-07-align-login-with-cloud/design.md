## Context

后端事实（`yeweiyi-cloud` `internal/api/router.go`、`auth.go`）：

- `POST /api/login`：`{username,password}` → 200 `{token}`；400/401/500 → `{error}`。被停用账号与密码错误同为 401。
- JWT：HS256，claims `{uid, role, sub=username, exp, iat}`，默认 TTL 24h（`auth.tokenTTL` 可配）。
- 受保护路由只认 `Authorization: Bearer`；中间件**每次请求读库**取角色/停用状态，**不信任 JWT 里的 `role`**。
- **没有** `/api/currentUser`、`/api/me`、登出、刷新 token 接口。
- 错误响应一律 HTTP 状态码 + `{error: string}`，没有 `{success,data}` 信封。

前端现状：`app.tsx` 的 `getInitialState` 调 `queryCurrentUser`，`requestErrorConfig.ts` 的 `errorThrower` 对没有
`success:true` 的响应抛 `BizError`——接真实后端后**每个成功请求都会被当成失败**。

## Goals / Non-Goals

**Goals:**
- 登录、会话、当前用户、角色门禁全部对齐真实后端，且不要求后端改动。
- 类型里不再有任何后端不存在的字段（name/avatar/email 等）。

**Non-Goals:**
- 不新增后端接口（如 `/api/me`）；不做 token 刷新；不做"记住我"。
- 不做具体业务页面（`/ops/*`、`/console/*` 内容）。
- 不改国际化方案；只增删本变更涉及的文案 key。

## Decisions

**D1：当前用户 = 解码 JWT claims（不新增 `/api/me`）。** 用户已确认。前端用 `atob` 解码 payload，不验签（无密钥，也无必要）。
理由：零后端依赖，可立即落地。替代方案 `GET /api/me` 角色实时准确，但需先改 cloud 并更新其文档（跨仓库规则），拆成两步，本次不取。
**该决定的边界**：JWT 派生的角色只决定菜单/路由显隐；任何数据访问的授权仍由后端判定，所以篡改 localStorage 只能让界面显示多余菜单，
请求会被后端 403。

**D2：token 存 `localStorage`，key 为 `yeweiyi_admin_token`。** 封装在 `src/utils/auth.ts`（get/set/clear/decode），
所有读写包 try/catch（隐私模式可能抛错，降级为内存）。替代方案：内存 only（刷新即登出，PC 控制台体验差）；
HttpOnly cookie（后端不支持）。取舍：XSS 可读 token——本项目无富文本/第三方脚本注入面，且 token 仅 24h，接受。

**D3：请求层。** `requestInterceptors` 读 token 附加 Bearer，登录请求通过 `skipAuth: true` 选项跳过。
删除 `errorThrower`；`errorHandler` 按 `error.response.status` 处理：401（非登录）→ 清 token + 跳登录并带 `redirect`；
其他状态 → `message.error(response.data.error ?? 默认文案)`。保留 `skipErrorHandler` 约定，登录页自己处理 401 文案。

**D4：`CurrentUser` 类型。** `{ userId: number; username: string; access: 'platform_admin' | 'customer_admin' | 'customer_user' }`，
放在 `typings.d.ts` 的 `API` 命名空间。`access.ts` 签名不变（读 `currentUser.access`），所以 `canPlatformAdmin`/`canConsole` 逻辑与已有测试断言不变，
仅测试夹具的字段名更新。未知角色字符串在解码时原样保留，由 `access.ts` 判为无权限（不在解码层抛错，避免新增角色导致整站白屏）。

**D5：`getInitialState`。** 无 token 或解码失败/过期 → `currentUser` 为 `undefined`，由既有 `onPageChange` 重定向登录；
不再有"请求失败再跳转"的异步分支。`fetchUserInfo` 改为同步从 token 重新派生，登录成功后调用以更新 `initialState`。

**D6：登出本地化。** `AvatarDropdown` 去掉 `outLogin` 调用，直接 `clearToken()` 后跳转。头像区显示 `username` 与角色中文名，
不再使用 avatar/name。

**D7：登录页裁剪。** 仅保留账号密码表单；删除 `Tabs`、`ProFormCaptcha`、`getFakeCaptcha`、`autoLogin`、忘记密码、第三方图标，
以及对应无用的 locale key。

## Risks / Trade-offs

- [角色/停用变更后菜单滞后至多 24h] → 后端每次读库拒绝越权请求，滞后仅影响显隐；若后端返回 403 页面展示后端 `error`。
  后续若需要实时，再单独提 `/api/me`。
- [token 存 localStorage 可被 XSS 读取] → 见 D2；不引入第三方脚本，TTL 24h。
- [JWT claims 形状由后端定义，将来改名会静默失效] → 解码函数对缺字段返回 `undefined`（视为未登录），并有单测固定当前形状；
  claims 变更属跨仓库协议变更，应先在 cloud 落地。
- [后端无登出] → 被盗 token 在过期前仍可用；前端无法缓解，记录为已知限制。
- [删除模板 API 可能误伤仍被引用的代码] → 任务里先 grep 引用，`npm run tsc` 与 `npm run test` 通过后才算完成。

## Migration Plan

纯前端改动，无数据迁移。回滚即回退提交。联调前提：按 `yeweiyi-cloud` CLAUDE.md 启动后端，并有一个已建好的 `platform_admin` 账号。
