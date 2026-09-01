# 离线部署与运维手册

面向**完全断网内网**的安装、备份、恢复与排障。GitHub issue #2 的交付物。

本目录既是仓库里的源文件，也是离线包解开后的根目录 —— 手册里出现的路径两种场景下都成立。

---

## 组件与端口

| 组件 | 镜像 | 端口（宿主机 → 容器） | 用途 |
|---|---|---|---|
| ThingsBoard CE | `thingsboard/tb-postgres:4.2.1.1` | `8081 → 9090` | 控制台 / REST / WebSocket |
| | | `1884 → 1883` | 设备 MQTT（明文） |
| 业务 PostgreSQL | `postgres:16` | `5432` | 命令、审计、用户、设备绑定 |
| Redis | `redis:7` | `6379` | 报警实时扇出 |
| Go 后台 | 本仓库构建 | `8080` | 业务 REST / WebSocket |

**对外只需开放 8080（业务 API）与 1884/8883（设备接入）。** 8081 是 ThingsBoard 控制台，
仅供运维调试，不应暴露给终端用户；5432 与 6379 不应离开本机。

> ⚠️ `8883`（MQTT/TLS）**尚未配置**，见文末“尚未完成”。当前设备接入为明文 1884。

---

## 安装（内网）

```bash
tar -xzf yeweiyi-offline-<版本>.tar.gz && cd yeweiyi-offline-<版本>

./preflight.sh          # 端口、内存、磁盘、Docker 版本；只读不改
./offline-load.sh       # 校验 SHA256SUMS 后导入镜像，全程不联网

# 选一档规格（见 profiles/）
docker compose -f docker-compose.yml -f docker-compose.thingsboard.yml \
               -f profiles/small.yml up -d

# ThingsBoard 首次启动要装 schema，2–4 分钟。等它就绪：
until curl -sf http://localhost:8081/login >/dev/null; do sleep 5; done

# 业务库迁移
export DATABASE_URL="postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
docker run --rm --network host -v "$PWD/migrations:/m" migrate/migrate \
  -path=/m -database "$DATABASE_URL" up

cp config.example.yaml config.yaml     # 按需修改，密钥走环境变量
JWT_SECRET=<强随机值> ./bin/server -config config.yaml &

./bin/smoke -api http://localhost:8080   # 冒烟测试，必须全绿才算装完
```

冒烟测试覆盖：ThingsBoard 可达与鉴权、Go 后台健康检查、设备开通与凭据签发、MQTT 接入、
遥测上行与读回、即时报警、控制命令 RPC→ACK→异步结果。它会自建一台临时设备并在结束时删除，
**可以对生产环境运行**；用的控制方法是 `alarm.mute`（非物理动作），不会让现场设备动作。

### 首次登录后必做

ThingsBoard 出厂账号是公开默认值，装完立即改：

| 账号 | 默认口令 | 处置 |
|---|---|---|
| `sysadmin@thingsboard.org` | `sysadmin` | 改口令 |
| `tenant@thingsboard.org` | `tenant` | 改口令，并同步更新 `config.yaml` 的 `thingsboard.password` |
| `customer@thingsboard.org` | `customer` | 本项目不使用，建议禁用 |

ThingsBoard 还自带若干演示设备（`Test Device A1`、`Thermostat T1` 等），可一并删除。

---

## 资源规格

| 档位 | 设备量 | 整机建议 | ThingsBoard 堆 | 文件 |
|---|---|---|---|---|
| 开发 | — | 8 GB / 4 核 | 1–2 GB | `profiles/dev.yml` |
| 小型私有化 | ≤ 100 | 8 GB / 4 核 / SSD 100 GB | 2–3 GB | `profiles/small.yml` |
| 公有云 | 1,000 → 10,000 | 16 GB / 8 核起 | 4–8 GB | `profiles/cloud.yml` |

依据：ThingsBoard 官方单节点 + PostgreSQL 后端最低 4 GB；本项目在同一台机器上还要跑业务
Postgres、Redis 和 Go 后台。

100 台 × 2 罐 × 26 数据点 / 30 秒 ≈ **87 数据点/秒** —— 这个量级瓶颈在磁盘持续写入而非 CPU，
优先给 SSD。**公有云一档尚未压测**（issue #5），其数字是按速率推算的起点而非实测结论；到
10,000 台时单节点大概率不够，届时要走 ThingsBoard 集群（Kafka + 多传输节点），那是另一套
部署形态，不是把堆调大的事。

---

## 数据卷与删除行为

| 卷 | 内容 | 删掉会怎样 |
|---|---|---|
| `thingsboard_data` | ThingsBoard 内置 PostgreSQL：**设备、凭据、遥测、属性、规则链** | 全部设备与历史数据丢失，所有设备需重新开通并换新凭据 |
| `thingsboard_logs` | ThingsBoard 日志 | 只丢日志，安全 |
| `postgres_data` | 业务库：**控制命令、审计、用户、设备绑定** | 审计记录丢失且不可再生 |
| `redis_data` | 报警扇出缓存 | **安全**，Redis 在本架构中只作缓存，无持久状态 |

```bash
docker compose down          # 停容器，保留卷 —— 日常停机用这个
docker compose down -v       # 停容器并删除卷 —— 会丢数据，仅用于重装
```

`docker compose down -v` 会同时删掉 `thingsboard_data` 与 `postgres_data`，即上表前两行。
**执行前先跑 `./backup.sh`。**

---

## 备份

```bash
./backup.sh [输出目录]        # 默认 ../backups
```

产出 `business.sql.gz`、`thingsboard.sql.gz`、`manifest.txt`、`SHA256SUMS`。

**为什么是两个数据库**：ThingsBoard 的 `tb-postgres` 镜像**内置了自己的 PostgreSQL**（容器内
`/data/db`），与业务 Postgres 是两个互不相干的实例。只备份其中一个，恢复后会得到一个
“设备在、业务数据没了”或反之的半套系统。

备份是**逻辑转储**，不含容器卷本身，也不含 WAL 与日志 —— 这些不需要备份。

建议每日一次并异地留存；`manifest.txt` 记录了 ThingsBoard 镜像版本与业务库 schema 版本，
恢复时据此判断目标环境是否匹配。

## 恢复

```bash
./restore.sh backups/20260902-003000
```

先校验 SHA256SUMS，再要求输入 `yes` 确认覆盖。若备份的 ThingsBoard 版本与当前不一致会额外
警告 —— 跨大版本恢复其内置库可能导致 schema 不匹配，且往往很久以后才以“某个功能不工作”暴露。
恢复 ThingsBoard 内置库前会重启容器，因为它持有连接并缓存实体，边写库边跑会得到自相矛盾的状态。

恢复后**务必跑一次 `./bin/smoke`**。

---

## 离线包的制作（在有网的机器上）

```bash
./deploy/offline-bundle.sh          # 产出 dist/yeweiyi-offline-<版本>.tar.gz
```

按 `images.txt` 拉取镜像并**逐个校验摘要**后导出；摘要不符即中止 —— tag 可以被重新推送指向
不同内容，摘要不符意味着打进包里的东西和清单声称的不是同一个。同时静态编译
`server`/`smoke`/`tbdevicesim` 三个 Linux 二进制，内网侧不需要 Go 工具链。

把 tar 包与脚本末尾打印的 SHA256 **分开传递**（例如摘要走纸质或电话），内网侧用
`offline-load.sh` 校验后导入。

---

## 无公网依赖

- ThingsBoard CE 为 Apache 2.0，**无在线许可证校验**，许可证全文见 `LICENSE-thingsboard.txt`；
- 未使用 ThingsBoard PE / Cloud、云 API、公网 DNS、短信或外部认证；
- 已实测：把 ThingsBoard 移入无出网的 Docker 内部网络，并在同一网络内运行交叉编译的端到端
  测试（两侧均无互联网），全部通过。方法见 `docs/thingsboard-ce-baseline.md` 第 1 节。

镜像分发本身需要离线导入，即本目录的 `offline-bundle.sh` / `offline-load.sh`。

---

## 排障

| 现象 | 排查 |
|---|---|
| ThingsBoard 起不来 | `docker logs yeweiyi-thingsboard`。首启装 schema 需 2–4 分钟，期间健康检查为 unhealthy 属正常 |
| 设备连不上 MQTT | 确认 access token 正确（token 放 MQTT **username**，password 留空）；`docker logs` 看是否被拒 |
| 业务 API 返回 503 | ThingsBoard 不可达。`curl http://localhost:8081/login` 自查；503 表示平台故障而非请求有误 |
| 控制命令返回 409 | 物理动作总闸关闭。这是**默认且正确**的状态，设备端联锁验证完成前不要打开 |
| 遥测查得到但趋势图为空 | 检查设备是否发的是扁平键；嵌套 JSON 的聚合会**静默返回 0**，见 `docs/protocol-v1-reconciliation.md` §A |
| 冒烟测试卡在“遥测读回” | ThingsBoard 异步落盘，冒烟测试已内置 30 秒重试；若持续失败查其磁盘是否写满 |

日志导出：`docker logs yeweiyi-thingsboard > tb.log`；业务后台日志走 stdout，由 systemd
或容器运行时收集。审计记录在业务库 `audit_log` 表，长期保留，不随遥测清理。

---

## 尚未完成

诚实列出，不要以为这些已经就绪：

- **MQTT/TLS（8883）未配置。** 当前设备接入为明文 1884。内网部署下风险较低但仍应补齐，
  离线环境无公网 CA，需自签证书并预置到设备。
- **规则链与设备配置模板未导出。** 目前没有配置任何自定义规则链 —— 报警是作为遥测事件流转的，
  未映射为 ThingsBoard 原生 Alarm 实体。等那部分做了再导出入库。
- **离线升级与回滚演练未做**（issue #6）。本手册覆盖安装、备份、恢复，不覆盖升级。
- **公有云档位未压测**（issue #5）。
- **`preflight.sh` 只在 macOS 与 Linux 上试过**，未在国产化操作系统上验证。
