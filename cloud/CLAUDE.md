# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件，不要将该链接替换为普通文件。

## 阅读顺序

1. 修改前先读本文件。
2. 阅读目标包、其测试，以及关联的协议或部署文档。
3. 只有本指南链接到历史，或确实需要决策背景时，才阅读
   [工程日志](docs/engineering-journal.md)。日志是归档，不是当前指令。

本文件刻意保持简短：当前规则在这里，历史决策和复盘在
`docs/engineering-journal.md`。两者冲突时，以本文件为准。

## 项目概要

这是安卓液位仪的云端后端。设备仅通过 ThingsBoard CE 接入；Go 服务提供业务
REST/WebSocket、远程控制状态机、审计和用户—设备授权。PostgreSQL 保存业务数据，
ThingsBoard 保存连续遥测数据，并作为设备数据的传输通道与短期缓冲。

```text
Android 设备 <-> ThingsBoard 原生 MQTT <-> Go 后端 <-> PostgreSQL / Redis <-> 客户端
```

- 不要引入 EMQX、Alink/Paho 兼容层、第二个 MQTT 客户端或本地遥测库。
- 不要直接访问 ThingsBoard 数据库；只能使用其公开 REST/WebSocket API。
- 登录令牌滑动续期（#51）：受保护接口在令牌剩余有效期不足 `auth.tokenTTL` 一半时，于响应头
  `X-Renewed-Token` 下发新令牌，客户端覆盖本地令牌即可；没有该头则行为不变。已过期（401）、已停用/已删除
  （403）不续期。令牌带 `authAt`（上次输入密码的时间），续期时沿用，且新令牌的 `exp` 不会超过
  `authAt + auth.maxSessionAge`（默认 30 天，到点必须重新登录）。`/ws/alarms` 不受影响：连接仍在**原令牌**
  到期时以 `token expired` 关闭，客户端需用新令牌重连。续期不写 `audit_log`。
- 设备端仓库是 `~/Developer/zhongchengyeweiyi`，小程序仓库是
  `~/Developer/yeweiyi-miniapp`。跨仓库协议变更必须获得相关负责人确认。

## 快速启动

```bash
docker compose up -d
docker compose -f docker-compose.thingsboard.yml up -d

migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
  -path db/migrations up

JWT_SECRET=dev-secret \
TB_BASE_URL=http://localhost:8081 TB_USERNAME=tenant@thingsboard.org TB_PASSWORD=tenant \
go run ./cmd/server
```

配置优先级为默认值 -> `config.yaml` -> 环境变量。完整列表见
`config.example.yaml`；时长必须写成 `30s` 一类格式。

## 验证

```bash
go test ./internal/protocol/       # 无容器依赖
go test ./... -p 1                # ThingsBoard 相关包串行执行
go build ./... && go vet ./...
```

集成测试在基础设施缺失时会给出原因并跳过。跳过不等于通过。开发机上
ThingsBoard 相关包会争抢资源，完整套件请使用 `-p 1`。

## 目录

```text
fixtures/protocol/v1/ 跨仓库共享 fixture + MANIFEST.json 校验和清单
internal/protocol/    设备协议 V1 契约
internal/tbclient/    唯一的 ThingsBoard REST/WebSocket 网关
internal/devicesim/   设备侧参考实现
internal/control/     控制状态机、授权、审计
internal/ingest/      ThingsBoard 遥测订阅；出入库/报警落业务库与回补（records.go）
internal/rollup/      遥测小时汇总任务（唯一允许落业务库的连续遥测形态）
internal/api/         REST/WebSocket API
internal/store/db/    sqlc 输出，禁止手改
db/migrations/        golang-migrate 输入
db/queries/           sqlc 输入
docs/                 当前规范与历史归档
```

## 不可破坏的约束

1. 泵、阀门、重启等物理命令默认禁用，且该限制对所有角色生效。
2. 未收到 ACK 的命令状态是 `sent`，不是 `failed`；设备可能已开始执行。
3. ACK 不代表执行成功；只有异步 `cmdResult` 能确定最终结果。
4. 命令状态流转必须由 SQL 守卫，不能改成只在 Go 中校验。
5. 连续遥测（液位、温度等 30 秒快照）不得复制到业务数据库，ThingsBoard 是其唯一事实来源，原始数据只保留
   7 天；业务库只可存其**小时级汇总**（#30）。出入库（`stockEvent`）与报警（`alarm`）是业务记录，**业务库是
   长期事实来源**，须永久保存、非必要不删除（#29）：设备端本地长期保留这些记录，云端必须与之一致，ThingsBoard
   里的副本可以删。出入库与报警的落库（#29）已实现：`internal/ingest` 实时订阅 + 定时回读写入
   `stock_events` / `alarm_events`（无法解析的原文进 `record_rejects`），写入全部幂等；`/api/oil-in-out`、
   `/api/alarms` 读业务库。但 ThingsBoard 的遥测 TTL 仍必须保持关闭（`TS_RETENTION_SECONDS=0`），直到
   目标部署上按 `deploy/README.md`"启用 ThingsBoard 遥测 TTL 之前"完成出入库/报警回补（`cmd/backfill`）和小时汇总回补
   （`cmd/backfill -telemetry`）并核对无误——否则这些数据会被永久删除。小时汇总（#30）已实现：`internal/rollup`
   每小时由 ThingsBoard 的聚合结果写入 `telemetry_hourly`；`telemetry/history` 在 `telemetry.retention`
   之内读 ThingsBoard，更早的部分读汇总表（仅 `interval=1h`）。
6. 必须先授权、后检查设备是否存在，避免设备名枚举。
7. 设备配对密钥必须与 ThingsBoard/MQTT 设备凭据隔离。
8. 仅使用 ThingsBoard CE 功能，且部署必须支持完全离线环境。
9. 设备访问只有一条规则，定义在数据库函数 `user_may_access_device`（迁移 000015）：平台管理员任意设备；
   客户管理员本客户全部设备；客户普通用户本客户内已绑定的设备；已停用的一律否。角色、租户边界、停用状态
   全部读数据库，不读 JWT（认证中间件用数据库行填充请求上下文）；命令的 INSERT 语句自己再校验一次，
   判定与写入是同一条语句。跨客户、不存在、未绑定、未分配对非平台管理员必须返回完全相同的 403，且先于联锁。新增含 `:device` 或 `device` 参数的路由，必须登记到
   `internal/api/tenancy_test.go` 的 `deviceScopedRoutes`，否则测试失败。

## 变更规则

### Go 与测试

- 使用 `gofmt`；小需求优先标准库。
- 注释说明原因、限制和非显而易见的陷阱，不重复代码表意。
- 涉及平台行为或线协议时使用真实基础设施测试；协议测试应使用手写 JSON 或共享
  fixture，而非被测实现序列化后的数据。
- 保留无关工作区变更。仅在用户要求时提交；提交标题使用中文。

### 数据库变更

1. 同时新增 `db/migrations/0000NN_name.up.sql` 与 `.down.sql`；新表和字段补充中文注释。
2. 应用迁移后，验证一次 down/up 往返，再更新 `db/queries/`。
3. 执行 `sqlc generate`；禁止直接编辑 `internal/store/db/`。

### 文档

- 本文件只记录当前架构、命令、约束和工作流。
- 重大决策历史与已废弃的推理写入 `docs/engineering-journal.md`，使用带日期的标题，
  并链接到当前规范。
- 架构、协议或部署变化时，更新对应的 `docs/` 文档。

## 仍未定案或未验证的事项

- 协议 V1 尚未冻结。做协议工作前先读 `docs/protocol-v1-open-items.md`（待确认清单，含决定人）
  与 `docs/protocol-v1-reconciliation.md`（差异背景）。Android `main` 上**没有**任何 ThingsBoard 代码；
  协议实现在设备端 `feature/thingsboard-device-protocol`（zhongchengyeweiyi PR #38，**未合并、零真机验证**），
  该分支的遥测、报警、ACK、`cmdResult`、`stockEvent` 已对照共享 fixture 对齐（2026-10-06 复核，见
  open-items §A），但合并与真机联调前仍不得视为兼容。清单清空前，云端不要**自行发起**新增协议字段。例外：设备端负责人已在跨仓库 issue 中提出、云端只做
  消费的**可选、向后兼容**字段（如 #14 标定属性、#15 `source`），可先落地，但必须在 PR 里写明
  "Refs 而非 Closes"，并在 open-items 登记待设备端同步的 fixture；字段名、单位、形状仍以设备端确认为准。
- **真实设备上的控制闭环：旧实现是断的（已实测），新实现待真机验证。** 旧设备把终态发成第二条 RPC
  响应，而 ThingsBoard 的双向 RPC 只把第一条交给调用方，云端永远收不到结果，命令停在 `received` 直到
  被清扫成 `expired`。实测见 `internal/tbclient/rpc_response_channel_test.go`（保留，防平台升级改变行为）。
  设备端 PR #38 已改为终态走 `cmdResult` 遥测，只发一条 RPC 响应；**合并并在真实 ThingsBoard 上跑通前，
  不得宣称闭环已通**，见 open-items A-1。
- 共享 fixture 的校验（`fixtures/protocol/v1/MANIFEST.json` + `verify-manifest.sh` +
  `internal/protocol/manifest_test.go`）**只在云端跑，还不是跨仓库门禁**；Android 侧只有一份原样副本（`ProtocolV1ContractTest` 读它，无校验和清单、无构建期校验、副本落后于 #14），
  见 open-items §C-1。
- 单位以及 `config.setTank` / `config.setProbe` 的参数形状需产品和设备端负责人决定；
  不得单方面冻结或扩展。
- 多租户**阶段 1（隔离）与阶段 2（管理 API）均已落地**：`customers` 表；非平台管理员用户恰好属于一个
  客户，设备至多属于一个客户（`NULL` = 未分配）；租户只存在于 Go 业务库，不映射 ThingsBoard customer。
  管理接口在 `internal/api/admin.go`（客户 CRUD、设备分配、用户创建/列出/停用/重置密码/迁移），谁能做什么
  读数据库当前的角色、客户与 `users.disabled_at`，不读 JWT。**仍需 SQL 引导第一个平台管理员**（见
  `deploy/README.md`）；客户没有“停用”，有历史记录的客户因外键 RESTRICT 无法删除。设计与决策见
  `openspec/changes/archive/2026-10-05-implement-multitenant-support-with-customer-isolation/`。`GET /api/devices` 的语义变化、
  `pairing-secret` 新增必填 `customerId` 已对照小程序与运维控制台的代码核对，无需改客户端（2026-10-06，见
  open-items §D-租户）；实现运维控制台的"配对密钥"页面时请求体须带 `customerId`。
- `/api/oil-in-out` 与 `/api/alarms` 读业务库（#29）。`/api/oil-in-out` 依赖尚未合并的设备端 JSON 改动
  （zhongchengyeweiyi#39）。真实设备当前上报的
  `DetailInoutRecord.toString()` 解析不了，此时接口返回 502 与 `STOCK_EVENT_FORMAT_UNSUPPORTED`，
  不再以 200 + 空数组冒充成功；两条路径都返回 `{rows, parsed, unparsable, legacySkipped, …}`。
  升级固件**不会**转换 ThingsBoard 里已有的旧记录，因此车队升级后必须设置
  `stock.legacyFormatBefore`，否则回看历史的查询会永久 502。该分类依赖设备把生成时刻写进信封
  `ts` 且补投原样保留——这是协议硬要求，见 `docs/protocol-v1-stock-event.md` §3 与 `deploy/README.md`。
- **小程序与云端 REST 的契约自 PR #11 起全面不兼容**（行内字段、已删端点、`/api/alarms` 的信封
  形状），尚未获小程序负责人确认。清单见 `docs/protocol-v1-open-items.md` §D。
- OTA、生产 MQTT TLS、租户隔离与容量结论都必须在真实环境验证后才能宣称完成。

## 延伸阅读

- `docs/protocol-v1.md`：协议契约与开放问题。
- `docs/protocol-v1-open-items.md`：跨仓库待确认清单，按决定人分类。
- `docs/protocol-v1-reconciliation.md`：跨仓库差异背景与实测结论。
- `docs/protocol-v1-stock-event.md`：出入库最终字段映射与所需设备端改动。
- `fixtures/protocol/v1/README.md`：共享 fixture 的使用与改动规则。
- `docs/thingsboard-ce-baseline.md`：平台和部署基线。
- `deploy/README.md`：离线部署、备份和恢复。
- `docs/engineering-journal.md`：归档的设计理由与评审历史。
