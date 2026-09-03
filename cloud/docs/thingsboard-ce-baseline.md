# ThingsBoard CE 单栈基线与职责边界

GitHub issue #9 的交付物。记录固定的 ThingsBoard CE 版本、四个组件的职责划分、唯一的数据流、Go 侧
需要的 adapter 清单，以及旧双栈代码的待删清单。

**本文所有"已验证"结论均来自对真实 ThingsBoard CE 容器的实际运行**，验证代码是
`internal/tbclient/e2e_test.go`（11 个用例，全部通过），不是文档研读或搜索结论。上一轮
Aliyun IoT Platform 的教训就是搜索结论与实际不符——这次先跑再写。

---

## 1. 版本与许可证基线

| 项目 | 取值 |
|---|---|
| 产品 | ThingsBoard **Community Edition** |
| 版本 | **4.2.1.1**（2025-12-23 发布，当时的最新稳定版） |
| 镜像 | `thingsboard/tb-postgres:4.2.1.1` |
| 许可证 | **Apache License 2.0** |
| 上游源码 | https://github.com/thingsboard/thingsboard |
| 依赖的外部服务 | **无**。无在线许可证、无云 API、无公网 DNS 依赖 |
| 内置存储 | PostgreSQL（镜像内自带）。**不引入 Cassandra** |
| 队列 | `TB_QUEUE_TYPE=in-memory`，单节点无需 Kafka |

**版本号在 `docker-compose.thingsboard.yml` 中硬编码，绝不使用 `latest`。** ThingsBoard 首次启动新
版本时会就地迁移自己的 schema，用浮动标签会在开发者下次 pull 时静默升级其数据卷。

**未使用任何 PE 专属能力。** 本文和 `internal/tbclient` 用到的全部接口——设备 CRUD、设备凭据、
遥测读写、属性读写、双向 RPC、遥测 WebSocket——都在 CE 范围内，已在上述 Apache 2.0 镜像上实测通过。

### 断网（完全离线）验证

issue #9 要求"核心流程在断网环境中正常运行"。**已实测**，方法如下：

1. 建立 `--internal` 的 Docker 网络（无出网路由），把 ThingsBoard 容器移入，断开其默认桥接网络；
2. 用一个挂在同一内部网络上的旁路容器确认该网络确实无法访问外网；
3. 把 e2e 测试**交叉编译成 Linux 静态二进制**，在**同一内部网络内**的容器里运行——
   于是 ThingsBoard 与测试客户端**双方都没有互联网**；
4. 全部 11 个 e2e 用例通过。

（不能只靠宿主机 `curl` 验证：断开桥接网络会同时断掉端口映射，那样测的其实是"连不上"。）

镜像分发本身仍需离线导入方案（`docker save` / `docker load`），属于 issue #1 的离线交付任务，
不在本 Issue 范围。

---

## 2. 组件职责表

| 能力 | ThingsBoard CE | Go 业务后台 | 业务 PostgreSQL | Redis |
|---|:---:|:---:|:---:|:---:|
| 设备 MQTT/TLS 接入 | ✅ | ❌ | ❌ | ❌ |
| 每设备独立凭据签发 / 吊销 | ✅ | 调用其 API | ❌ | ❌ |
| 在线 / 离线状态 | ✅ | 读取 | ❌ | ❌ |
| **原始遥测存储（30 秒快照）** | ✅ **唯一** | ❌ | ❌ **不复制** | 可缓存最新快照 |
| 遥测历史 / 趋势查询 | ✅ | 代理转发 | ❌ | ❌ |
| 属性（客户端 / 共享） | ✅ | 读写 | 阈值权威副本 | ❌ |
| 双向 RPC 传输 | ✅ | 发起 | ❌ | ❌ |
| OTA 包存储与下发进度 | ✅ | ❌ | ❌ | ❌ |
| 平台 / 客户 / 用户 / 角色 | ❌ | ✅ | ✅ | ❌ |
| 设备授权（含扫码绑定） | ❌ | ✅ | ✅ | ❌ |
| **控制命令状态机与幂等** | ❌ | ✅ | ✅ | ❌ |
| **审计日志（长期保留）** | ❌ | ✅ | ✅ | ❌ |
| 出入库 / 交班 / 记录修正 | ❌ | ✅ | ✅ | ❌ |
| 报警处置（确认 / 派单） | 产生 | 处置 | ✅ | ❌ |
| 面向 Web 的 REST / WebSocket | ❌ | ✅ | — | 扇出 |

### 数据边界（一句话版）

> **时序数据只存 ThingsBoard；业务记录、命令、审计只存业务库。两边都不复制对方的东西。**

具体地：

- **不要**把 30 秒快照逐条写进业务 PostgreSQL。这是旧设计的做法（`alarms` / `oil_in_out` 分区表
  那一套），在单栈架构下会变成第二份时序存储。
- **要**写进业务库的，只有：控制命令行及其状态流转、审计记录、出入库/交班等业务记录、
  报警的**处置状态**（谁在何时确认的），以及用户/客户/授权。
- 报警本身由 ThingsBoard 产生和存储；业务库只存指向它的引用与处置信息。

---

## 3. 唯一的四条数据流

```
① 遥测流（30 秒快照）
   设备 --MQTT/QoS1--> ThingsBoard(ts_kv) --WS 订阅/REST--> Go --> Web

② 即时事件流（报警产生/恢复、命令结果）
   设备 --MQTT 立即--> ThingsBoard --WS 推送--> Go --> Web
   实测延迟 29 ms（产生）/ 66 ms（恢复）

③ 属性流
   设备 --客户端属性--> ThingsBoard --REST--> Go
   Go --REST 共享属性--> ThingsBoard --下发--> 设备

④ 控制流（RPC + ACK + 最终结果）
   Go --POST /api/rpc/twoway--> ThingsBoard --MQTT--> 设备
   设备 --ACK(RPC 响应)--> ThingsBoard --HTTP 响应--> Go      [received/rejected]
   设备 --cmdResult(遥测)--> ThingsBoard --WS--> Go           [succeeded/failed]
```

**没有第五条。** 不存在第二套 MQTT 通道，不存在 Go 直连 broker 的路径，不存在 Go 读写 ThingsBoard
内部数据库的路径。

---

## 4. Go 侧 adapter 清单（`internal/tbclient`）

全部经由 ThingsBoard 公开 REST / WebSocket API，**不直接访问其数据库**。

| 方法 | ThingsBoard 接口 | 用途 |
|---|---|---|
| `Login` / `Token` | `POST /api/auth/login` | 租户管理员鉴权，401 自动重登 |
| `GetDeviceByName` | `GET /api/tenant/devices?deviceName=` | 按业务 deviceName 查设备 |
| `CreateDevice` / `EnsureDevice` | `POST /api/device` | 开通（幂等，含并发建重试） |
| `GetDeviceCredentials` | `GET /api/device/{id}/credentials` | 取一机一密 access token |
| `DeleteDevice` | `DELETE /api/device/{id}` | 退役 / 测试清理 |
| `LatestTimeseries` / `LatestValues` | `GET .../values/timeseries` | 读最新快照 |
| `Timeseries` | `GET .../values/timeseries?startTs&endTs` | 历史趋势 |
| `Attributes` | `GET .../values/attributes/{scope}` | 读客户端 / 共享 / 服务端属性 |
| `SetSharedAttributes` | `POST .../SHARED_SCOPE` | 下发配置 |
| `SendCommand` | `POST /api/rpc/twoway/{deviceId}` | 发控制命令，返回 ACK |
| `SubscribeTelemetry` | `WS /api/ws` | 实时订阅遥测与即时事件 |

### 实测确认的两个 API 行为（会坑人的）

1. **遥测值一律被字符串化。** 通过 REST 或 WebSocket 读回时，数字是 `"1842.5"`，布尔是 `"true"`，
   JSON 对象是一个 JSON **字符串**。只按设备侧形状写的解析器读 REST 必然失败。
   `internal/protocol` 的 `toFloat` / `toBool` / `decodeJSONValue` 同时接受两种形状，
   `fixtures/protocol/v1/telemetry_snapshot_rest.json` 固定这一点。
2. **属性值保留原类型**，不被字符串化。同一个平台的两个 API 行为不一致，已在
   `TestUplink_ClientAttributesReachBackend` 中固定。

### 降级行为（issue #10 要求）

`tbclient.Unavailable(err)` 区分两类错误，因为它们的处置完全不同：

| 情况 | `Unavailable` | 含义 |
|---|:---:|---|
| 连接被拒 / 超时 / TLS 失败 | `true` | 平台故障，API 应返回 503，需运维介入 |
| ThingsBoard 5xx | `true` | 同上 |
| `ErrNotFound`（设备不存在） | `false` | 正常业务状态 |
| `ErrDeviceOffline`（设备未 ACK） | `false` | 正常运行状态，稍后重试 |

> 这条区分最初写错了：`ErrDeviceOffline` 不是 `*tbclient.Error`，落进了兜底分支被当成平台故障。
> `TestControl_OfflineDeviceIsReportedDistinctly` 抓到了它。保留这条记录，因为把"设备离线"报成
> "平台挂了"会直接导致误报警。

---

## 5. e2e 验收对照

`go test ./internal/tbclient/`，需先 `docker compose -f docker-compose.thingsboard.yml up -d`。
未启动 ThingsBoard 时这些用例**跳过**（并提示启动命令），不会让 `go test ./...` 变红。

| issue #9 验收项 | 对应用例 | 结果 |
|---|---|:---:|
| 遥测 → ThingsBoard → Go 读取闭环 | `TestUplink_SnapshotReachesBackend` | ✅ |
| Go 发 RPC → 设备 ACK → Go 得最终结果闭环 | `TestControl_FullCommandLifecycle` | ✅ |
| 告警产生与恢复不等 30 秒周期 | `TestAlarm_ArrivesWithoutWaitingForSnapshot` | ✅ 29 ms / 66 ms |
| 能力全部属于开源 CE | 全部用例跑在 Apache 2.0 镜像上 | ✅ |
| 核心流程断网可用 | 见第 1 节的内部网络验证 | ✅ |
| 架构中无 EMQX / 第二套 MQTT 通道 | 新代码路径不含任何 broker 客户端配置 | ✅ |

附加覆盖（非验收项但属同一批）：客户端属性上行、共享属性下行、联锁拒绝、
接受后执行失败、重复投递幂等、有效期拒绝、设备离线与平台故障的区分、未知设备 404。

### 本轮**未**验证的部分

- **MQTT over TLS（8883）**。开发环境用明文 1884，生产证书链未验证。
- **OTA 实际下发**。ThingsBoard 侧的 OTA 功能未接入验证；设备侧接收/校验/刷写逻辑本就不存在。
- **多租户隔离**。ThingsBoard 侧 tenant/customer 的实际隔离行为未验证，
  Go 侧租户模型属于 issue #10。
- **容量与压力**。第 8 节的数据点估算是算术推导，未做压测。
- **ThingsBoard 规则链**把 `alarm` 遥测转成 ThingsBoard 原生 Alarm 实体——本轮报警是作为遥测事件
  流转的，规则链映射未配置。

---

## 5b. 控制状态机与数据边界（issue #10 首批落地）

`internal/control` + `internal/ingest`（migration `000010`）实现了 issue #1 的远程控制状态机与审计。

**状态流转全部由 SQL 的 `WHERE status IN (...)` 守卫**，不是靠应用层判断——迟到的 ACK、重复投递的
`cmdResult`、以及过期清扫之后才到达的结果，都无法改写已进入终态的记录。

三条容易做反的判断，已分别用测试固定：

| 情况 | 做法 | 为什么 |
|---|---|---|
| 设备未 ACK（离线/超时） | 记为 `sent`，**不记 failed** | "设备没回话" ≠ "设备没动作"。它可能已经收到命令、启了泵、然后掉线。记成 failed 等于告诉操作员泵没启动，而它可能正在转 |
| ThingsBoard 不可用 | 留在 `pending` | 命令根本没交出去 |
| 设备明确拒绝 | 记 `rejected`（终态），**返回值不是 Go error** | 拒绝是操作员必须看到的信息，不是传输故障 |

**物理动作默认关闭。** `Config.AllowPhysicalCommands` 默认 `false`，即"什么都不配"的部署无法启泵
开阀重启——因为设备端联锁尚未补齐、未经真机验证（issue #1）。这个闸门对**平台管理员同样生效**：
它约束的是未验证的硬件，不是信任级别。

**数据边界由测试强制**：`TestIngest_DoesNotPersistTelemetry` 会在 ingester 往业务库写入任何遥测行
时失败。遥测只存 ThingsBoard，业务库只存命令、审计和业务记录。

端到端实测（`internal/ingest`，真实 ThingsBoard + 真实模拟设备 + 真实 Postgres/Redis）：
命令走完 `pending → sent → received → succeeded`，其中最后一跳完全由 ingester 从设备异步上报的
`cmdResult` 驱动；报警经 设备→ThingsBoard→ingest→Redis 到达操作员用时 **30 ms**。

## 6. 旧双栈代码 —— 已于 2026-09-02 全部删除

issue #10 的结构性工作到此完成。**清单从"待删"变成"已删记录"。**

| 路径 | 处置 |
|---|---|
| `internal/mqtt/` | 已删除（Alink topic、paho 通配符订阅、handlers、poller） |
| `cmd/devicesim/` | 已删除，由 `cmd/tbdevicesim` 取代 |
| `internal/queue/` | 已删除（river 写回队列），由 `internal/control` 取代 |
| `internal/partitions/` | 已删除（随分区表一起） |
| `dev/mosquitto/` + `docker-compose.yml` 的 mosquitto 服务 | 已删除 |
| `internal/api/writeback.go` | 已删除 |
| `go.mod` 的 `riverqueue/river` | 已移除。`paho.mqtt.golang` **保留**——`internal/devicesim` 用它连 ThingsBoard 的 MQTT |
| 环境变量 `MQTT_*` / `WRITE_BACK_TTL_DAYS` | 已删除，配置项见 `config.example.yaml` |
| 表 `alarms` / `oil_in_out` / `commit_works` / `oil_can_sets` / `write_back_commands` | 已删除（migration `000011`） |

**删表前确认五张表均为空**——它们的摄取路径早已移除。down 迁移从 `000001` **程序化提取** DDL 而非
手抄（避免转录漏字），并写明它恢复的是结构而非数据。分区表显式 DROP 而非依赖 Postgres 级联：
sqlc 逐语句跟踪表，看不到 DROP 就会继续为分区生成模型——一批看起来能用的死类型。

**river 的四张表刻意保留**（`river_job` 等，migrations `000002`–`000007`）。在后续迁移里删掉它们、
而前面六个文件仍声称创建它们，会让迁移历史在两个方向上都不自洽；要清理应当把那六个文件一并退役，
那是独立的一次 schema 历史变更。

**副作用**：`go test ./...` 并行跑现在稳定了。此前长期存在的偶发失败（`internal/api` 与
`internal/queue` 互抢 river job、`internal/mqtt` 的 `TestClient_OnlineStatus` 时序敏感）完全位于被
删除的包中，随之消失。连续三次并行全量测试全绿，不再需要 `-p 1`。

## 7. 本地开发

```bash
# 启动 ThingsBoard CE（首次启动要装 schema，约 2–4 分钟）
docker compose -f docker-compose.thingsboard.yml up -d

# 控制台 http://localhost:8081   tenant@thingsboard.org / tenant
# 设备 MQTT tcp://localhost:1884

# 开通一台模拟设备并拿到它的 token
go run ./cmd/tbdevicesim provision -name tank-farm-01

# 跑起来：每 10 秒一条快照，15 秒后产生一次报警再恢复
go run ./cmd/tbdevicesim run -token <token> -tanks 2 -interval 10s -alarm-after 15s

# 端到端验收
go test ./internal/tbclient/ -v
```

`-policy reject` / `-policy fail` 可让模拟设备走联锁拒绝或"接受后执行失败"分支。

---

## 8. 端口

| 端口 | 用途 | 备注 |
|---|---|---|
| 8081 → 容器 9090 | ThingsBoard HTTP / REST / WebSocket | 避开 Go 后台默认的 8080 |
| 1884 → 容器 1883 | 设备 MQTT | 避开旧栈 mosquitto 占用的 1883；#10 删除后可改回 1883 |
| 8883 | 生产 MQTT/TLS | 尚未配置 |

CoAP 与 LwM2M 端口本项目不使用，刻意未发布。
