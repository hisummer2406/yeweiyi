# 油站与大屏一对一、多罐监控、换机重新采集：三端契约（v1.3）

状态：**v1.3（v1.2 + #63 已落地：§6、§14 更新；#61 的接口与错误行为无变化），云端已实现并通过真实数据库测试（见 §12）；三端联调与真机未验证。** v1.0 于 2026-10-13 经用户按建议确认（C1/Q2/Q3/Q4/R1 取建议值，见 §10）。 主需求：[cloud#61](https://github.com/hisummer2406/yeweiyi-cloud/issues/61)；
配套：[admin#15](https://github.com/hisummer2406/yeweiyi-admin/issues/15)、
[miniapp#6](https://github.com/hisummer2406/yeweiyi-miniapp/issues/6)、
[zhongchengyeweiyi#45](https://github.com/hisummer2406/zhongchengyeweiyi/issues/45)。
关联但不在本契约实现范围：[cloud#63](https://github.com/hisummer2406/yeweiyi-cloud/issues/63)（按罐授权）、
[cloud#60](https://github.com/hisummer2406/yeweiyi-cloud/issues/60)（客户开通）、
[cloud#62](https://github.com/hisummer2406/yeweiyi-cloud/issues/62)（手输编号核查）。

本文按 [区分] 标注每条的性质：**【需求】** 已确认需求；**【选择】** 云端实现选择（可被评审改变）；
**【待确认】** 需负责人决定；**【现状】** 已核对的当前代码行为（基准 `08c0524`）。

## 1. 身份与关系

- 【需求】油站 ↔ 大屏一对一：一个油站同时至多一台设备，一台设备同时至多属于一个油站。
- 【需求】TB 设备实体 = 一台大屏；出厂编码 = TB 设备名称 = 云端 `devices.device_name`。编码一律按字符串处理，
  保留前导零，不硬编码前缀/长度。
- 【需求】一台大屏有多个罐，罐以 `device_name + tank` 定位（协议 `tank{n}`）。同罐号在不同设备下是不同数据对象。
- 【需求】换机 = 使用新设备码，重新采集；不迁移/合并/接续旧设备历史，不改名复用旧设备，不复制旧设备授权，不自动删除旧设备记录。
- 【需求】油站只存在业务库，不映射 ThingsBoard，不新增线协议字段。
- 【现状】`devices.station_id` 可空，复合外键 `(customer_id, station_id)` 保证同客户，`CHECK` 保证有客户才可有油站；
  **没有**同站唯一约束；设备 → 油站是 `devices` 上的一列，没有"油站 → 设备"的反向存储。

## 2. 空油站

- 【选择】油站可暂无设备（建档、待安装、旧设备已解除、等待新设备）。理由：#60 的最小开通条件只要求"至少一台设备分配到该客户的油站"，
  不要求每个油站都有设备；禁止空油站会改变 #60 与 #43 的生命周期。空油站不是"一站多设备"。
- 【现状】`customer_admin` 的 `GET /api/stations` 已包含无设备的油站；`customer_user` 只见其已绑定设备所在油站。
- 【已确认 Q2】不禁止空油站。

## 3. 接口契约

响应错误沿用 `{"error": "...", "code": "..."}`（`code` 为本契约新增，客户端按 `code` 分支，`error` 文案不作为判断依据）。
`403 {"error":"not authorized"}` 仍只代表角色/账号失效；对象级拒绝用 404/409，见 #54。

### 3.1 普通分配 / 解除：复用 `PUT /api/devices/:device/station`（【选择】不新增）

请求 `{"stationId": 12}` 或 `{"stationId": null}`（解除）。行为变更：

| 情形 | 响应 |
| --- | --- |
| 油站空闲，设备此前未分组或在别的油站 | `200 {"deviceName","stationId","changed":true}` |
| 同设备同油站重复提交 | `200 ... "changed":false`（幂等，不写审计） |
| 油站已被**另一台**设备占用 | `409 {"code":"station_occupied","error":"...","occupiedBy":"<deviceName>"}`，不修改任何行 |
| 设备已在另一油站（移动）| 允许，沿用现状（等价于先解除再分配，同一条 UPDATE）；若目标油站被占用同上 409 |
| 油站不存在 / 属于别的客户 | 沿用：`400 stationId does not name a station of the device's customer`（平台管理员）；客户管理员视角不泄露 |
| 设备不存在 / 别的客户的设备 | 沿用：`404 device not found`，v1.1 起带 `code:"device_not_found"`（对客户管理员两者相同） |
| 设备无客户 | 沿用：`409`，补 `code:"device_without_customer"` |
| `customer_user` / 账号失效 | 沿用：`403 not authorized`，写 `denied` 审计 |
| 并发：两个请求同时分配不同设备到同一空油站 | 恰好一个 `200`，另一个 `409 station_occupied`（由唯一索引裁决，见 §4）；23505 映射为 409，不是 500 |

- `occupiedBy` 只可能是同客户设备（油站必属同一客户），不构成跨客户泄露。【选择】
- 普通分配**不会**顶替旧设备：占用即 409，必须先解除或走换机。

### 3.2 换机：新增 `POST /api/stations/:id/replace-device`（【选择】新增）

不复用 3.1 的理由：换机需要"解除旧、关联新"在一个事务里完成并带乐观并发保护；用两次 PUT 之间存在
"油站空档"，且旧页面可能把别的设备顶掉。issue 明确"一步替换 API 是实现选择"，故新增。**只有云端新增这一个接口。**

请求：

```json
{ "deviceName": "<新设备码，原样字符串>", "expectedCurrentDeviceName": "<页面看到的旧设备码，或 null 表示页面看到的是空油站>" }
```

`expectedCurrentDeviceName` **必填**（可为 `null`）：缺失是 `400 code=invalid_request`，防止过期页面盲替。

事务内顺序（固定加锁顺序，避免死锁）：锁操作者行 → 锁油站行 → 按 `device_name` 升序锁涉及的设备行 → 复核。

| 情形 | 响应 |
| --- | --- |
| 当前设备 = expected，新设备合法 | `200 {"stationId","deviceName","previousDeviceName","changed":true}`；旧设备 `station_id=NULL`（仍属客户、保留记录与绑定），新设备 `station_id=油站` |
| 新设备已是该油站当前设备 | `200 ... "changed":false`（幂等重试） |
| 当前设备 ≠ expected（页面过期/他人已换） | `409 {"code":"station_device_changed","currentDeviceName":"..."}` |
| 新设备已在**另一个**油站 | `409 {"code":"device_in_other_station"}`（不静默搬走；需先显式解除） |
| 新设备未登记（`devices` 无此行） | `404 code=device_not_found`（不隐式创建；登记走 `POST /api/devices`，#48） |
| 新设备属于别的客户 / 无客户 | 客户管理员：`404 code=device_not_found`；平台管理员：别的客户 `409 {"code":"device_customer_mismatch"}`，无客户 `409 device_without_customer` |
| 新设备 = 旧设备 | `400 code=same_device`（不是换机） |
| 油站不存在 / 别的客户的油站（客户管理员） | `404 station not found`，v1.1 起带 `code:"station_not_found"`（同 #54，两者完全相同） |
| 角色/账号失效 | `403 not authorized` + `denied` 审计 |

- 授权先于存在性检查（invariant 6）；写入一条审计 `station.replace_device`，detail 含 `from`/`to`/`stationId`。
- 失败不留半成品：任一步失败整体回滚。
- 【已确认 Q3】`customer_admin` 可换机，按现有油站管理规则：平台管理员不限，客户管理员限本客户。

### 3.3 查询

- 【选择】不新增"按油站读数据"接口。`GET /api/devices` 每行已带 `station_id`；客户端用它解析"油站当前设备"，再用
  `device_name` 调用既有 `/api/devices/:device/...`。**不得**按 `station_id` 拼接多设备记录。
- 【选择】`GET /api/stations` 不新增 `deviceName` 字段（避免 `customer_user` 范围外泄露与重复事实源）。若后台需要"油站 → 当前设备"
  一次取到，【已确认 Q4】本版不加；admin 负责人如确需，另行提出，再评审（会修订契约）。
- 迁移 000023 之后库内不可能再出现两行带同一 `station_id`。

## 4. 数据库

【选择】迁移 `000023_station_one_device`（成对 up/down，注释中文）：

1. 先决检查（`DO` 块）：若存在 `station_id` 下超过一台设备，**迁移失败**并在异常信息里列出油站 id 与设备名；
   **不自动挑选、不自动清空、不删除**。
2. `CREATE UNIQUE INDEX devices_station_id_key ON devices (station_id) WHERE station_id IS NOT NULL`；
   删除被它覆盖的普通索引 `devices_station_id_idx`。
3. down：删除唯一索引，恢复普通索引。

开发库当前核对：75 台设备，0 台挂油站，无冲突。**目标环境尚未核对**，上线前须按 `deploy/README.md` 补充的预检 SQL 执行并人工评审。

## 5. 数据隔离（换机后）

【现状，已核对】`telemetry_hourly`、`stock_events`、`alarm_events`、`record_rejects`、命令表、审计均以 `device_name`
为键，没有任何查询以 `station_id` 取数据。因此换机本身不需要改写任何记录，隔离是结构性的：

| 数据 | 换机后规则 |
| --- | --- |
| 最新值 / 历史（TB 读穿） | 只按 URL 里的 `:device`；B 无数据 → 空/无数据，**不回退 A**。注意既有缺陷 #28（停报超保留期误报 404）会让"无数据"表现为 404，客户端须同样按空状态处理 |
| 小时汇总 | 键 `(device_name, tank, field, hour_start)`，A 的行永远是 A |
| 报警 / 出入库 | 键含 `device_name`；`eventId` 去重也以 `(device_name, eventId)` 计，A 与 B 同 eventId 不冲突 |
| A 的迟到上报 / 回补 | 仍以 A 的名字写入 A；不会出现在 B 的任何查询 |
| 实时推送 `/ws/alarms` | 按设备授权过滤；A 报警只推给仍可访问 A 的人，不带入 B |
| 旧设备记录 | 不删除；A 仍属该客户，对客户管理员/平台管理员可见（作为未分组设备） |

验收测试将用真实库证明上表（见 §9），而不只是文档声明。

## 6. 授权与扫码（#63 已落地，契约见 [tank-grants-contract.md](tank-grants-contract.md)）

- 【需求】设备码只是标识，不是授权凭证；换机**不复制**授权，B 没有任何旧授权。
- 【需求】扫码只导航；员工只能看已分配的 `device_name+罐号`；分配罐与创建/修改员工账号都只归平台管理员，客户管理员无此权限。
- 【现状】授权为罐粒度（`user_tank_grants`）：`POST /api/devices/resolve` 只定位；`POST /api/devices/bind` 已停用（`410 bind_removed`）；
  员工只读。换机后：员工对 B 为 `403 not authorized for this device`，B 不在其设备列表，油站在员工的油站列表里也不再出现
  （旧设备已不在该站、新设备无授权）；管理员在 B 上报罐后用 `PUT /api/users/:id/tank-grants/:device` 重新分配。
- 历史：v1.0–v1.2 期间 `bind` 仍会创建设备级授权（过渡行为，C1），已随 #63 结束。

## 7. 旧短码标签兼容

- 【现状】`bind` 的短码路径：精确匹配优先，再按 `pairing.codePrefixes` 顺序拼接前缀（默认 `ZCT`），只在调用者客户的设备内解析，
  失败统一 `401 未找到可绑定的设备`，响应带解析后的 `deviceName`。
- 【选择】不要求重印标签；不新增规则。客户端必须使用服务端返回的实际 `deviceName` 发后续请求。
- **风险 R1【已确认：仅记录，不阻塞 #61】**：同客户内若存在 `ZCT000001` 与另一前缀的 `ABC000001`（换机使用不同前缀的新设备时可能出现），
  短码 `000001` 会按前缀配置顺序解析到第一个，可能指向**旧设备**。缓解建议：换机用同前缀新编号；或在导航契约（#63）里对多重匹配返回
  `409 ambiguous` 而非择一。换机建议用同前缀新编号；多重匹配处理归 #63 导航契约。

## 8. 各端工作

| 端 | 需要做 | 依赖本契约的哪些点 |
| --- | --- | --- |
| 后台 admin | 油站页展示唯一当前设备；分配候选只含同客户油站且预提示占用；换机用 3.2，显示旧/新编码与"不接续历史"；处理 `station_occupied`/`station_device_changed`/`device_in_other_station`；换机后重置设备相关缓存与迟到响应 | §3.1 §3.2 §3.3 |
| 小程序 | 按 `station_id` 找当前设备，只用 `device_name` 取数；换机后切换设备并清缓存/订阅；B 无授权时提示联系管理员；不新增换机入口 | §3.3 §5 §6 |
| 安卓 | 出厂码与 TB 名称一致；换机用新码和新凭据；旧本地历史/补投队列不以新身份发送；同设备断网补投不受影响；不新增线协议字段 | §1 §5（云端对身份的假设） |

### 8.1 换机后的监控页行为（用户 2026-10-14 确定，admin 与小程序统一实现）

- 从**油站入口**打开的监控页：换机后刷新油站当前关联，**自动切换到 B**；清理 A 的缓存、订阅与已选罐；
  阻止 A 的迟到响应/推送覆盖 B；B 无数据显示空状态（不回退 A）。
- 直接按设备编码查看 A 的**独立入口**可以保留（A 仍是该客户的未分组设备，旧数据按旧身份与现有权限可见），
  但在任何界面都**不得**再把 A 显示为该油站的当前设备。
- 云端不推送"换机事件"；客户端靠刷新 `GET /api/devices` 的 `station_id` 得知，WebSocket 连接按设备授权，不随换机变化。

## 9. 联合验收用例

云端自动化（真实 PostgreSQL，实现后给出实际结果）：
1. 同站分配两台不同设备：第二个 409；直接 SQL `UPDATE` 违反唯一索引（23505）。
2. 并发 N 个分配同一空油站：恰好 1 个 200，其余 409，库中仅 1 行。
3. 同设备同站重复分配/重复换机：幂等 200，`changed:false`，不重复审计。
4. 换机 A→B：A 的 `station_id` 为空、B 为该站、A 的绑定与记录未变、B 无绑定；换机中途失败（如新设备跨客户）无半成品。
5. 过期 `expectedCurrentDeviceName` → 409 且不改动；新设备在别的油站 → 409。
6. 权限：`customer_user` 403；客户管理员对别客户的油站/设备得 404 且与不存在一致；账号停用 403；授权前置于存在性（对不存在设备不产生锁等待差异）。
7. 隔离：A 有罐 1 的汇总/报警/出入库行与同 eventId；换机后用 B 查询无任何 A 行；A 迟到写入后 B 仍无；A 仍可按 A 查询。
8. 迁移：含重复数据时迁移失败并列出冲突；无冲突时 up/down/up 往返。

三端联合（**云端不能代替，需要真实环境**）：后台把 A 换为 B 后，小程序刷新到 B 且无 A 缓存/推送；B 无数据时显示空状态；
无授权的员工看不到 B；安卓 B 只发自己数据；真实 ThingsBoard/真机上的多罐与同设备补投。

## 10. 决定记录与剩余待确认

| # | 问题 | 结论 |
| --- | --- | --- |
| Q2 | 允许空油站 | 允许 |
| Q3 | 客户管理员可否换机 | 可（本客户） |
| Q4 | `GET /api/stations` 是否带当前设备 | 本版不加 |
| C1 | #63 前 bind 是否继续 | 保持现状作为过渡；收紧归 #63 |
| R1 | 短码跨前缀多重匹配 | 仅记录；换机用同前缀新编号；多重匹配归 #63 |

剩余待确认：无阻塞 #61 的项。#63 的接口契约（按罐授权、导航接口）另立文档，不在本版。

## 11. 其他端的反馈（已收到）

- admin（2026-10-13）：建议 404 加 `code` 以区分油站不存在与设备未登记 → **采纳，v1.1**，向后兼容（`error` 文案不变）。
- android（2026-10-13）：身份假设成立；发现并在 PR #38 分支修复"待补投队列/ACTIVE 报警/命令去重不带设备身份"的缺口
  （补丁未提交、未合入）。注意：同一硬件输错编码再更正，错码期间排队的事件留在错码名下不自动转移（有意的隔离策略）。
  §9「安卓 B 只发自己数据」依赖该补丁合入 PR #38。

## 12. 当前已实现行为与验证（云端，基准 `08c0524` 之上的未提交改动）

已实现：迁移 `000023`（先决检查 + 部分唯一索引）、`PUT /api/devices/:device/station` 的占用冲突与 `code`、
`POST /api/stations/:id/replace-device`、404 `code`。验证见 `internal/api/stations_test.go`、
`internal/api/station_replace_test.go`（真实 PostgreSQL）：唯一约束直写 23505、8 路并发分配仅 1 胜、6 路并发换机仅 1 胜、
换机 A→B 不复制绑定/不改客户、过期页面 409、跨客户/未登记/权限边界、旧设备同 id 记录与迟到写入不进入新设备、重试幂等且不重复审计、
路径登记于 `tenancy_test.go` 的路由遍历。
联合验收中发现并修复：此版 ThingsBoard 对从未上报的键返回 `[{"ts":…,"value":null}]`，旧实现把它当作空串 `tankCount` 解析失败并误报 `503`，
使刚安装的新设备 B 看起来像平台故障；现 null 视为无数据，`/telemetry/latest` 返回 `404 this device has not reported any telemetry yet`（回归测试 `TestLatestSnapshot_NullValuesMeanNeverReported`）。迁移含冲突数据时失败并列出冲突（手工验证）、up/down/up 往返通过。
**未验证**：真实 ThingsBoard 上换机后的最新值/历史读取（云端读穿按 URL 里的设备名，代码路径未变，未在真实 TB 上重跑换机场景）、
`/ws/alarms` 在换机瞬间的推送（沿用按设备授权，未加专门用例）、三端联调、真机。

## 14. 验收范围与依赖（需求边界）

| 能力 | 归属 | 对本需求的关系 |
| --- | --- | --- |
| 非空油站至多一台设备、并发分配、占用冲突 | #61 | 本需求直接内容，可独立验收 |
| 换机 A→B、B 无数据、旧数据不进入新设备、不复制绑定 | #61 | 本需求直接内容，可独立验收 |
| 一台大屏多罐用 `device_name+罐号` 定位 | #61 + 协议 V1 | 数据按设备名隔离已验证；罐展示由客户端按既有协议完成 |
| 员工按罐授权、罐级过滤、扫码只导航 | **#63** | 云端已实现（v1.3）；扫码只导航、"新设备油罐权限不自动继承"现可验收。admin/小程序尚需按 [tank-grants-contract.md](tank-grants-contract.md) 联调 |
| 客户开通状态、完成校验 | #60 | 不阻塞本需求：本需求不改变开通门槛；#60 的"至少一台设备分配到油站"与一对一兼容 |
| 手输编号、配网、凭据 | #62 / android#44 | 不阻塞，身份一致性假设由安卓核对 |
| 账号（手机号、微信登录） | #59 | 已实现于云端；员工无密码，开发联调的员工令牌只能由测试实例签发（见交接） |

## 13. 修订记录

- v0.1（2026-10-13）：初稿，基于 `08c0524`。
- v1.0（2026-10-13）：用户确认 C1/Q2/Q3/Q4/R1；接口与错误行为冻结，变更须递增版本并通知三端。
- v1.3（2026-10-14）：#63 落地——§6、§14 更新（bind 停用、罐级授权、员工只读）；§12 补缺陷说明已在 v1.2 文本中；#61 接口与错误行为不变。
- v1.2（2026-10-14）：新增 §8.1（监控页换机行为，用户确定）与 §14（验收范围与依赖）；接口与错误行为不变。
- v1.1（2026-10-14）：404 增加 `code`（`station_not_found`/`device_not_found`）；新增 §11 反馈、§12 实现与验证；其余不变。
