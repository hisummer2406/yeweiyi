## 1. 会话工具与类型

- [x] 1.1 新增 `src/utils/auth.ts`：`getToken/setToken/clearToken`（localStorage，try/catch 降级内存）与 `decodeToken(token)`（解码 payload，要求 `uid`、`sub`、`role`、`exp` 齐全且未过期，否则返回 `undefined`）
- [x] 1.2 为 `auth.ts` 写 vitest：有效 token、过期、非 JWT 字符串、缺 claim、未知角色原样保留、存储抛错时降级
- [x] 1.3 重写 `typings.d.ts`：`API.CurrentUser = {userId, username, access}`、`API.LoginParams = {username, password}`、`API.LoginResult = {token}`、`API.Role`；删除 `RuleListItem`/`RuleList`/`FakeCaptcha`/`NoticeIcon*`/`ErrorResponse` 等模板类型

## 2. 请求层

- [x] 2.1 `src/services/ant-design-pro/api.ts` 仅保留 `login(body)`（`POST /api/login`，`skipAuth` + `skipErrorHandler`）；删除 `currentUser`、`outLogin`、`getNotices`、`rule*`；删除 `login.ts`（captcha），更新 `index.ts`
- [x] 2.2 `requestErrorConfig.ts`：请求拦截器附加 Bearer（`skipAuth` 跳过）；删除 `errorThrower` 与 `ResponseStructure`/`ErrorShowType` 信封逻辑；`errorHandler` 按 HTTP 状态处理，401（非 `skipErrorHandler`）清 token 并跳登录带 `redirect`，其余显示 `response.data.error`
- [x] 2.3 为请求拦截器与 errorHandler 补测试：带/不带 token、`skipAuth`、401 跳转、403 不清 token、无信封的 200 响应不报错

## 3. 初始状态、权限与登出

- [x] 3.1 `app.tsx`：`getInitialState` 改为从 token 同步派生 `currentUser`，`fetchUserInfo` 同步化；删除 `queryCurrentUser` 引用；`avatarProps` 去掉 `avatar`/`ProUser` 占位，改用 `username`
- [x] 3.2 `access.ts` 保持逻辑不变；更新 `access.test.ts` 夹具为新 `CurrentUser` 形状，并补"未知角色两项均为 false"用例
- [x] 3.3 更新 `app.test.tsx` 以匹配同步派生行为（有效 token、无 token、过期 token 跳转、登录页不派生）
- [x] 3.4 `AvatarDropdown.tsx`：移除 `outLogin`，退出登录仅 `clearToken()` + 清 `currentUser` + 跳转 `/user/login`；展示 `username` 与角色中文名

## 4. 登录页

- [x] 4.1 `src/pages/user/login/index.tsx` 裁剪为账号密码表单：删 Tabs、`ProFormCaptcha`、`autoLogin`、忘记密码、第三方登录；提交走新 `login`，成功后 `setToken` → `fetchUserInfo` → 跳转 `redirect` 或 `/`
- [x] 4.2 区分错误提示：401 → "账号或密码错误"；400/5xx/网络 → 通用失败提示；清理不再使用的 locale key（至少 zh-CN、en-US）
- [x] 4.3 更新 `login.test.tsx` 与快照，覆盖成功、401、5xx 三种路径

## 5. 清理与文档

- [x] 5.1 grep 确认 `types/index.d.ts`、`types/cache/mock/login.mock.cache.js` 中的模板 currentUser 示例无人引用后删除或裁剪
- [x] 5.2 更新 `CLAUDE.md`「项目状态」与「仍未定案或未做的事」：登录已对齐、当前用户由 JWT 派生、无后端登出
- [x] 5.3 运行 `npm run tsc`、`npm run lint`、`npm run test` 全部通过
- [x] 5.4 联调：本地起 `yeweiyi-cloud`，用 `platform_admin` 与一个客户角色账号各登录一次，确认 `/ops` 菜单仅前者可见、刷新后保持登录、退出后回登录页（若无法启动后端，在交付说明中明确"未联调"）
