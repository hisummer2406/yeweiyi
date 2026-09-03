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

> **开发者与 AI 智能体请先读 [`CLAUDE.md`](./CLAUDE.md)**（`AGENTS.md` 是指向它的符号链接，
> Codex / Claude Code / Cursor 读到的是同一份）。第一部分是当前状态的操作指南——架构、命令、
> 目录、不可破坏的约束、代码约定；第二部分是工程日志，记录决策的来龙去脉（含已被推翻的决策）。
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
cmd/smoke/            # 部署冒烟测试（编译为二进制，随离线包分发）
cmd/tbconfig/         # ThingsBoard 侧配置的版本化：设备模板/规则链基线的 apply 与 export
cmd/loadtest/         # 可重复的容量压测工具（见 docs/capacity-test.md）
deploy/               # 离线交付：镜像清单、打包/导入、预检、备份/恢复、规格档位、部署手册
fixtures/protocol/v1/ # 跨仓库共享的协议 fixture，Android 侧用同一批文件做契约测试
internal/config/      # 配置加载：默认值 → config.yaml → 环境变量
internal/control/     # 控制命令状态机、授权、审计
internal/ingest/      # 每设备一条 ThingsBoard 遥测订阅 → 业务层（不落遥测）
internal/api/         # REST：路由、鉴权、控制与审计端点、业务查询
internal/store/db/    # sqlc 生成的查询层
db/migrations/        # golang-migrate schema
docs/                 # 协议与架构基线文档

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

- **已完成**：ThingsBoard CE 单栈端到端验证；设备协议 V1（草案，由共享 fixture 的双向契约测试固定）；
  远程控制状态机、授权与审计；遥测摄取（cmdResult 回流 + 报警扇出）；控制与审计的 REST 端点；
  `cmd/server` 已切到单栈——**不需要任何 productKey / Alink Topic / broker 即可启动**。
- **进行中**：协议 V1 尚未冻结，待与 Android 负责人确认单位等条目（`docs/protocol-v1.md` 第 12 节）。
- **读端点已全部迁移**：`/api/alarms`、`/api/oil-in-out`、`/api/devices/:device/telemetry/latest`
  读透 ThingsBoard；`/api/oilcan/latest` 与 `/api/shifts` 直接删除（交班在 V1 两侧都没有上行通道，
  留一个永远返回空的端点比没有更糟）。`/api/oil-in-out` 依赖一个尚未合并的设备侧改动
  （[zhongchengyeweiyi#39](https://github.com/hisummer2406/zhongchengyeweiyi/issues/39)）：设备已在
  上报 `stockEvent`，但发的是 Java 调试字符串而非 JSON。
- **旧 Alink 栈已于 2026-09-02 全部删除**：`internal/mqtt`、`cmd/devicesim`、`internal/queue`、
  `internal/partitions`、mosquitto 服务，以及五张遗留表（migration `000011`，删除前确认均为空表）。
- **离线部署基线已建立**（`deploy/`）：镜像按摘要固定、离线打包与导入、预检、备份/恢复、
  三档资源规格、冒烟测试二进制。已实测**断网重启后模拟设备仍可正常接入**。
  **MQTT/TLS 已支持**（自签 CA + 8883，明文端口收回环回；`deploy/gen-certs.sh`）。
  双向 TLS、证书续期自动化与离线升级尚未完成，见 `deploy/README.md` 末节。
- **未开始**：完整多租户模型；固定监控 Web；离线升级与回滚演练；记录修正功能（旧的写回端点随队列
  一并删除，若产品仍需要，应基于 `internal/control` 重新设计而非恢复）。

## 运行

```bash
cp config.example.yaml config.yaml     # 按需修改
JWT_SECRET=dev-secret go run ./cmd/server -config config.yaml
```

不带 `-config` 也能跑（用内置默认值 + 环境变量），本地开发通常这样：

```bash
JWT_SECRET=dev-secret \
TB_BASE_URL=http://localhost:8081 TB_USERNAME=tenant@thingsboard.org TB_PASSWORD=tenant \
go run ./cmd/server
```

配置优先级：**内置默认值 → 配置文件 → 环境变量**（环境变量最高，便于容器覆盖单项）。
所有时间写成 `"30s"` / `"5m"` / `"24h"`，不接受裸数字。拼错的键会导致启动失败而非被静默忽略。
完整键说明与环境变量对照见 [`config.example.yaml`](./config.example.yaml)。

⚠️ 密钥（`JWT_SECRET`、数据库与 ThingsBoard 密码）建议走环境变量而非写进配置文件——
配置文件会进备份、可能进 git。

⚠️ 物理动作（启泵/开阀/重启）默认关闭。开启需显式设置 `control.allowPhysicalCommands: true`
或 `ALLOW_PHYSICAL_COMMANDS=true`，**且设备端联锁验证完成之前不要打开**。
