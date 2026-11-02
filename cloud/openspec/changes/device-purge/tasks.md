## 1. 契约

- [x] 1.1 写 `docs/device-purge-contract.md`：接口、请求/响应、`purge` 形状、错误码、重复请求语义、状态与步骤、清理清单、不覆盖的副本、重放与重登记规则，标注【需求】【选择】【待确认】
- [x] 1.2 Open Questions 里的用户决定写回 design.md 与契约后再进入第 2 组

## 2. 数据库

- [x] 2.1 迁移 000032：`device_purges` 表（中文注释、同设备至多一个未完成任务的部分唯一索引）与 `device_purge_blocked`、最近删除完成时刻的函数；down 迁移；验证 down/up 往返
- [x] 2.2 改写入语句加栅栏与重放截止：`UpsertStockEvent`、`InsertAlarmEvent`、`InsertRecordReject`、`UpsertTelemetryHourly`、`MarkRecordBackfill`；`ListAllDeviceNames` 排除删除中的设备；登记与配对密钥插入语句拒绝删除中的设备
- [x] 2.3 新增按设备删除/计数各表的查询与任务读写查询；`sqlc generate`

## 3. ThingsBoard

- [x] 3.1 `tbclient`：列出设备遥测键、按键删除全部遥测；对真实 ThingsBoard 写测试（删后键与数据为空）
- [x] 3.2 `DeleteDevice` 后按名查询 404 的核对

## 4. 删除执行

- [x] 4.1 删除执行器：`fence` → `thingsboard` → `business` → `verify`，每步幂等，失败写 `failed`/`step`/`error`
- [x] 4.2 启动时续跑未完成任务；同设备并发只跑一个
- [x] 4.3 `Ingester.StopDevice`（`rollup` 与 `cmd/backfill` 经 `ListAllDeviceNames` 自然跳过）

## 5. 接口

- [x] 5.1 `POST /api/devices/:device/purge`、`GET /api/device-purges/:id`、`GET /api/device-purges?deviceName=`；权限先于解析请求体，写审计
- [x] 5.2 登记、配对密钥、油站分配、换机、罐授权、发命令在删除中返回 `409 device_purge_in_progress`
- [x] 5.3 新路由登记到 `tenancy_test.go` 的 `deviceScopedRoutes`

## 6. 验证（隔离数据，真实 PostgreSQL + ThingsBoard）

- [x] 6.1 客户管理员、员工、停用的平台管理员被拒且无副作用（无效令牌走既有认证中间件）
- [x] 6.2 两台设备：目标设备多罐、历任两个客户、有遥测/汇总/报警/出入库/拒收/命令/授权/审计；删除后逐项为空，另一台全部不变，油站变空
- [x] 6.3 删除中出入库、报警、小时汇总、回补标记写不进（写入语句栅栏），设备不再交给订阅/汇总/回补工具；真实栈上实时订阅被停止、删除后设备再报警不入库（命令结果随设备行级联删除，未单测）
- [x] 6.4 注入 TB 失败、业务库失败、服务重启、重复请求：状态正确、可续跑、不报假成功
- [x] 6.5 同编号重登记后重放旧记录被丢弃，新记录正常入库
- [x] 6.6 `go build ./... && go vet ./... && go test ./... -p 1`

## 7. 文档与交接

- [x] 7.1 `CLAUDE.md` 约束 5 加唯一例外并链接契约；`docs/engineering-journal.md` 记决定；`deploy/README.md` 写备份边界与重新接入步骤
- [ ] 7.2 在 cloud#83 与 admin#29 留言契约链接，交 admin 对接
