# yeweiyi-cloud

安卓液位仪（`com.sansen.yeweiyi`，油罐监控设备）的云端监控与远程控制后台。

架构为 **ThingsBoard CE 单栈**：设备只连接 ThingsBoard 开源社区版，Go 后台通过 ThingsBoard 的
REST / WebSocket 接口读取数据、下发 RPC，自身只承载行业业务、多租户权限、控制状态机和审计。

```text
Android 设备
    ⇄ ThingsBoard 原生 MQTT
ThingsBoard CE          设备接入 / 凭据 / 遥测 / 属性 / 告警 / RPC / OTA
    ⇄ REST / WebSocket
Go 业务后台（本仓库）    业务 / 权限 / 控制状态机 / 审计
    ⇄
PostgreSQL / Redis + 固定监控 Web
```

**不引入 EMQX，不建设双 MQTT 栈，不保留 Alink / Paho 业务兼容通道。**

> 产品背景、数据模型来源、已知缺口和待决产品问题见 [`CLAUDE.md`](./CLAUDE.md)。
> 架构基线与职责边界见 [`docs/thingsboard-ce-baseline.md`](./docs/thingsboard-ce-baseline.md)。
> 设备协议见 [`docs/protocol-v1.md`](./docs/protocol-v1.md)。

## 项目边界

- **属于本项目**：ThingsBoard adapter、行业业务、多租户与设备授权、控制命令状态机与审计、
  面向 Web 的 REST/WebSocket API。
- **不属于本项目**：小程序前端（`yeweiyi-miniapp` 仓库）、设备端代码（`zhongchengyeweiyi` 仓库）、
  局域网 PC 监控（需求A，设备自带 HTTP 服务，无需后端）。

## 技术栈

- 设备接入平台：**ThingsBoard CE 4.2.1.1**（Apache 2.0，自托管，可完全离线）
- Web/API：`gin` + `gorilla/websocket`
- 数据库：PostgreSQL + `sqlc` + `golang-migrate`
- 队列：`riverqueue/river`（Postgres-backed，与业务写入同事务入队）
- 缓存：Redis
- 鉴权：JWT（`golang-jwt/jwt`）+ bcrypt
- 部署：阿里云 ECS（公有云）/ 客户内网（完全离线私有化），同一套代码与部署模板

## 项目结构

```
internal/protocol/    # 设备协议 V1 的唯一真源（遥测/事件/属性/RPC 的线格式）
internal/tbclient/    # ThingsBoard adapter：REST + WebSocket，不直连其数据库
internal/devicesim/   # 设备侧参考实现，测试在进程内驱动它
cmd/tbdevicesim/      # 模拟设备的命令行封装（provision / run）
fixtures/protocol/v1/ # 跨仓库共享的协议 fixture，Android 侧用同一批文件做契约测试
internal/api/         # REST：路由、鉴权、业务查询
internal/queue/       # 写回队列
internal/store/db/    # sqlc 生成的查询层
db/migrations/        # golang-migrate schema
docs/                 # 协议与架构基线文档

internal/mqtt/        # 旧 Alink/Paho 栈，待 issue #10 删除（不属于当前架构）
cmd/devicesim/        # 同上
dev/mosquitto/        # 同上
```

## 本地开发

```bash
docker compose up -d                                    # Postgres / Redis
docker compose -f docker-compose.thingsboard.yml up -d   # ThingsBoard CE（首启装 schema，约 2–4 分钟）

# 控制台 http://localhost:8081  （tenant@thingsboard.org / tenant）
# 设备 MQTT tcp://localhost:1884

go build ./... && go vet ./...
go test ./internal/protocol/     # 协议契约测试，不需要任何容器
go test ./internal/tbclient/     # 端到端验收，需要 ThingsBoard；未启动时自动跳过

# 开通并运行一台模拟设备
go run ./cmd/tbdevicesim provision -name tank-farm-01
go run ./cmd/tbdevicesim run -token <token> -tanks 2 -interval 10s -alarm-after 15s
```

## 现状

- **已完成**：ThingsBoard CE 单栈端到端验证（遥测上行、即时报警、属性双向、RPC→ACK→最终结果、
  幂等、有效期、离线与故障区分），全部在真实容器上跑通，并在完全断网的内部网络中复验通过。
  设备协议 V1 已实现并由共享 fixture 的双向契约测试固定。
- **进行中**：协议 V1 尚未冻结，待与 Android 负责人共同确认单位等条目
  （见 `docs/protocol-v1.md` 第 12 节）。
- **未开始**：Go 业务后台向单栈迁移（issue #10）、多租户模型、固定监控 Web、离线私有化交付包。
  旧 Alink/Paho 栈仍在仓库中，删除清单见
  [`docs/thingsboard-ce-baseline.md`](./docs/thingsboard-ce-baseline.md) 第 6 节。
