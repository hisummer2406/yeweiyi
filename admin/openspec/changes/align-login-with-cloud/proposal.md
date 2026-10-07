## Why

`src/services/ant-design-pro/*`、登录页、`app.tsx` 与 `requestErrorConfig.ts` 仍是 Ant Design Pro 模板的示例实现：
调用的 `/api/login/account`、`/api/currentUser`、`/api/login/outLogin` 在 `yeweiyi-cloud` 里**都不存在**，
响应也被假定为 `{success, data}` 信封。真实后端只有 `POST /api/login`（请求 `{username,password}`，成功返回
`{token}`，失败为 HTTP 401/400 + `{error}`），没有 currentUser / 登出接口。登录不通，其余所有页面（`/ops/*`、
`/console/*`）都无法接入，所以这是第一步。

## What Changes

- 登录对接 `POST /api/login`；token 保存在浏览器本地，后续请求自动带 `Authorization: Bearer <token>`。
- 当前用户不再请求接口，改为**解码 JWT claims**（`uid`、`role`、`sub`、`exp`）得到，仅用于菜单/路由可见性；
  真正的鉴权仍由后端每次读库判定。
- `API.CurrentUser` 重写为 `{ userId, username, access }`，`access` 取 `platform_admin | customer_admin | customer_user`；
  `src/access.ts` 的判断逻辑保持不变，仅换数据来源。
- 请求错误处理改为适配后端真实格式（HTTP 状态码 + `{error}`），**移除**会把所有成功响应误判为失败的
  `{success}` 信封逻辑；收到 401 时清除 token 并跳转登录页。
- 退出登录改为纯本地操作（清除 token），不再调用不存在的 `outLogin`。
- 登录页裁剪为只有账号 + 密码：移除手机号/验证码 Tab、忘记密码、自动登录、第三方登录。
- 删除模板示例：`rule`、`notices`、`captcha`、`currentUser`、`outLogin` 请求函数与 `RuleListItem`、`FakeCaptcha`、
  `NoticeIcon*` 等类型。
- 同步更新 `CLAUDE.md` 的项目状态说明。

## Capabilities

### New Capabilities
- `admin-auth`: 登录、会话（token 存取与附带）、由 JWT claims 派生的当前用户与角色、401 处理、本地登出。

### Modified Capabilities
<!-- 无：openspec/specs/ 目前为空 -->

## Impact

- 代码：`src/services/ant-design-pro/{api,login,typings.d,index}.ts`、`src/app.tsx`、`src/requestErrorConfig.ts`、
  `src/access.ts`（及测试）、`src/pages/user/login/*`、`src/components/RightContent/AvatarDropdown.tsx`、
  `types/index.d.ts` 中的模板示例类型、`CLAUDE.md`。
- 后端：**不需要任何改动**。仅依赖 `yeweiyi-cloud` 现有的 `POST /api/login` 与 JWT 格式（`internal/api/auth.go`）。
- 依赖：不新增 npm 依赖（JWT payload 用 `atob` 解码，不验签）。
- 风险点见 design.md：角色变更最长 24h（token TTL）后菜单才刷新；token 存 localStorage 的 XSS 取舍。
