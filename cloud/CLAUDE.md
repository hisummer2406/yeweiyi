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
- 用户名账号（#77，契约见该文档 §11）：国外用户无微信，客户侧账号也可以是**独立用户名 + 密码**账号（`phone` 为 NULL，三种
  角色包括 `customer_user` 都必须有密码，用户名全局唯一不区分大小写，不得形如手机号）；与手机号账号二选一、不互转。手机号员工
  也可由平台管理员设置密码（之后 `phone` + 密码可登录）。`POST /api/login` 的 `username` 接受任何无手机号的账号。**没有自助改密、
  没有强制改密标记**（产品决定）：改密一律平台管理员重置。登录失败按账号标识与来源地址限流，超限 `429 login_rate_limited`
  （Redis，重置密码可解锁）。员工令牌调任何管理类接口一律 403，按库内当前角色判定。下面这条手机号账号的规则中"员工没有密码"一句已被
  放宽：员工密码可选。
- 账号（#59，契约见 [docs/account-phone-wechat-contract.md](docs/account-phone-wechat-contract.md)）：客户侧账号以手机号
  标识（`users.phone`，11 位大陆号，全局唯一，客户角色的 `username` 等于手机号）；手机号员工（`customer_user`）默认**没有密码**
  （`password_hash` 为 NULL），只能经微信登录；平台管理员可为其设置密码（#77）。**只有平台管理员能创建/停用/启用/重置账号**，客户管理员
  调用这些接口一律 `403 not authorized`（在解析请求体之前，写 `denied` 审计）；客户管理员只能列出本客户员工（手机号脱敏）。
  `POST /api/login` 的 `username`（平台管理员）与 `phone`（客户管理员）二选一；没有密码的员工以任何方式密码登录都与密码错误同样
  `401 invalid_credentials`。令牌 `sub` 是用户 ID，不是用户名；客户端用登录响应的 `user` 或 `GET /api/me` 取身份。
  新增的认证/账号响应带 `code` 字段，客户端按 `code` 分支。开发库里升级前的旧客户账号不迁移，需重建。
  微信登录：`POST /api/auth/wechat/login`（已绑定身份恢复）与 `/phone-login`（首次手机号授权并绑定，只收 `loginCode`
  `phoneCode`，手机号/openid 一律由服务端向微信验证，绝不收客户端提交的值）；不自动注册、不自动换绑，冲突 `409
  wechat_identity_conflict`。`wechat.enabled` 默认关，关闭时 `503 wechat_unavailable` 且不影响密码登录与启动；
  AppSecret 只能走 `WECHAT_APP_SECRET`。**只用假微信服务器测过，真实小程序联调未做**（`internal/wechat`）。
- 油站（#43、#61，契约见 [docs/station-device-replacement-contract.md](docs/station-device-replacement-contract.md)）：客户 → 油站 →
  设备，油站只存在业务库（`stations` 表、`devices.station_id`），ThingsBoard 不知道，设备名仍是唯一连接点。
  **油站与大屏一对一**：非空 `station_id` 至多关联一台设备，由部分唯一索引 `devices_station_id_key` 保证（迁移 000023；
  库里有一站多设备时迁移失败并列出冲突，不自动挑选/删除）；空油站允许（待安装/换机间隙）。
  设备所属油站必须与设备所属客户一致，由复合外键 + `CHECK` 保证；`SetDeviceCustomer` 换客户时同时清空 `station_id`。
  `PUT /api/devices/:device/station`：油站已被另一台设备占用 → `409 code=station_occupied`（含 `occupiedBy`），同设备
  同站幂等；**换机**走 `POST /api/stations/:id/replace-device`（`deviceName` + 必填 `expectedCurrentDeviceName|null`，
  单事务，过期 `409 station_device_changed`，新设备在别的油站 `409 device_in_other_station`，不隐式搬走/创建）。
  换机不迁移历史、不改名、不复制 `user_devices`、不删旧设备：所有记录以 `device_name` 为键，隔离是结构性的，不得
  新增任何按 `station_id` 聚合设备数据的读路径。`GET /api/devices` 每行带可空 `station_id`；`GET /api/stations` 的范围与设备列表
  同一套规则（`customer_user` 只见其有被授权罐的设备所在的油站，`customer_admin` 见本客户全部油站含空油站，平台管理员见全部）。
  管理接口 `POST/PUT/DELETE /api/stations`、`PUT /api/devices/:device/station`、`POST /api/stations/:id/replace-device`：
  平台管理员不限客户，客户管理员限本客户，别的客户的油站/设备与"不存在"对客户管理员完全相同：`404 station not found` /
  `404 device not found`（现带 `code`：`station_not_found` / `device_not_found`），带别的客户的
  `customerId` 建油站与客户不存在同为 `400 unknown customerId`（#54）。**`403 {"error":"not authorized"}` 只留给角色/账号
  问题**（`customer_user` 调管理接口、事务内发现权限已变、账号停用/删除）——客户端把它当作"账号失效"并回登录页（#79 起体不变、追加 `code`：`account_unavailable` | `role_not_allowed`，见
  [错误码契约](docs/error-codes-contract.md)），
  对象级拒绝不得再用它（`refuse` 返回 403，对象级用 `refuseObject`/`refuseObjectCode`；两者都写 `denied` 审计）。
  记录表不带 `station_id`，历史跟着设备所属油站走。加锁顺序固定：油站行 → 设备行（按名升序）。
- 油罐授权与扫码（#63，契约见 [docs/tank-grants-contract.md](docs/tank-grants-contract.md)）：员工（`customer_user`）按
  `(device_name, 罐号)` 获得**只读**查看权，存于 `user_tank_grants`；管理员（客户/平台）看其范围内全部罐。
  **扫码只定位**：`POST /api/devices/resolve`（`{"code":...}`，精确匹配优先再 `pairing.codePrefixes`，只在调用者客户内，
  只返回调用者本来就能定位的设备，其余一律同一 `404 device_not_found`，不写任何东西）。`POST /api/devices/bind` 已停用，
  恒 `410 bind_removed`，不论请求体（含配对密钥）都不写库；`/api/devices/unbind` 保留为无副作用空操作。授权只由**平台管理员**（客户管理员无此权限，一律 403；2026-10-14 产品决定）
  经 `PUT /api/users/:id/tank-grants/:device`（`{"tanks":[...]}` 整组替换，幂等）写入；罐号必须是设备已上报的罐
  （`GET /api/devices/:device/tanks`），客户端不能自造。旧 `user_devices` 不转换、不再被任何判定引用。员工**只读**：
  不能发任何命令，不读命令历史与审计；整机级记录（无罐号/tank=0）、无法解析的出入库原文只对管理员可见。授权每次读库，
  停用/撤销/换客户立即生效，WebSocket 逐帧复核。换机用新 `device_name`，天然没有任何授权。
- 客户开通（#60，契约见 [docs/customer-onboarding-contract.md](docs/customer-onboarding-contract.md)）：`customers.onboarding_status`
  `configuring`|`active`（迁移 000025，存量客户一律 `active`，新建默认 `configuring`，单向）。配置中的客户下**所有**非平台管理员账号
  不能登录/续期/访问业务：密码与微信登录在身份验证通过后、旧令牌在认证中间件与 `/ws/alarms` 握手均返回
  `403 {"code":"customer_not_onboarded"}`（不同于账号失效的裸 `not authorized`），`user_may_access_device/tank` 同样拒绝。
  仅平台管理员可 `GET /api/customers/:id/onboarding`、`POST .../onboarding/complete`（事务内锁客户行复检：有未停用且有手机号
  密码的客户管理员、至少一个油站、至少一台在该客户油站内的设备；不全 `409 onboarding_incomplete` + `missing`；重复幂等）。
  不使用 `users.disabled_at` 模拟客户状态；测试里 `newCustomer` 直接种为 `active`。
- 设备彻底删除（#83，契约见 [docs/device-purge-contract.md](docs/device-purge-contract.md)）：仅平台管理员，
  `POST /api/devices/:device/purge`（体须重复完整编号 + 原因）建异步任务，`internal/purge` 按 `thingsboard → business →
  verify` 执行，每步幂等，失败停在 `failed` 等再次发起，`running` 的启动时续跑。`device_purges` 未完成的任务就是该设备的
  **写入栅栏**：业务记录、小时汇总、回补标记的写入语句经 `device_record_writable` 判定（迁移 000032），`ListAllDeviceNames`
  不再列出它，登记/配对密钥/油站/换机/罐授权/发命令返回 `409 device_purge_in_progress`。完成后同编号可重新登记，但信封
  `ts` 不晚于删除完成时刻的记录一律不写入（挡设备重放）。**新增按设备编号存数据的表，必须同时加进删除清单
  （`db/queries/device_purges.sql` 与 `CountDeviceRemnants`）**。
- 交班、库存记录（#85，契约见 [docs/shift-stock-records-contract.md](docs/shift-stock-records-contract.md)）：设备以
  `shiftEvent`（一班开始、结束各报一次，同 `shiftId`）与 `stockRecord`（一次库存保存一条多罐）上报，只同步显示器已生成的
  记录，云端绝不用快照/汇总补造；升级前的旧记录不传。落 `shift_records`/`stock_records` 表头 + 每罐一行（迁移 000037），信封 `ts`
  最大者胜，永久保存（同约束 5）。`GET /api/shift-records`、`/api/stock-records` 每 (记录, 罐) 一行，规则同 `/api/oil-in-out`（#84），
  缺失值一律 `null` 不当 0，`syncStatus` 为 `UNSUPPORTED|SYNCING|SYNCED`，客户端只在 `SYNCED` 且无行时显示"暂无记录"。
  `oilTypeIndex` 是与界面语言无关的油品标识，`oilType` 只是显示名。**设备端与云端都未真机验证。**
- 设备端仓库是 `../android/`，小程序仓库是
  `../miniapp/`。跨仓库协议变更必须获得相关负责人确认。

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
   之内读 ThingsBoard，更早的部分读汇总表（仅 `interval=1h`）。**唯一例外**：平台管理员的设备彻底删除（#83，
   [契约](docs/device-purge-contract.md)）清掉该设备在业务库与 ThingsBoard 的全部数据；换机、客户转移仍不删任何历史。
6. 必须先授权、后检查设备是否存在，避免设备名枚举。
7. 设备配对密钥必须与 ThingsBoard/MQTT 设备凭据隔离。
8. 仅使用 ThingsBoard CE 功能，且部署必须支持完全离线环境。
9. 设备与罐的访问只有两条规则，定义在数据库函数 `user_may_access_device`（迁移 000015，000024 改写）与
   `user_may_access_tank`（000024）：平台管理员任意；客户管理员本客户全部设备与罐；客户普通用户只有本客户内**被授权的罐**
   （设备"可定位"= 至少有一个被授权罐）；已停用的一律否。角色、租户边界、停用状态、授权全部读数据库，不读 JWT（认证中间件用
   数据库行填充请求上下文）；命令的 INSERT 语句自己再校验一次，判定与写入是同一条语句。**设备可定位不等于可读整机**：任何返回
   整机数据的路径都必须再按罐过滤（遥测快照、历史、报警、出入库、WebSocket 帧），列表/分页/计数在 SQL 里过滤。跨客户、不存在、
   未授权对非平台管理员必须返回完全相同的 403，且先于联锁。新增含 `:device` 或 `device` 参数的路由，必须登记到
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
  与 `docs/protocol-v1-reconciliation.md`（差异背景）。设备端协议实现
  （zhongchengyeweiyi PR #38）已于 2026-11-02 合并入 Android `main`，**仍未做真机验证**，
  其遥测、报警、ACK、`cmdResult`、`stockEvent` 已对照共享 fixture 对齐（2026-10-06 复核，见
  open-items §A），但真机联调前仍不得视为兼容。清单清空前，云端不要**自行发起**新增协议字段。例外：设备端负责人已在跨仓库 issue 中提出、云端只做
  消费的**可选、向后兼容**字段（如 #14 标定属性、#15 `source`），可先落地，但必须在 PR 里写明
  "Refs 而非 Closes"，并在 open-items 登记待设备端同步的 fixture；字段名、单位、形状仍以设备端确认为准。
- **真实设备上的控制闭环：旧实现是断的（已实测），新实现已在真机上走通一次（2026-11-08）。** 旧设备把终态发成第二条 RPC
  响应，而 ThingsBoard 的双向 RPC 只把第一条交给调用方，云端永远收不到结果。实测见
  `internal/tbclient/rpc_response_channel_test.go`（保留，防平台升级改变行为）。新实现终态走 `cmdResult` 遥测：生产 ZCT000001
  单字段 `config.setTankSettings` 已验证 ACK→`cmdResult`→属性回显→重启持久化，见
  [docs/tank-settings-device-verification.md](docs/tank-settings-device-verification.md#验证记录)。其余方法、客户管理员、离线/超时
  路径仍未真机验证；`allowConfigCommands` 在生产保持关闭，只在验证短窗口内打开。
- 配置命令提交保护（#3，契约见 [docs/config-command-contract.md](docs/config-command-contract.md)，迁移 000038）：两个配置方法
  **必须**带 `requestId`（同一操作人+设备+id 至多一条命令，重放 `200` 不再派发，改内容 `409 request_id_reused`）与
  `expectedStationId`（受理事务内按油站行 → 设备行加共享锁复核，不符 `409 device_station_changed`）；同一罐/整机至多一条未终态
  命令（部分唯一索引，`409 config_command_in_flight`）。开关是**两级**：部署级 `allowConfigCommands` 且设备级
  `devices.config_commands_enabled_at`（仅平台管理员经 `PUT /api/devices/:device/config-commands` 开关，换客户自动关闭）。命令
  INSERT 语句内再判访问规则、方法等级与设备级开关。迟到的 `cmdResult` 只写一次 `control.result_late` 审计，不改状态。
  整机设置镜像 `device_settings`（`GET /api/devices/:device/device-settings`，仅管理员）。**未真机验证。**
- 共享 fixture（`fixtures/protocol/v1/`）两端共用一份：Android 单测直接读它，Cloud CI 与 Android CI 都校验
  `MANIFEST.json`。改 fixture 要在同一个 PR 里让两端测试都通过；没有分支保护，CI 红了不会阻止合并，见 open-items §C-1。
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
- `/api/oil-in-out` 与 `/api/alarms` 读业务库（#29），支持可选的 `tank`（两者）与 `direction`（出入库）筛选，先筛选后分页，
  指定未授权罐为 `403 tank_not_authorized`（#84，[契约](docs/records-filter-contract.md)）。设备端 JSON 改动（zhongchengyeweiyi#39，已随 PR #38 合并，#39 于 2026-11-02 关闭）已就位，
  但**未真机验证**。旧固件上报的
  `DetailInoutRecord.toString()` 解析不了，此时接口返回 502 与 `STOCK_EVENT_FORMAT_UNSUPPORTED`，
  不再以 200 + 空数组冒充成功；两条路径都返回 `{rows, parsed, unparsable, legacySkipped, …}`。
  升级固件**不会**转换 ThingsBoard 里已有的旧记录，因此车队升级后必须设置
  `stock.legacyFormatBefore`，否则回看历史的查询会永久 502。该分类依赖设备把生成时刻写进信封
  `ts` 且补投原样保留——这是协议硬要求，见 `docs/protocol-v1-stock-event.md` §3 与 `deploy/README.md`。
- 小程序与云端 REST 的契约自 PR #11 起曾全面不兼容（行内字段、已删端点、`/api/alarms` 的信封
  形状）；小程序已按 V1 重写并经负责人确认（2026-09-04），`/api/alarms` 仍刻意保持裸数组。清单见
  `docs/protocol-v1-open-items.md` §D。
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
