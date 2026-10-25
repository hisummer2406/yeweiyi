# 业务错误码契约（#78、#79）

客户端按 `code` 分支，再按 HTTP 状态兜底；不解析 `error` 文案。以下仅为**本批已落地**的码，状态码与既有行为不变，
全部是向后兼容的新增 `code` 字段；`error` 文案与状态码都没改。码名是云端的提案，三端确认前客户端可先按本表映射，改名需同步。

| code | HTTP | 场景 | 说明 |
| --- | --- | --- | --- |
| `session_invalid` | 401 | 令牌缺失/过期/被篡改 | `error` 固定为「登录失效，请重新登录」。受保护 HTTP 路由与 `/ws/alarms` 握手一致。`invalid_credentials`、`wechat_code_invalid`、`wechat_not_bound` 等登录类 401 不并入。`403 not authorized`（账号停用/删除）不变。 |
| `telemetry_not_reported` | 404 | `GET /devices/:device/telemetry/latest` | 已授权、设备已在 ThingsBoard，但从未上报。 |
| `device_not_provisioned` | 404 | 遥测/历史读取 | 业务库有该设备，ThingsBoard 里没有（接入信息未就绪）。 |
| `device_platform_unavailable` | 503 | 遥测/历史读取 | ThingsBoard 未配置或不可达。 |

后三个码只在通过授权校验之后返回，不泄露设备存在性；无权限仍是统一的 403。
`/ws/alarms` 已建立连接后的 `token expired` 关闭帧不变。

## 参数校验与冲突（#79 第二批）

均为 400/409/404/503，只加 `code`。`invalid_request` 沿用旧码，作通用兜底。

| code | HTTP | 场景 |
| --- | --- | --- |
| `invalid_name` | 400 | 客户/油站名称（1–100 字符）或地址超长 |
| `invalid_password` | 400 | 密码长度不合规 |
| `invalid_device_name` | 400 | 设备名为空或含 `/ ? # \` |
| `invalid_time_format` | 400 | `beginTime`/`endTime` 不是 RFC3339 |
| `invalid_time_range` | 400 | 历史查询 `beginTime` 不早于 `endTime` |
| `invalid_paging` | 400 | `limit`/`offset`/`sort` 非法 |
| `device_required` | 400 | 列表接口缺 `device` 参数 |
| `invalid_tank` | 400 | 报警/出入库列表的 `tank` 不是 1–64 的整数（[records-filter-contract](records-filter-contract.md)，#84）；罐授权写入里的未上报罐号沿用同码 |
| `invalid_direction` | 400 | 出入库列表的 `direction` 不是 `IN`/`OUT`（#84） |
| `invalid_station` | 400 | `stationId` 缺失/类型错/不属于设备所在客户 |
| `unknown_customer` | 400 | `customerId` 不存在（别的客户的也一样，见 #54） |
| `platform_admin_has_no_customer` | 400 | 平台管理员不能迁移客户 |
| `customer_not_found` / `user_not_found` / `device_not_found` | 404 | 对象不存在或对调用者不可见 |
| `customer_name_taken` / `username_taken` / `station_name_taken` | 409 | 重名 |
| `default_customer_protected` | 409 | 默认客户不能改名/删除 |
| `customer_not_empty` / `station_not_empty` | 409 | 仍有用户/设备/油站/历史，不能删除 |
| `device_customer_mismatch` | 409 | 设备已属于另一客户 |
| `station_assignment_retry` | 409 | 分配期间设备或油站变化，重试 |
| `control_unavailable` | 503 | 未启用远程控制，命令**未提交** |
| `audit_unavailable` | 503 | 审计不可用，命令**未发送**，可安全重试 |

既有的 `station_occupied`、`station_device_changed`、`device_without_customer` 等码不变。
**未动**：所有 `403 {"error":"not authorized"}`（账号失效 vs 角色不足的拆分需先确认兼容方案，客户端据此回登录页）、
500 的原文错误体、出入库/报警"不完整"信封、命令结果未确认的状态语义。

## 账号与登录（#77）

| code | HTTP | 场景 |
| --- | --- | --- |
| `login_rate_limited` | 429 | 登录失败次数过多；带 `Retry-After`（秒）。见 account 契约 §11.4 |
| `invalid_username` | 400 | 创建用户名账号时用户名格式不合规 |
| `invalid_password` | 400 | 密码不是 8–72 字节（用户名账号、手机号员工的创建，以及重置密码） |

`username_taken` 沿用，现也适用于用户名账号。`password_not_allowed`、`no_password_for_role` 不再返回（员工可以有密码）。
没有自助改密，也就没有 `wrong_password`/`password_unchanged`。
