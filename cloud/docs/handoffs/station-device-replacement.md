# 交接报告：油站与大屏一对一、换机按新设备码重新采集（cloud#61 云端）

状态：**云端代码完成（#61 + #63），本端检查通过；四端联合验收未完成（缺 admin/小程序对 #63 的联调、小程序 UI 与真机）。** 未提交。

## 1. 版本信息
| 项 | 值 |
| --- | --- |
| 仓库 / 分支 | `yeweiyi-cloud` / `feat/station-one-device`（本地，自 `feat/wechat-login` 切出，改动随行，无提交） |
| HEAD / 基准 | `08c0524`（#59 微信登录）。本需求依赖 #59 的账号模型（员工无密码、手机号账号） |
| 已跟踪改动 diff SHA256 | `ead7428402d12b682a594ab21605d8effdb00cb2c2b3b69098c97beb1af0b26a`（ 37 files changed, 900 insertions(+), 863 deletions(-)） |
| 未跟踪 | `db/migrations/000023_*.{up,down}.sql`、`docs/station-device-replacement-contract.md`、`internal/api/station_replace_test.go`、本报告及证据 JSON |
| 契约 | `docs/station-device-replacement-contract.md` v1.3 SHA256 `bb1cb5ba0186fddc2654e3f232d8f00da5c85fc69efe522cda808576a0fdfc93`；`docs/tank-grants-contract.md` v1.0 SHA256 `abb1b1ddd71a55123fcdad5491552066bdeb3fc57e30ba054bd61048213fccdb`（均未提交） |
| 备份（仓库外，#61 阶段 / #63 阶段） | `.../scratchpad/backup-cloud61-20261014-003308/` 与 `/private/tmp/claude-501/-Users-stone-Developer-yeweiyi-cloud/a1af7ac7-9845-42df-8379-432696528394/scratchpad/backup-cloud63-20261014-011433/`（tracked.diff、untracked.tar） |

## 2. 改动
- 迁移 `000023_station_one_device`：先决检查（一站多设备则失败并列出冲突，不改数据）+ 部分唯一索引 `devices_station_id_key`。
- `PUT /api/devices/:device/station`：占用 `409 station_occupied`（含 `occupiedBy`），幂等，并发由唯一索引裁决。
- 新增 `POST /api/stations/:id/replace-device`：单事务换机，`expectedCurrentDeviceName` 必填。
- 404 带 `code`（`station_not_found`/`device_not_found`）。
- **联调中发现并修复的缺陷**（`internal/tbclient/telemetry.go`）：此版 ThingsBoard 对从未上报的键返回 `value:null`，旧实现误报 `503`，使新设备 B 像平台故障；现为 `404 this device has not reported any telemetry yet`。回归测试 `TestLatestSnapshot_NullValuesMeanNeverReported`。影响面：`/telemetry/latest`、`internal/rollup`（同函数）。

## 3. 检查（实际执行）
| 命令 | 结果 |
| --- | --- |
| `go build ./...`、`go vet ./...` | 通过 |
| `go test ./internal/protocol/` | 通过 |
| `go test ./... -p 1 -count=1 -v` | 全部包通过，**0 个 SKIP**，ThingsBoard、PostgreSQL、Redis 容器在线 |
| 迁移 up/down/up、含冲突数据 | 往返通过；冲突时失败并列出 `[ZZ-P1, ZZ-P2]`（探针数据已清理） |
| `sqlc generate` | 已执行，未手改生成代码 |
- 已知偶发：`TestLatestSnapshot_DropsTankRemovedSinceReconfiguration` 在修复期间失败过一次（`tankCount` 先于罐字段落库的写入竞态），同一用例连跑 4 次和整包重跑均通过；与本次改动无关，**未单独修复**。

## 4. 开发环境联调（云端侧，隔离实例）
实例：`http://localhost:18080`，独立库 `yeweiyi_e2e`，本机开发 ThingsBoard（非生产），未改 TTL，未发物理控制命令。账号/令牌在 0600 文件，未入库。
证据：`docs/handoffs/station-device-replacement-e2e-results.json`（脚本 28 步：27 通过，1 个 **预期为失败**即扫码 bind 仍授权，阻塞于 #63）。

| 场景 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- |
| 同站第二台设备 | 409 `station_occupied`，不改动 | 409，`occupiedBy` 正确，B 未挂站 | 通过 |
| 重复分配 | 200 `changed:false` | 同 | 通过 |
| 6 路并发分配空站 | 恰好 1×200 其余 409 | `[409,409,200,409,409,409]`，库中 1 台 | 通过 |
| 一台大屏多罐 | 罐 1/2/3，按设备+罐号 | 真实 TB，3 罐；单罐历史 200 | 通过 |
| A→B 换机 | 200，A 无站，B 在站 | 同 | 通过 |
| B 无数据 | 404 空状态，不回退 A | 修复后 404；修复前 503（缺陷） | 通过（修复后） |
| A 的报警/历史/汇总不进 B | B 为空 | B 报警 0、历史空 | 通过 |
| A 迟到上报 | 只进 A | 换机后 A 报警 1→3，B 仍 0，B 最新值仍 404 | 通过 |
| 实时推送隔离（WebSocket） | A 的报警只到 A 的连接 | A 连接 2 帧，B 连接 0 帧 | 通过 |
| 新设备不继承员工授权 | B 对未绑定员工拒绝 | 403 `not authorized for this device`，站列表不含该站 | 通过（设备级） |
| 过期页面/新=旧/跨客户/员工调用 | 409/400/404·409/403 | 同 | 通过 |
| 扫码只导航不授权 | 不新增授权 | `bind` 返回 200 并创建授权 | **未通过：阻塞于 #63** |

## 5. 跳过项与未验证（不算通过）
- 真实设备、真机：全部未做。
- UI 级（浏览器、小程序 DevTools）联调：云端无法做；admin 因 Chrome 扩展未连接未做，小程序等待其用户确认。
- 实时推送：已验证 A 的报警不到 B 的连接；**换机瞬间**连接中的 WebSocket 行为未专门测试（按设备授权，不随换机变化）。
- 目标环境的一站多设备冲突：只核对了开发库与隔离库。
- 罐级授权（#63）：云端未实现，无从验证。

## 6. 依赖
- 阻塞本需求验收：**#63**（扫码只导航、罐级授权与继承）。
- 不阻塞：#60（开通门槛不变）、#62（手输编号）。
- 代码依赖：#59 账号模型（已在基准提交中）。

## 7. #63：员工按罐授权、扫码只定位（2026-10-14 追加）
契约：`docs/tank-grants-contract.md` v1.0。迁移 `000024_tank_grants`（up/down/up 已验证，`sqlc generate` 已执行）。
- 授权存 `user_tank_grants(user,device,tank)`；`user_may_access_device` 改为"有 ≥1 授权罐可定位"，新增 `user_may_access_tank`。
- 过滤路径：设备列表（`granted_tanks`）、油站列表、`/devices/:device/tanks`、最新值、历史（未授权/不存在同一 403 `tank_not_authorized`）、
  `/api/alarms`、`/api/oil-in-out`（SQL 内过滤，分页与计数按过滤后）、`/ws/alarms`（逐帧复核）。命令与审计对员工不可见。
- 扫码：`POST /api/devices/resolve`（只定位）；`/devices/bind` 恒 410 `bind_removed`；`unbind` 空操作。旧 `user_devices` 不转换。
- 新接口：`GET /api/users/:id/tank-grants`、`PUT /api/users/:id/tank-grants/:device`（整组替换，幂等）。
- 其他：`cmd/smoke` 的绑定步骤改为登记+定位；`roleAllows(customer_user)` 收紧为只读；用户/设备换客户同事务清授权。
- **行为收紧（需产品确认，契约 P1/P2/B1）**：员工不能再发控制级命令；升级后员工需重新分配；旧小程序调 bind 得 410。

### 检查（实际执行）
| 命令 | 结果 |
| --- | --- |
| `go build ./...`、`go vet ./...`、`go test ./internal/protocol/` | 通过 |
| `go test ./... -p 1 -count=1 -v` | 全部包通过，0 SKIP，0 FAIL（ThingsBoard、PostgreSQL、Redis 在线） |
| 新增专项测试 | `TestTankGrants_*`（员工只见授权罐/WS 帧过滤/授权边界与并发/换机不继承）、`TestResolve_*`、`TestBind_IsRetiredAndWritesNothing`、`TestIssue_CustomerUserCannotIssueAnyCommand` |
| 路由登记 | `tenancy_test.go`：`GET /devices/:device/tanks`、`PUT /users/:id/tank-grants/:device` 入 `deviceScopedRoutes`；`resolve`、`GET tank-grants` 入 `exemptRoutes`（带理由） |

### 开发环境联调（云端侧，隔离实例，35 步 35 通过）
证据：`docs/handoffs/station-device-replacement-e2e63-results.json`。覆盖：未授权员工看不到设备/扫码 404 与不存在相同；授权 A/1 后列表、
最新值、历史、其他罐 403；管理员读全部罐；重复授权幂等；授权未上报的罐 400；重复扫码/扫 B/bind 不增加授权；A→B 后员工对 B 403 且油站消失、
A 上授权未删未复制、重新分配 B/1 后可读；撤销同令牌立即失效；别客户管理员/员工自授权/授权别客户员工被拒；员工发命令 403。
**未覆盖/未验证**：WebSocket 的实例级联调（自动化测试已覆盖帧过滤）、admin 与小程序 UI、真机。

### 小程序 T0 序列（云端用管理员账号执行，小程序用员工 A 令牌读取核对）
| 步骤 | 时点 | 云端动作 | 小程序读取核对 |
| --- | --- | --- | --- |
| 换机 A→B | 01:12:02 | `replace-device` 200 | 01:12:15 通过：员工 A 设备列表仅 A（未分组）、站 5 消失、B 403、resolve 930002→404 |
| 授权 B/1 | 01:12:35 | `PUT tank-grants/B [1]` 200 | 01:12:44/01:12:53 **失败**：被 admin 会话 01:12:37 起的并发写入覆盖（审计 118–127），非云端缺陷 |
| 授权重做 | 01:13:33 | 同上 | 01:13:45 通过：列表含 A、B，站 5 出现，B latest 仅罐 1，followStationDevice→B |
| 撤销 B | 01:13:57 | `PUT … []` 200 | 01:14:08 通过：B 全部 403，站 5 消失，resolve 930002→404 |
教训：多个会话共用同一测试数据会互相覆盖。已拆分：小程序用 `g63`（员工 userId 4、ZCT930001/2、油站 5），admin 用 `admin17`（员工 userId 6、ZCT940001/2、油站 6）。

### 仍需人工/真机（云端无法代替）
- 小程序 DevTools：页面切换与缓存清理、撤销瞬间已打开页的 `/ws/alarms` 不再收帧、真实扫码、小程序码 scene（契约未定义）。
- admin 浏览器：#17 的 UI 保存与撤销（admin 会话可驱动的部分自行完成）。
- 真机：安卓补丁合入 PR #38、两设备身份/多罐/换机隔离/旧队列/同设备补投、备份恢复到另一硬件；7 个 ProbeReplayTest 跳过不算通过。
