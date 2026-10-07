## ADDED Requirements

### Requirement: 账号密码登录
系统 SHALL 通过 `POST /api/login` 以 `{username, password}` 登录，成功响应体为 `{token}`。登录页 SHALL 只提供账号与密码输入，
不提供手机号/验证码、忘记密码、自动登录或第三方登录入口。

#### Scenario: 登录成功
- **WHEN** 用户提交正确的账号密码且后端返回 200 `{token}`
- **THEN** 系统保存该 token，并跳转到 `redirect` 参数指定的地址；没有 `redirect` 时跳转到 `/`

#### Scenario: 账号或密码错误
- **WHEN** 后端返回 401（含账号被停用的情形，后端对两者返回相同响应）
- **THEN** 登录页显示"账号或密码错误"，不保存 token，也不区分账号不存在或已停用

#### Scenario: 请求参数或服务异常
- **WHEN** 后端返回 400、5xx，或网络不可达
- **THEN** 登录页显示与账号密码错误不同的提示，不保存 token

### Requirement: 请求携带凭据
系统 SHALL 在 token 存在时为发往 `/api/*` 的请求附加 `Authorization: Bearer <token>`；`POST /api/login` 本身 SHALL NOT 附带该头。

#### Scenario: 已登录请求
- **WHEN** 本地存有 token 并发起任意 `/api/*` 请求（登录除外）
- **THEN** 请求头包含 `Authorization: Bearer <token>`

#### Scenario: 未登录请求
- **WHEN** 本地没有 token
- **THEN** 请求不带 `Authorization` 头

### Requirement: 当前用户由 JWT claims 派生
系统 SHALL 从 token 的 payload 解码出 `uid`、`sub`、`role`、`exp`，得到 `CurrentUser = {userId, username, access}`，
且 SHALL NOT 为获取当前用户而调用后端接口。解码结果只用于界面可见性，系统 SHALL NOT 将其视为授权依据。

#### Scenario: 有效 token
- **WHEN** 本地 token 能解码，且 `exp` 晚于当前时间
- **THEN** `currentUser` 为 `{userId: uid, username: sub, access: role}`

#### Scenario: 过期或损坏的 token
- **WHEN** token 缺失、无法解码、缺少 `uid`/`sub`/`role`，或 `exp` 已过
- **THEN** 系统清除 token，视为未登录并跳转 `/user/login?redirect=<原地址>`

#### Scenario: 未知角色
- **WHEN** `role` 不是 `platform_admin`、`customer_admin`、`customer_user` 之一
- **THEN** `canPlatformAdmin` 与 `canConsole` 均为 false

### Requirement: 角色决定分区可见性
`access.ts` SHALL 保持：`platform_admin` 可进入 `/ops/*` 与 `/console/*`；`customer_admin`、`customer_user` 只能进入 `/console/*`。

#### Scenario: 平台管理员
- **WHEN** `access` 为 `platform_admin`
- **THEN** `canPlatformAdmin` 与 `canConsole` 均为 true

#### Scenario: 客户角色
- **WHEN** `access` 为 `customer_admin` 或 `customer_user`
- **THEN** `canPlatformAdmin` 为 false，`canConsole` 为 true

### Requirement: 后端错误格式适配与 401 处理
系统 SHALL 按 HTTP 状态码判定请求成败，并读取失败响应体中的 `error` 字符串作为提示；SHALL NOT 要求成功响应带有 `success` 字段。
对非登录请求收到 401 时，系统 SHALL 清除 token 并跳转登录页。

#### Scenario: 成功响应无信封
- **WHEN** 后端返回 200 且响应体为 `{"rows": [...]}`
- **THEN** 调用方原样得到该响应体，不被判为失败

#### Scenario: token 失效
- **WHEN** 已登录状态下某个非登录请求返回 401
- **THEN** token 被清除，页面跳转 `/user/login?redirect=<原地址>`

#### Scenario: 无权限
- **WHEN** 请求返回 403
- **THEN** 系统显示后端返回的 `error` 文本，不清除 token，不跳转登录页

### Requirement: 本地登出
系统 SHALL 在用户点击退出登录时清除本地 token 与 `currentUser` 并跳转登录页，且 SHALL NOT 调用后端接口（后端无登出端点，token 在过期前仍有效）。

#### Scenario: 退出登录
- **WHEN** 用户点击"退出登录"
- **THEN** 本地 token 被清除，页面跳转 `/user/login`，且不发出任何网络请求
