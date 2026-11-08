# 业务错误码契约（#78、#79）

客户端按 `code` 分支，再按 HTTP 状态兜底；不解析 `error` 文案。以下仅为**本批已落地**的码，状态码与既有行为不变，
全部是向后兼容的新增 `code` 字段；`error` 文案与状态码都没改。码名是云端的提案，三端确认前客户端可先按本表映射，改名需同步。

| code | HTTP | 场景 | 说明 |
| --- | --- | --- | --- |
| `session_invalid` | 401 | 令牌缺失/过期/被篡改 | `error` 固定为「登录失效，请重新登录」。受保护 HTTP 路由与 `/ws/alarms` 握手一致。`invalid_credentials`、`wechat_code_invalid`、`wechat_not_bound` 等登录类 401 不并入。`403 not authorized`（账号停用/删除）不变。 |
| `telemetry_not_reported` | 404 | `GET /devices/:device/telemetry/latest` | 已授权、设备已在 ThingsBoard，但从未上报。 |
| `telemetry_expired` | 404 | `GET /devices/:device/telemetry/latest` | 设备上报过，但距今超过 ThingsBoard 保留期，快照已被清理（cloud#28）。体里带 `lastReportedAt`（最后一次上报时间）。**不返回旧值**：遥测不复制进业务库，按 key 拼最新值会让已不存在的罐复活。状态码仍是 404，只看状态码的客户端行为不变，只是可以多说一句「已超过保留期」。 |
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
| `device_required` | 400 | 列表接口缺 `device` 参数。例外：员工（`customer_user`）调 `GET /api/audit` 不带 `device` 为 **403**（兼容旧行为，体不是 `not authorized`，不应登出） |
| `invalid_tank` | 400 | 报警/出入库列表、遥测历史（`telemetry/history`，原为 `invalid_request`）的 `tank` 不是 1–64 的整数（[records-filter-contract](records-filter-contract.md)，#84）；罐授权写入里的未上报罐号沿用同码 |
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

## 服务异常、请求体与命令（#79 第三批）

同样只加 `code`，状态码不变。**例外：500 的 `error` 文案不再透传底层错误**（SQL、约束名、ThingsBoard 响应体），
统一为 `internal server error`，原文只进服务端日志；此前没有客户端依赖 500 的文案。

| code | HTTP | 场景 |
| --- | --- | --- |
| `internal_error` | 500 | 未预期的服务端错误，所有接口共用；客户端显示通用兜底「操作失败，请稍后重试」 |
| `telemetry_unparsable` | 500 | `telemetry/latest`：设备最新遥测不符合协议，读不出来（设备侧问题，不是"未上报"） |
| `invalid_request` | 400 | 请求体/参数绑定失败（缺字段、类型错、JSON 坏）。`error` 仍是绑定器原文，指出字段，供开发排查，**不给最终用户看** |
| `device_not_authorized` | 403 | 设备级读取被拒：不存在、跨客户、未授权**同一个码同一个体**（约束 6、9）。`/ws/alarms` 握手相同 |
| `command_not_authorized` | 403 | 发命令被拒（设备不可访问或该角色不能发该方法），同样不区分原因 |
| `platform_admin_required` | 403 | 设备登记、签发配对密钥只限平台管理员。体不是裸 `not authorized`，客户端不应据此登出 |
| `unknown_method` | 400 | 命令方法名不存在（仅对有权访问设备者返回） |
| `reason_required` | 400 | 该方法必须填写原因 |
| `physical_commands_disabled` | 409 | 泵/阀/重启等物理命令在本部署禁用（约束 1） |
| `command_delivery_unconfirmed` | 503 | ThingsBoard 在交接命令时出错。命令**可能已送达**设备（约束 2），客户端提示「执行结果尚未确认，请先查看操作记录」，**不得**提示失败或引导直接重试 |
| `cannot_disable_self` | 400 | 平台管理员不能停用自己 |

设备 RPC 方法列表、`tanks` 列表读 ThingsBoard 失败时，与遥测读取同用 `device_not_provisioned` / `device_platform_unavailable`。

## 待三端确认（#79 未落地部分）

以下需要小程序（miniapp#7）与网页（admin#17）负责人确认后再改，云端不单方面动：

1. **裸 `403 {"error":"not authorized"}` 已追加 `code`（体与状态码不变）**：`account_unavailable`（账号已删除/停用，
   应结束会话）与 `role_not_allowed`（账号有效、角色不允许，**不应**结束会话）。事务内复检失败（"authority changed"）
   时按调用者当前账号行判定：停用/删除 → `account_unavailable`，否则 `role_not_allowed`；读不到行时回落为
   `account_unavailable`（与旧行为一致）。小程序与网页现按 `error === "not authorized"` 判定登出，不受影响；
   **待客户端确认后**改为仅在 `account_unavailable`（或无 `code` 的旧体）时登出，`role_not_allowed` 显示
   「没有权限执行此操作」（miniapp#7、admin#17）。
2. **记录"不完整"信封**（`parsed`/`unparsable`/`legacySkipped`、`STOCK_EVENT_FORMAT_UNSUPPORTED`）保持现状，
   只需客户端按字段显示"列表可能不完整"，不需要云端新码。
3. **场景 × 文案矩阵**（操作场景—HTTP—code—默认文案—下一步—会话行为）以本表的 code 为准，由客户端维护本地化文案；
   码名改动需三端同步。

## 账号与登录（#77）

| code | HTTP | 场景 |
| --- | --- | --- |
| `login_rate_limited` | 429 | 登录失败次数过多；带 `Retry-After`（秒）。见 account 契约 §11.4 |
| `invalid_username` | 400 | 创建用户名账号时用户名格式不合规 |
| `invalid_password` | 400 | 密码不是 8–72 字节（用户名账号、手机号员工的创建，以及重置密码） |

`username_taken` 沿用，现也适用于用户名账号。`password_not_allowed`、`no_password_for_role` 不再返回（员工可以有密码）。
没有自助改密，也就没有 `wrong_password`/`password_unchanged`。
