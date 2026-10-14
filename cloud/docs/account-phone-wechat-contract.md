# 客户手机号账号与微信小程序登录：三端契约（草案，待确认）

状态：**已确认（2026-10-13），范围仅 #59。** 实施进度：云端 PR-1（手机号账号、创建权限收紧、`/api/me`、密码登录规则、
令牌语义）与 PR-2（微信接口，仅假微信服务器验证）已实现；网页、小程序见各自 PR。#60、#63 不在本批，§9 的限制仍然成立。
主需求：[cloud#59](https://github.com/hisummer2406/yeweiyi-cloud/issues/59)；
配套：[miniapp#4](https://github.com/hisummer2406/yeweiyi-miniapp/issues/4)、
[admin#13](https://github.com/hisummer2406/yeweiyi-admin/issues/13)。
相关但**不在本契约实现范围**：[cloud#60](https://github.com/hisummer2406/yeweiyi-cloud/issues/60)（客户开通）、
[cloud#63](https://github.com/hisummer2406/yeweiyi-cloud/issues/63)（油罐授权）。本契约只为它们预留位置，见 §9。

> 取舍：issue 正文有多处被后续"更正"覆盖。本文以 2026-10-12/13 的更正为准：员工只能由平台管理员创建；
> 客户管理员不能新增/修改/停用/启用/删除员工，也不能变更其客户归属；扫码不授权。

## 1. 现状与差距（已核对代码，main @ 02ca28d）

| 项 | 现状 | 需要 |
| --- | --- | --- |
| 用户标识 | `users.username` 唯一、`password_hash` 必填；无手机号、无微信身份 | 客户侧手机号；员工无密码；微信身份表 |
| `POST /api/login` | username+password；停用与密码错同为 `401 invalid username or password` | 增加 `phone` 登录；员工不能经密码登录 |
| `POST /api/users` | 平台管理员建任意角色；**客户管理员可建本客户 `customer_user`**（`planUserCreation`） | 仅平台管理员；员工无密码 |
| `PUT /api/users/:id` | 客户管理员可停用/启用本客户员工（`decideUserUpdate`） | 仅平台管理员 |
| JWT | `sub`=用户名；`authAt`=上次输入密码 | `sub` 不再承诺是用户名；`authAt`=上次完整认证 |
| 当前用户信息 | **没有** `GET /api/me`；客户端靠 JWT `sub` | 新增 `GET /api/me` 与登录响应 `user` |
| 微信 | 无 | 登录/手机号绑定接口，可配置关闭 |

## 2. 数据模型

迁移 `000021_phone_accounts`（成对 up/down，新表新列写中文注释）：

- `users.phone TEXT NULL`：规范化手机号。`platform_admin` 为 NULL；`customer_admin`/`customer_user` 必填
  （`CHECK`）。全局唯一（`UNIQUE` 部分索引 `WHERE phone IS NOT NULL`）。
- `users.password_hash` 改为可空，`CHECK`：`platform_admin`/`customer_admin` 非空，`customer_user` 必须为 NULL。
  不使用公共默认密码、占位哈希。
- 客户侧账号的 `users.username` 由服务端设为等于 `phone`（客户端不可提交），继续满足既有 `NOT NULL UNIQUE`、
  审计与日志引用。`CHECK`：客户角色 `username = phone`。
- 新表 `user_wechat_identities(user_id UNIQUE → users, appid, openid, bound_at, last_login_at)`，
  `UNIQUE(appid, openid)`。一个用户至多一个微信身份，一个微信身份至多一个用户。不存 `session_key`、不依赖 UnionID。
- 不迁移旧数据（系统未上线）。开发库里的旧客户账号需用新契约重建。

### 手机号规范化（待确认 Q1）

建议首版只支持中国大陆：去掉空格/连字符与 `+86`/`86` 前缀后匹配 `^1[3-9]\d{9}$`，存 11 位数字。
微信 `getuserphonenumber` 返回的 `purePhoneNumber`+`countryCode` 用同一函数规范化；`countryCode != 86` 一律拒绝。

## 3. 用户信息与令牌

### 3.1 `user` 对象（登录响应、`GET /api/me`）

```json
{ "id": 12, "role": "customer_admin", "customerId": 3,
  "phone": "138****0000", "username": null, "disabled": false }
```

- `phone`：对**本人**与**平台管理员**返回完整号码，其余场景（客户管理员列员工）返回脱敏 `138****0000`。
  脱敏规则：保留前 3 后 4。
- `username`：仅 `platform_admin` 有值，客户侧为 `null`。**客户端展示名与存储键一律用 `id`/`phone`，
  不再读 JWT `sub`。** 油站偏好等本地存储键应改用 `id`。

### 3.2 令牌

- `sub` 改为用户 ID 的十进制字符串；`uid` 不变。`role` 仍只签发不信任。
- 新增声明 `amr`：`"pwd"` 或 `"wx"`，仅供审计/排查，不参与授权。
- `authAt` 语义改为"最近一次**完整认证**（密码或微信 `code` 验证）的时间"。续期沿用，
  `exp ≤ authAt + auth.maxSessionAge` 不变；#51 的 `X-Renewed-Token` 行为不变；`/ws/alarms` 仍在原令牌到期时
  以 `token expired` 关闭，客户端用新令牌重连。
- 已绑定微信的客户端重新调用 `/api/auth/wechat/login` 视为新的完整认证，`authAt` 重置。因此对微信用户
  `maxSessionAge` 的实际含义是"静默重新验证微信身份的间隔"，不是强制人工重新输入（待确认 Q4）。

## 4. 接口

所有新增错误响应形如 `{"error": "<文案>", "code": "<机器码>"}`；既有接口保留只有 `error` 的形状。
客户端按 `code` 分支，不按文案或仅按状态码。

### 4.1 `POST /api/login`（网页密码登录）

请求：`{"password": "...", "username": "..."}`（平台管理员）或 `{"password": "...", "phone": "138..."}`
（客户管理员）。**两字段必须恰好给一个**，服务端不猜测输入含义，否则 `400 invalid_request`。

- `username` 路径只接受 `platform_admin`；`phone` 路径只接受 `customer_admin`。`customer_user` 以任何方式都得到
  与"密码错误"完全相同的 `401 invalid_credentials`，且执行等价的哈希比较以避免计时差异。
- 密码错、账号不存在、角色不允许、已停用：`401 invalid_credentials`（沿用现有"不泄露账号是否存在/停用"）。
- 密码正确但所属客户未开通：`403 customer_not_onboarded`（**#60 落地后启用**，见 §9）。
- 成功：`200 {"token": "...", "user": {...}}`（`token` 字段位置不变，旧客户端不受影响）。

### 4.2 `GET /api/me`

受保护。返回 §3.1 的 `user`。身份与角色读库，不读 JWT。

### 4.3 `POST /api/auth/wechat/login`（已绑定微信的恢复登录）

请求：`{"loginCode": "<wx.login 的 code>"}`。服务端调 `jscode2session` 换 `openid`，查
`user_wechat_identities`。

| 结果 | 响应 |
| --- | --- |
| 已绑定且账号可用 | `200 {"token","user"}` |
| 未绑定 | `401 wechat_not_bound`（客户端转入首次手机号授权） |
| 账号停用 | `403 account_disabled` |
| 账号已删除（绑定行随之消失） | 等同未绑定 |
| 所属客户未开通 | `403 customer_not_onboarded`（#60 后） |
| `loginCode` 无效/过期/已用 | `401 wechat_code_invalid` |
| 微信能力关闭 | `503 wechat_unavailable` |
| 微信请求失败/超时 | `502 wechat_upstream_error` |

### 4.4 `POST /api/auth/wechat/phone-login`（首次手机号授权并绑定）

请求：`{"loginCode": "...", "phoneCode": "<getPhoneNumber 的 code>"}`。两个 code 来源与用途不同，
不得互换；各自**只用一次**，失败后客户端必须重新获取，不重放。

服务端：① `jscode2session` 得 `openid`；② `getuserphonenumber` 得手机号，校验 `watermark.appid` 等于配置的 AppID，
规范化；③ 单事务内 `SELECT … FOR UPDATE` 按手机号取用户并绑定。**不信任客户端提交的手机号或 openid**
（接口不接受这两个字段）。

| 结果 | 响应 |
| --- | --- |
| 绑定成功，或该 openid 已绑定到同一用户（幂等） | `200 {"token","user"}` |
| 该手机号没有预开通的业务账号 | `404 phone_not_provisioned`（提示联系管理员；不自动注册） |
| 账号停用 | `403 account_disabled` |
| 该 openid 已绑定到**另一**用户，或该用户已绑定**另一个** openid | `409 wechat_identity_conflict`，不覆盖、不换绑 |
| 所属客户未开通 | `403 customer_not_onboarded`（#60 后） |
| code 无效/过期/已用、`appid` 不符 | `401 wechat_code_invalid` |
| 手机号地区不支持 | `400 phone_unsupported` |
| 能力关闭 / 上游失败 | `503 wechat_unavailable` / `502 wechat_upstream_error` |

`phone_not_provisioned` 与 `account_disabled` 之所以可以明示：调用者已由微信证明持有该手机号，
披露的只是"你自己的号码的状态"，不含客户名或其他租户信息。并发绑定由 `UNIQUE` 约束兜底：撞约束后重读，
同一用户同一 openid 视为成功，否则 `409`。

### 4.5 `POST /api/users`（创建账号，**仅平台管理员**）

- 非平台管理员（含客户管理员、`customer_user`）：在**解析请求体之前**返回 `403 {"error":"not authorized"}`
  并写 `denied` 审计；伪造 `role`/`customerId` 得不到不同的错误。写入事务内再按库内当前角色复核（既有做法保留）。
- `role=platform_admin`：`username`+`password`，不得带 `phone`/`customerId`（现状不变）。
- `role=customer_admin`：`phone`+`password`+`customerId` 必填，不接受 `username`。
- `role=customer_user`：`phone`+`customerId` 必填；**出现 `password` 字段即 `400 password_not_allowed`**，不静默忽略。
- 错误：`400 invalid_phone`、`400 unknown customerId`（沿用）、`409 phone_exists`（不含客户信息）。
- 响应 `201`：`user` 对象（§3.1，平台管理员看到完整手机号）。

### 4.6 其余用户管理接口

| 接口 | 变化 |
| --- | --- |
| `GET /api/users` | 平台管理员不变；客户管理员仍可列**本客户员工**（只读，手机号脱敏；油罐分配已改为仅平台管理员，此入口不再用于分配）；`customer_user` 仍 403 |
| `PUT /api/users/:id`（停用/启用/重置密码） | **仅平台管理员**。客户管理员 → `403 not authorized`。对 `customer_user` 重置密码 → `409 no_password_for_role` |
| `PUT /api/users/:id/customer` | 已仅平台管理员，不变 |
| 删除账号 | 当前**没有**删除接口，本期不新增（停用即可）；不得为此引入 |

## 5. 配置与离线边界

```yaml
wechat:
  enabled: false           # 默认关闭
  appId: ""
  appSecret: ""            # 仅允许环境变量 WECHAT_APP_SECRET；不写进配置文件
  baseUrl: "https://api.weixin.qq.com"   # 测试注入用
  timeout: "5s"
```

- `enabled=false`（默认）：两个微信接口返回 `503 wechat_unavailable`；密码登录与服务启动**完全不受影响**。
- `enabled=true` 但 AppSecret 缺失：启动失败并给出明确错误（配置错误不应静默降级）。
- 服务启动时不访问微信；`access_token` 按需获取、内存缓存、到期前刷新，**不落库、不写日志**；AppSecret、
  `access_token`、`openid`、两类 code 不写日志、不进审计 `detail`。
- 公网不可达只影响微信接口（`502`），不阻塞其他路由。

## 6. 审计

`audit_log` 新增 action：`auth.wechat_bind`（allowed/denied，`detail` 只含 `userId`、`reason`）、
`user.create`（既有，`detail` 里 `username` 改记 `userId`+`role`，不记完整手机号）。登录本身沿用"不写审计"
（#51 的约定），不新增登录审计。

## 7. 客户端义务

- **小程序**：登录页先 `wx.login` → `/api/auth/wechat/login`；`wechat_not_bound` 才展示手机号授权按钮，
  `getPhoneNumber` 回调拿 `phoneCode` 后再次 `wx.login` 取**新**的 `loginCode` 调
  `/api/auth/wechat/phone-login`。登录类请求必须走"无会话"通道，**不得**被全局 401/403 处理当作令牌失效而
  清会话/跳转；以 `code` 分支提示，失败后可手动重试，不自动循环。
- **网页**：登录表单一个输入框按正则决定发 `phone` 还是 `username`；`customer_user` 即使拿到有效令牌
  （来自小程序）也不得进入 `/console`（`access.ts`）；员工表单无密码字段。
- 两端都要处理响应头 `X-Renewed-Token`（既有），并以 `GET /api/me`/登录响应的 `user.id` 作身份键。

## 8. 待产品/负责人确认（集中，未确认前一律按"建议"实现，不当作已确认需求）

| # | 问题 | 建议 | 决定人 |
| --- | --- | --- | --- |
| Q1 | 首版支持的手机号范围 | 仅大陆 11 位 | 产品 |
| Q2 | 一个手机号只对应一个账号、一个客户 | 是（全局唯一），不做多客户身份 | 产品 |
| Q3 | 员工换微信/换号怎么办 | 本期**无自助流程**；是否提供平台管理员"解除微信绑定"接口（审计、需二次确认）？不提供则只能 SQL 处理 | 产品 |
| Q4 | 已绑定微信可无限静默重新登录，`maxSessionAge` 对微信用户只是重新验证间隔 | 接受；否则需限制绑定后的总会话年龄并强制重新手机号授权 | 产品+安全 |
| Q5 | 客户管理员列员工（脱敏手机号） | 保留，仅本客户 `customer_user`；2026-10-14 起油罐分配归平台管理员，是否继续保留此列表待产品确认 | 产品 |
| Q6 | `sub` 改为用户 ID | 是；客户端改读 `/api/me` | 三端负责人 |
| Q7 | 员工姓名 | 本期不做、不阻塞创建 | 产品 |
| Q8 | **本批范围**：是否同时实施 #60（开通状态）与 #63（油罐授权） | 见 §9 | 你 |

## 9. 与 #60、#63 的关系（范围风险）

- **#60**：本契约在登录响应里预留 `403 customer_not_onboarded`，但客户开通状态属于 #60 的数据模型与接口。
  在 #60 落地前，"客户已开通才可登录"的验收**无法**成立；#59 的"创建后可直接登录"在 #60 前是实际行为。
- **#63**：员工能看到什么由 #63 决定。#63 落地前，`customer_user` 的设备访问仍是旧的 `user_devices`
  设备级绑定（且旧 `POST /api/devices/bind` 仍可自行扩权，#63 要求关闭）。因此 #59 单独交付后，
  员工"仅看分配油罐"的验收也**无法**成立。
- 因此 #59 的验收条目中凡涉及"开通"和"仅见分配油罐"的，要么随 #60/#63 一起交付，要么明确挂账。
  不要在 #59 的 PR 里偷偷实现它们的一部分。

## 10. 实施拆分（确认后）

1. cloud：迁移 + sqlc + 创建权限收紧 + `/api/me` + 密码登录规则 + 令牌语义（PR-1，不依赖微信）。
2. cloud：微信配置、客户端适配器、两个微信接口（PR-2，用可注入的假微信服务器测试；**真实联调另算**）。
3. admin：登录表单、`access.ts`、员工/管理员创建表单、`customer_admin` 去除员工管理入口。
4. miniapp：登录页重做、身份键迁移到 `id`、错误分支。
5. 联合验收：必须用真实微信小程序与真实后端，模拟不算通过。
