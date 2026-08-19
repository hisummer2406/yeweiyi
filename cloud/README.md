# yeweiyi-cloud

安卓液位仪（`com.sansen.yeweiyi`，油罐监控设备）需求B（PC 端 / 小程序远程数据监控）的云端后端。

设备本身跑在局域网内，通过阿里云 IoT（MQTT）上报数据；本项目是设备 MQTT 与小程序 / 远程 PC
客户端之间的中间层，把 MQTT 事件落库、缓存，再以 HTTPS REST/WebSocket 暴露给客户端使用，同时
支持客户端向设备下发配置的离线写回队列。

> 完整的产品背景、MQTT 契约细节、数据模型来源、已知缺陷和尚未决定的产品问题，见
> [`CLAUDE.md`](./CLAUDE.md)。这里只给出面向新读者的项目概览。

## 项目边界

- **属于本项目**：MQTT 接入（订阅设备事件、轮询实时快照）、REST/WebSocket API、写回队列、鉴权。
- **不属于本项目**：小程序前端（在 `yeweiyi-miniapp` 仓库）、设备端代码（在
  `zhongchengyeweiyi` 仓库）、局域网内的 PC 监控（需求A，设备自带 HTTP 服务，无需后端）。

## 技术栈

- Web/API：`gin` + `gorilla/websocket`
- MQTT：`eclipse/paho.mqtt.golang`，对接阿里云 IoT 标准 MQTT
- 数据库：PostgreSQL + `sqlc`（查询层）+ `golang-migrate`（迁移/分区）
- 写回队列：`riverqueue/river`（Postgres-backed，与业务写入同事务入队）
- 缓存：Redis（实时快照缓存、`/ws/alarms` 的 pub/sub）
- 鉴权：JWT（`golang-jwt/jwt`）+ bcrypt
- 部署：阿里云 ECS

## 项目结构

```
cmd/server/main.go   # 入口：装配 gin + pgxpool + redis + river + (可选) mqtt client/poller
internal/mqtt/       # MQTT 接入：topics 解析、payload、client、handlers、poller
internal/api/        # REST：路由、鉴权、alarms/oil-in-out/shifts、oilcan 快照读取
internal/queue/      # 写回队列：入队、pending→sent worker、TTL 清理
internal/store/db/   # sqlc 生成的查询层
db/migrations/       # golang-migrate schema（含 river 自身的迁移）
db/queries/          # sqlc 查询输入
dev/mosquitto/       # 本地开发用 Mosquitto broker 配置（模拟阿里云 IoT）
```

## 本地开发

```bash
docker compose up -d            # 启动本地 Postgres / Redis / Mosquitto

go build ./...                  # 编译
go vet ./...                    # 静态检查
sqlc generate                   # 由 db/migrations + db/queries 重新生成 internal/store/db

migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
        -path db/migrations up  # 执行迁移

go test ./...                   # internal/queue、internal/mqtt 有针对本地容器的集成测试

JWT_SECRET=... MQTT_BROKER_URL=tcp://localhost:1883 MQTT_PRODUCT_KEY=... go run ./cmd/server
                                 # 不设置 MQTT_BROKER_URL 则跳过 MQTT 接入
```

## 现状

尚未接入真实阿里云 IoT broker（凭证未下发）；`internal/mqtt` 的告警/上下线/实时快照摄取已跑通
端到端测试，但写回队列的 `sent → acked` 环节依赖的设备端"操作确认"消息语义还不存在。详细的
验证记录、已知缺口和产品待决问题见 [`CLAUDE.md`](./CLAUDE.md)。
