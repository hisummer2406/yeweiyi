# 客户开通状态契约（cloud#60，配套 admin#14、miniapp#5）

状态：云端已实现（迁移 000025，`internal/api/onboarding.go`）。契约以本文件为准。

## 1. 状态

`customers.onboarding_status`：`configuring`（配置中）| `active`（已开通）。

- 新建客户（任何入口）= `configuring`。
- 升级迁移时已存在的客户全部置 `active`（含 `default`），升级不锁任何现有客户。
- 单向：`configuring → active`。新增/删除油站设备、停用账号都不会退回 `configuring`。
- 状态属于客户，与 `users.disabled_at` 无关；开通不会启用已停用账号。

## 2. 接口（仅平台管理员；客户管理员/员工一律 `403 {"error":"not authorized"}`）

客户对象（`GET /api/customers`、`POST /api/customers`、`PUT /api/customers/:id` 响应）新增：

```json
{ "id": 1, "name": "x", "onboardingStatus": "configuring", "onboardedAt": null }
```

### GET /api/customers/:id/onboarding

```json
{
  "customerId": 7,
  "status": "configuring",
  "complete": false,
  "missing": ["customer_admin", "station", "device_in_station"],
  "counts": { "customerAdmins": 0, "stations": 0, "devicesInStation": 0 }
}
```

`missing` 取值（`status=active` 时恒为 `[]`）：

| code | 含义 |
|---|---|
| `customer_admin` | 无属于该客户、未停用的客户管理员（手机号账号） |
| `station` | 无该客户的油站 |
| `device_in_station` | 无归属该客户且已分配到该客户油站的设备 |

客户不存在 `404 {"code":"customer_not_found"}`。

### POST /api/customers/:id/onboarding/complete

无请求体。服务端在同一事务内锁客户行后重新检查。

- 成功 `200`：同 GET 的形状，`status=active`，`complete=true`。
- 已开通：幂等 `200`，同上，不重复写审计。
- 条件不全 `409 {"code":"onboarding_incomplete","error":"...","missing":[...]}`，客户保持配置中。
- 客户不存在 `404 customer_not_found`。

审计：`customer.onboard`，`allowed` / `denied`（含 missing），不含任何凭证。

## 3. 登录与令牌

配置中客户下的**所有**账号（客户管理员、员工）不能登录或续期；平台管理员不受限。

- 错误：`403 {"error":"customer not onboarded","code":"customer_not_onboarded"}`。
- 这是业务提示，**不是**"账号失效"：客户端不得当作 `not authorized` 清会话回登录页之外的处理，
  应展示"所在客户尚未完成开通，请联系平台管理员"，保留输入，可重试。
- 顺序（不泄露客户信息）：先验证身份，再检查状态。
  - `POST /api/login`：密码正确且账号未停用之后才可能返回该错误；密码错/不存在/停用仍同为 `401 invalid_credentials`。
  - `POST /api/auth/wechat/login`：已绑定且未停用之后；`/phone-login`：手机号经微信验证、账号存在且未停用之后，
    **不写绑定**。
- 旧令牌：受保护接口按当前库状态判断，配置中 → 同样 `403 customer_not_onboarded`，不下发 `X-Renewed-Token`。
  `/ws/alarms` 在握手及逐帧复核时同样以该状态拒绝/关闭。
- 开通完成后旧令牌（未过期）立即恢复可用。

## 4. 客户端职责

- admin：向导分步调用现有 CRUD 创建客户/账号/油站/设备并分配；刷新或重进用 `GET .../onboarding` 恢复进度；
  最后调用 complete；`409 onboarding_incomplete` 按 `missing` 引导到对应步骤。跨客户设备不得隐式迁移
  （现有接口已拒绝），油站已被占用 `409 station_occupied`。
- miniapp：登录/微信登录/会话内收到 `customer_not_onboarded` 时展示上述提示，不清除手机号输入，不当作
  密码错误，不无限重试。
