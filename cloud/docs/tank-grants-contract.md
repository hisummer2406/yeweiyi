# 员工按油罐查看授权、扫码只定位：三端契约（v1.0，待相关负责人确认）

状态：**云端已实现并通过真实数据库/ThingsBoard 测试；admin、小程序尚未按本契约联调。** 主需求：
[cloud#63](https://github.com/hisummer2406/yeweiyi-cloud/issues/63)；配套：
[admin#17](https://github.com/hisummer2406/yeweiyi-admin/issues/17)、
[miniapp#7](https://github.com/hisummer2406/yeweiyi-miniapp/issues/7)。关联：[#61 换机契约](station-device-replacement-contract.md)。
标注：**【需求】** 已确认需求；**【选择】** 云端实现选择（可被评审改变）；**【待确认】** 需负责人决定；**【现状】** 已核对代码。

## 1. 模型

- 【需求】授权粒度是 `(用户, device_name, 罐号)`，不是油站、不是整机。员工（`customer_user`）只能查看被授予的罐；未授予默认无权。
  客户管理员看本客户全部设备与罐；平台管理员保留现有范围。
- 【需求】授权读写者**仅平台管理员**（产品决定 2026-10-14，更正此前“客户管理员”）。客户管理员**没有**此权限：`GET`/`PUT` 一律 `403 not authorized`（按数据库当前角色，写 `denied` 审计），不能通过授权操作创建或修改账号。
- 【选择】存储 `user_tank_grants(user_id, device_name, tank 1..64, granted_by, created_at)`，主键三元组，外键级联；
  迁移 `000024_tank_grants`。设备归属（客户/油站）与罐授权相互独立。
- 【选择】**旧设备级绑定 `user_devices` 不转换**（#63："不能无提示把设备绑定转换成所有罐权限"）：升级后员工在被明确分配
  之前什么都看不到。该表保留为只读历史，不再被任何判定引用。
- 【选择】罐号来源：设备**已上报**的最新快照（ThingsBoard，`GET /api/devices/:device/tanks`）。云端不接受未见过的罐号
  （`400 invalid_tank`）；设备从未上报时新增授权返回 `409 tanks_unknown`（撤销不受限）。罐从快照中消失后，已有授权保留但不产生
  数据，可撤销。
- 【选择】换机（#61）用新 `device_name`，授权键含设备名，因此新设备天然没有授权；管理员需在新设备上报罐后重新分配。

## 2. 可见性规则（所有读路径）

| 路径 | 员工 | 管理员 |
| --- | --- | --- |
| `GET /api/devices` | 只含至少有一个被授权罐的设备；每行带 `granted_tanks:[…]`（管理员行此字段为 `null`） | 范围内全部 |
| `GET /api/stations` | 只含这类设备所在的油站 | 同 #43 |
| `GET /api/devices/:device/tanks` | 只含被授予的罐 | 设备当前上报的全部罐 |
| `…/telemetry/latest` | 快照里只留被授予的罐；设备级字段（`ts`、`snapshotSeq`）保留 | 全部 |
| `…/telemetry/history?tank=n` | `n` 未授予 **或不存在** → `403 {"code":"tank_not_authorized"}`（二者同一响应，不泄露罐数） | 全部 |
| `/api/alarms`、`/api/oil-in-out` | SQL 内按罐过滤；分页、截断、`limit` 都基于过滤后的行 | 全部（含无罐号的整机记录） |
| `/ws/alarms` | 每帧按 `(设备, 罐)` 复核；未授予的罐与整机帧（tank 0）**不发送**；撤销后下一帧即停；停用/换客户由既有周期复核关闭连接 | 全部 |
| 命令（发起、历史）、`/api/audit` | **不可见**：发起 `403`；历史与审计列表为 `200` 的**空列表**（不是 403/404：员工对设备可定位，只是没有可见行） | 既有规则 |

- 【选择】**整机级记录**（payload 无罐号、`tank=0`）、整机事件/属性：只对管理员可见，不向有一个罐权限的员工广播（#63 要求评审的规则）。
- 【选择】出入库无法解析的原文：员工仍会收到"窗口内有 N 条无法解析"的 `502 STOCK_EVENT_FORMAT_UNSUPPORTED`（否则会把故障
  读成成功的空列表），但 `sample` 原文留空——它可能描述别的罐。
- 【选择】**员工只读**：不能发任何命令（含 `alarm.mute` 这类控制级方法）。理由：命令作用于整机，一个罐的查看权不能让人为
  其他罐消音或重启。这收窄了旧行为（旧模型下员工可发控制级命令），属权限收紧而非放宽；若产品要"每罐控制权"，另立需求。
- 【现状】`user_may_access_device` 的含义变为"可定位"（有 ≥1 个授权罐），**不等于可读整机**；`user_may_access_tank` 是罐级规则。
  所有判定读数据库当前状态，不读令牌。

## 3. 扫码只定位

- `POST /api/devices/resolve`，请求 `{"code": "<扫码原文>"}`。**不写任何东西**（无绑定、无授权、不改客户/油站），重复调用无副作用。
  - 候选：原文精确匹配优先，再按 `pairing.codePrefixes` 顺序拼前缀；只在调用者客户的设备内查找；原文只接受 `[0-9A-Za-z_-]{1,64}`，
    保留前导零，不硬编码前缀/长度。
  - `200 {"deviceName": "<实际设备名>"}`：客户管理员——本客户任意设备；员工——至少有一个授权罐的设备。
  - 其余一律 `404 {"error":"device not found","code":"device_not_found"}`：不存在、别的客户的、本客户但员工无授权、账号停用、
    平台管理员、畸形原文——**不可区分**，不会是裸 `403 not authorized`（客户端据此判账号失效，不得误用）。
- `POST /api/devices/bind` **已停用**：恒 `410 {"code":"bind_removed"}`，不论角色与请求体（含配对密钥）都不写库。
  `POST /api/devices/unbind` 保留为无副作用的 `200`（员工不能改授权）。
- 【待确认 B1】旧版小程序（仍调 bind）会收到 410。新小程序应改用 resolve。是否需要对旧客户端给过渡期？云端默认不提供
  （保留 bind 的任何写入行为都会让"扫码授权"留下旁路）。

## 4. 授权管理接口

均需管理员；角色/账号失效为 `403 not authorized`，对象级拒绝为 `404`，不会误作账号失效。

### `GET /api/devices/:device/tanks`
`200 {"deviceName", "reported": bool, "tanks": [{"index": 1}, …]}`。`reported:false` = 设备尚未上报（`tanks` 为空）。
设备不可定位 → `403 not authorized for this device`；ThingsBoard 不可用 `503`。

### `GET /api/users/:id/tank-grants`
`200 {"userId", "grants": [{"deviceName", "tanks": [1,2]}]}`。仅平台管理员；目标必须是员工，否则：
不存在 `404 code=user_not_found`；目标是管理员 `409 code=grant_not_applicable`。

### `PUT /api/users/:id/tank-grants/:device`
请求 `{"tanks": [1,2]}`：把该员工在该设备上的授权**整组替换**为给定集合；`[]` = 撤销该设备全部授权。`tanks` 键必须存在。

- 幂等：相同集合重复提交 `200 changed:false`，不写审计；并发提交收敛到其中一组（目标用户行加锁串行化），不会 500。
- `200 {"userId","deviceName","tanks":[…],"changed":bool}`。
- `400 invalid_request`（缺 `tanks`/非列表）、`400 invalid_tank`（不在 1..64 或设备未上报该罐）。
- `409 tanks_unknown`（设备从未上报，不能新增）、`409 device_customer_mismatch`（设备与员工不同客户）、
  `409 grant_not_applicable`（目标不是员工）。
- `404 user_not_found` / `404 device_not_found`（调用者是平台管理员，不存在即不存在）；非平台管理员 `403 not authorized`。
- 写入在同一事务内复核调用者仍是平台管理员、员工与设备同属一客户；INSERT 语句自己再带同客户与角色条件（判定与写入同一条语句）。
- 审计 `tank.grants.set`：`{userId, before, after}`，归属设备所属客户；拒绝写 `denied`。
- 员工换客户、设备换客户：授权在同一事务内清除（`bindingsCleared` 计数含授权所涉设备/用户数）。

## 5. 撤销与实时连接

- 撤销、停用、换客户对**已发出的令牌**立即生效（授权每次读库，不读 JWT）。
- WebSocket：撤销某罐后，下一帧起该罐的帧不再发送（逐帧查询）；撤销设备下全部罐、停用、换客户由既有周期复核关闭连接
  （`access revoked`）。令牌到期关闭沿用既有行为。

## 6. 与 #61 换机的关系

- A→B 后：员工在 A 上的授权**不删除、不复制**；B 没有任何授权，员工对 B 为 `403 not authorized for this device`，且 B 不出现在其设备列表。
- B 尚未上报罐时不能分配（`409 tanks_unknown`）；B 上报后，管理员用 `PUT` 重新分配，员工才可见。无自动继承、无自动提示。
- 员工看到的"油站当前设备"：油站出现在员工的 `/api/stations` 当且仅当该站设备上有其授权罐；换机后新设备未授权前，员工看不到该站。
  客户端据此展示"未分配/联系管理员"，**不得**回退显示旧设备（旧设备已不在该站）。

## 7. 各端工作

| 端 | 需要做 | 说明 |
| --- | --- | --- |
| 后台 admin | 员工详情页：按设备列出可分配罐（`/devices/:device/tanks`）与已授权（`GET …/tank-grants`），整组保存（`PUT`）；处理 `409 tanks_unknown/invalid_tank` | 仅平台管理员可见该页面与调用；客户管理员无授权入口，不提供创建/改员工账号入口（同归平台管理员，#59） |
| 小程序 | 扫码改调 `POST /api/devices/resolve`，再进设备详情；不再调 `bind`；详情按 `granted_tanks`/服务端过滤结果展示；无授权设备提示联系平台管理员；`410 bind_removed` 与 `404 device_not_found` 都不是账号失效 | 服务端是权限边界，客户端不得靠隐藏 |
| 安卓 | 无变化；不新增线协议字段 | |

## 8. 待确认项

| # | 问题 | 云端默认 |
| --- | --- | --- |
| B1 | 旧版小程序调 `bind` 得到 `410`，是否需要过渡期 | 不提供（任何保留的写入行为都是旁路） |
| P1 | 员工完全只读（含控制级命令）是否符合产品预期 | 是（收紧，非放宽） |
| P2 | 旧 `user_devices` 绑定不转换，升级后员工需重新分配 | 是（#63 明确要求不得无提示转换） |

## 9. 版本记录

- v1.1（2026-10-14）：授权读写改为仅平台管理员（产品决定），客户管理员 `GET`/`PUT` tank-grants 一律 403；此前文本写作“客户管理员”，已更正。
- v1.0（2026-10-14）：初稿，云端已实现；基于 `08c0524` 之上的未提交改动。§2 命令历史/审计措辞澄清为“发起 403、列表 200 空”（小程序提问），接口不变。

## 设备小程序码（#63 补充）

- `GET /api/devices/:device/mini-code`：**仅平台管理员**（其余角色 `403 not authorized`，写 `denied` 审计）。返回 PNG/JPEG
  二进制（`Content-Type` 由内容判定，`Cache-Control: no-store`），需 `Authorization` 头。设备不存在 `404 code=device_not_found`；
  `wechat.enabled=false` → `503 wechat_unavailable`；微信失败 `502`。
- 码内容：`scene` = 设备名原文（≤32 字符），`page` = `wechat.codePage`（默认 `pages/device-detail/device-detail`），
  `env_version` = `wechat.codeEnvVersion`。生成不写库、不授予任何权限；扫码后由小程序走 `POST /api/devices/resolve`。
- 只用假微信服务器测过；真实 getwxacodeunlimit（含 check_path、版本、额度）未验证。
