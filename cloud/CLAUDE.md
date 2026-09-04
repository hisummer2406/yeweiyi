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
ThingsBoard 保存遥测数据。

```text
Android 设备 <-> ThingsBoard 原生 MQTT <-> Go 后端 <-> PostgreSQL / Redis <-> 客户端
```

- 不要引入 EMQX、Alink/Paho 兼容层、第二个 MQTT 客户端或本地遥测库。
- 不要直接访问 ThingsBoard 数据库；只能使用其公开 REST/WebSocket API。
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
internal/protocol/    设备协议 V1 契约
internal/tbclient/    唯一的 ThingsBoard REST/WebSocket 网关
internal/devicesim/   设备侧参考实现
internal/control/     控制状态机、授权、审计
internal/ingest/      ThingsBoard 遥测订阅
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
5. 不得把遥测复制到业务数据库；ThingsBoard 是遥测唯一事实来源。
6. 必须先授权、后检查设备是否存在，避免设备名枚举。
7. 设备配对密钥必须与 ThingsBoard/MQTT 设备凭据隔离。
8. 仅使用 ThingsBoard CE 功能，且部署必须支持完全离线环境。

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

- 协议 V1 尚未冻结。做协议工作前先读 `docs/protocol-v1-reconciliation.md`；当前 Android
  实现的遥测字段形状仍不兼容。
- 单位以及 `config.setTank` / `config.setProbe` 的参数形状需产品和设备端负责人决定；
  不得单方面冻结或扩展。
- 多租户尚未完成；当前授权模型仅为角色加 `user_devices` 绑定。
- `/api/oil-in-out` 依赖尚未合并的设备端 JSON 改动；真实设备记录会被跳过而非解析。
- OTA、生产 MQTT TLS、租户隔离与容量结论都必须在真实环境验证后才能宣称完成。

## 延伸阅读

- `docs/protocol-v1.md`：协议契约与开放问题。
- `docs/protocol-v1-reconciliation.md`：跨仓库兼容状态。
- `docs/thingsboard-ce-baseline.md`：平台和部署基线。
- `deploy/README.md`：离线部署、备份和恢复。
- `docs/engineering-journal.md`：归档的设计理由与评审历史。
