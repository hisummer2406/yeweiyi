## Context

- 设备在两处登记，只靠设备名字符串对上：业务库 `devices`（主键 `device_name`），ThingsBoard 设备实体（出货时按 CSV 预登记，`CHECK_PRE_PROVISIONED_DEVICES`，设备首次手输编号后领取 access token）。
- 业务库里以 `device_name` 关联的数据（2026-10-27 按迁移 000001–000031 核对）：
  - 级联外键（删 `devices` 行即删）：`user_tank_grants`、`user_devices`、`control_commands`、`device_pairing_secrets`。
  - 无外键、须显式删除：`stock_events`、`alarm_events`、`record_rejects`、`record_backfills`、`telemetry_hourly`、`audit_log`（刻意不做外键）。
  - `devices.station_id` 随行删除，油站自然变空。Redis 只用于报警推送与登录限流，没有按设备的缓存。旧 Alink 表已在 000011 删除；river 表不再使用。
- 会写回数据的路径：`internal/ingest`（每设备一个订阅 + `reconcileLoop` 定时回读；`sync` 只启动不停止订阅，内存表 `active`）、`internal/rollup`（按 `ListAllDeviceNames` 逐设备汇总）、`cmd/backfill`、命令结果写入。生产是单实例，但栅栏不依赖这一点。
- ThingsBoard 4.2.1.1。`tbclient` 已有 `GetDeviceByName`、`DeleteDevice`，没有列遥测键与删除遥测。

## Goals / Non-Goals

**Goals:** 按 spec 的清单删干净；跨存储失败可见、可重试、可续跑；删除开始后任何路径写不回；同编号可重登记但不复活旧历史；契约先定，再由 admin#29 对接。

**Non-Goals:**
- 设备本机存储、外部导出文件、PostgreSQL/ThingsBoard 的离线备份——云端 API 触及不到，备份按其轮转周期自然过期，`deploy/README.md` 写明。
- 批量删除、客户级删除、删除撤销（不可逆，没有回收站）。
- 换机、客户转移的语义不变，仍不删历史。

## Decisions

### D1 接口：POST 发起 + 任务查询（拟议，未实现）

- `POST /api/devices/:device/purge`，体 `{"confirmDeviceName": "...", "reason": "..."}`（`reason` 必填，与控制命令的二次确认一致）→ `202 {purge}`；已有未完成任务 → `200 {purge}`（同一任务，继续执行）。
- `GET /api/device-purges/:id`、`GET /api/device-purges?deviceName=` → 任务（含状态、步骤、各类条数、错误）。
- `purge` 形状：`{id, deviceName, status: running|failed|completed, step, error?, counts{...}, requestedBy{id, username}, reason, startedAt, finishedAt?}`。
- 错误码：`403 not authorized`（非平台管理员，先于解析请求体）、`400 confirm_mismatch`、`400 reason_required`、`404 device_not_found`、`409 device_purge_in_progress`（其他接口在删除中碰到该设备）。
- 不用 `DELETE /api/devices/:device`：需要请求体做确认、结果是异步任务而不是 204，HTTP DELETE 带体在代理与客户端库里支持不一致。
- 设备完成删除后 `devices` 行已不在，再次 POST 是 `404 device_not_found`；结果按 `deviceName` 查任务表得到。

### D2 任务表即栅栏

新表 `device_purges`（迁移 000032）：任务字段如上，`counts JSONB` 只存数字。部分唯一索引保证同一 `device_name` 至多一个未完成任务。`ListAllDeviceNames`（订阅、汇总、回补工具共用的设备清单）排除有未完成任务的设备。
- **栅栏**：数据库函数 `device_purge_blocked(device_name)` = 存在未完成任务。`UpsertStockEvent`、`InsertAlarmEvent`、`InsertRecordReject`、`UpsertTelemetryHourly`、`MarkRecordBackfill` 改成 `INSERT … SELECT … WHERE device_record_writable(...)`，判定与写入同一语句（沿用约束 9 的做法）。命令、罐授权、配对密钥都对 `devices` 有级联外键，删除中写入的行会随 `devices` 行一起删掉，不另设栅栏。登记与配对密钥签发的插入语句自带同一检查（否则删除完成前一刻的登记会复活设备行）；油站分配、换机、罐授权、发命令在处理函数里检查，命中即 409。
- 为什么不用"先删 `devices` 行当栅栏"：业务记录表没有外键，删行挡不住写入；而且 `ingest.sync` 不会因设备从列表消失而停订阅。
- 进程内：任务创建后调用 `Ingester.StopDevice(name)` 取消订阅与回读；`rollup` 跳过有未完成任务的设备。即使这一步漏掉（多实例、竞态），SQL 栅栏仍挡住写入。

### D3 执行顺序（每步幂等，步骤名写入 `step`）

1. `fence`：一个事务内锁 `devices` 行、插入任务。之后停订阅。
2. `thingsboard`：按名查设备；不存在即完成。存在则列出全部遥测键，按键删除全部数据（`deleteAllDataForKeys=true`），再读一次键/数据核对为空，然后删除设备实体（连同凭据与属性，设备无法再上报），最后按名查询确认 404。
3. `business`：一个事务内删除 `stock_events`、`alarm_events`、`record_rejects`、`record_backfills`、`telemetry_hourly`、`audit_log`（`device_name` 相等，排除本删除动作自身的审计）、`devices` 行（级联授权、绑定、命令、配对密钥），记录各表条数。
4. `verify`：逐表按设备名计数为 0、ThingsBoard 查无此设备 → 任务 `completed`、写 `finished_at`。

先删 ThingsBoard：设备先断联、不再产生新数据；若业务库先删而 TB 失败，回补工具可能从 TB 重新读出旧记录（栅栏也挡，但少一个风险面）。失败即 `failed`+`step`+`error`；重新 POST 或启动时的续跑器从 `step` 继续。

### D4 删除自身的痕迹

- 以设备为对象的历史审计随清单删除；`denied` 的越权删除尝试与本次发起记录写入审计（`action=device.purge`），它们描述的是删除动作，不含设备业务载荷。
- `device_purges` 行永久保留，作为 #83 第 7 点要求的最小结果记录。

### D5 重放与再次登记

- 删除完成后同编号可经 `POST /api/devices` 重新登记；ThingsBoard 需运维重新预登记该编号，设备端需在旧凭据失效后重新领取（依赖设备端确认，见 Open Questions）。
- 入库语句额外检查：该编号最近一次 `completed` 任务的 `finished_at` 存在时，信封 `ts` ≤ 该时刻的出入库、报警、拒收原文不写入；汇总只聚合该时刻之后的小时。用信封 `ts`（生成时刻，协议硬要求补投原样保留）而不是到达时间，正是为了挡住设备本机旧记录的重放。

### Alternatives considered

- **只删 TB 遥测、保留 TB 设备实体**：设备照常上报，同编号重登记无需重新预登记；但删除后设备仍在往 TB 写数据，重登记时首次全量回补会把这段数据读进来，与"彻底删除"相悖。故选择删除实体（待确认，见下）。
- **同步删除接口（200 即完成）**：TB 按键删除与核对可能耗时数十秒，超时后客户端无从得知结果，违反 #83 第 6 点。

## Risks / Trade-offs

- [设备时钟错误，新记录的 `ts` 早于删除时刻] → 被当作重放丢弃。缓解：重登记前确认设备时间；丢弃计数写日志；在契约中列为已知限制。
- [ThingsBoard 删除实体后其后台清理是异步的] → 我们先显式按键删遥测并核对，不依赖其后台清理。
- [删除后忘记重新预登记，设备无法接入] → 契约与 `deploy/README.md` 写明重新接入步骤；admin 完成页提示。
- [误删] → 只限平台管理员、须输入完整编号与原因；没有撤销，admin 弹窗写明"不可恢复"。
- [备份中仍有副本] → 不宣称清除；文档写明备份轮转周期。
- [恢复早于删除的备份会让被删设备复活] → `deploy/README.md` 写明恢复后逐台再删。
- [未合并分支 `tank_settings`（000026）、`tank_calibration`（000029）也按设备编号存数据] → 合并时加入删除清单与 `CountDeviceRemnants`，`CLAUDE.md` 写成规则。

## Migration Plan

1. 迁移 000032（任务表、栅栏函数、写入语句改造），本地验证 down/up 往返；`sqlc generate`。
2. 先上线栅栏与任务表（无入口时不影响现有行为），再上线接口；admin#29 对接后才在界面出现入口。
3. 回滚：down 迁移删除任务表与函数；已完成的删除不可恢复。

## Decisions confirmed（2026-10-27，用户）

- 删除 ThingsBoard 设备实体（设备断联；同编号重新接入须重新预登记、设备重新领取凭据）。
- 重放规则按信封 `ts` 与最近一次删除完成时刻比较，接受"设备时钟错误时新记录可能被丢弃"的已知限制。

## Open Questions

- 【待确认·设备端 zhongchengyeweiyi】旧 access token 失效后设备能否重新走预登记领取？不影响云端实现，影响重新接入的操作步骤。
- 【待确认·admin#29】接口路径、`purge` 形状与错误码。
