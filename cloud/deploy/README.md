# 离线部署与运维手册

面向**完全断网内网**的安装、备份、恢复与排障。GitHub issue #2 的交付物。

本目录既是仓库里的源文件，也是离线包解开后的根目录 —— 手册里出现的路径两种场景下都成立。

---

## 组件与端口

| 组件 | 镜像 | 端口（宿主机 → 容器） | 用途 |
|---|---|---|---|
| ThingsBoard CE | `thingsboard/tb-postgres:4.2.1.1` | `8081 → 9090` | 控制台 / REST / WebSocket |
| | | `8883` | 设备 MQTT over TLS（生产用） |
| | | `1884 → 1883` | 设备 MQTT 明文（启用 TLS 后仅绑环回） |
| 业务 PostgreSQL | `postgres:16` | `5432` | 命令、审计、用户、设备绑定 |
| Redis | `redis:7` | `6379` | 报警实时扇出 |
| Go 后台 | 本仓库构建 | `8080` | 业务 REST / WebSocket |

**对外只需开放 8080（业务 API）与 1884/8883（设备接入）。** 8081 是 ThingsBoard 控制台，
仅供运维调试，不应暴露给终端用户；5432 与 6379 不应离开本机。

启用 TLS 后设备走 `8883`，明文 `1884` 收回到环回地址（仅本机联调可用）。见下节。

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

# 业务库迁移。镜像 tag 必须与 images.txt/offline-load.sh 导入的那个完全一致——写 :latest
# 会在内网机器上直接找不到本地镜像（不联网，没有 latest 可拉），此前正是这个不一致导致
# 从零开始的离线安装无法完成迁移。
export DATABASE_URL="postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
docker run --rm --network host -v "$PWD/migrations:/m" migrate/migrate:v4.18.1 \
  -path=/m -database "$DATABASE_URL" up

# ThingsBoard 侧配置（设备模板）。幂等，可重复执行。
./bin/tbconfig apply

cp config.example.yaml config.yaml     # 按需修改，密钥走环境变量
JWT_SECRET=<强随机值> ./bin/server -config config.yaml &

./bin/smoke -api http://localhost:8080   # 冒烟测试，必须全绿才算装完
```

冒烟测试覆盖：ThingsBoard 可达与鉴权、Go 后台健康检查、设备开通与凭据签发、MQTT 接入、
遥测上行与读回、即时报警、控制命令 RPC→ACK→异步结果。它会自建一台临时设备并在结束时删除，
**可以对生产环境运行**；用的控制方法是 `alarm.mute`（非物理动作），不会让现场设备动作。

### 首次业务账号初始化

业务 API（Go 后台）与 ThingsBoard 是两套独立的身份体系。Go 后台的首次账号需要**首先创建**：

```bash
# 创建管理员账号（通过 SQL 直接写入业务库，因为还没有 API 可注册）
export DATABASE_URL="postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"

# 如果你有现成的创建用户工具，这里调用它；暂时没有官方工具，需手工或通过脚本执行 SQL：
docker run --rm -it --network host \
  -e PGPASSWORD=yeweiyi \
  postgres:16 psql -h localhost -U yeweiyi -d yeweiyi \
  -c "INSERT INTO users (username, password_hash) VALUES \
      ('admin', crypt('change-me-immediately', gen_salt('bf'))) \
      ON CONFLICT (username) DO NOTHING;"

# 用户首次登录时会通过 JWT 获取 token，密码在 API 服务侧设置。
# 详见 internal/store/db/migrations 中的 users 表定义。
```

**生产环境建议**：建立一个管理员初始化脚本或在配置管理工具（Ansible/Terraform）中自动化此步骤，
不要每次装机都手工跑 SQL。

### 首次登录后必做

ThingsBoard 出厂账号是公开默认值，装完立即改：

| 账号 | 默认口令 | 处置 |
|---|---|---|
| `sysadmin@thingsboard.org` | `sysadmin` | 改口令 |
| `tenant@thingsboard.org` | `tenant` | 改口令，并同步更新 `config.yaml` 的 `thingsboard.password` |
| `customer@thingsboard.org` | `customer` | 本项目不使用，建议禁用 |

ThingsBoard 还自带若干演示设备（`Test Device A1`、`Thermostat T1` 等），可一并删除。

### 服务托管与开机恢复

生产环境不能用 `&` 后台启动。需要确保：
- 进程异常退出时自动重启
- 开机时自动启动相关服务
- 日志被正确收集

**选项 A：Docker Compose 方式（推荐 Docker 部署）**

在前面 `up -d` 命令中已启用了容器重启策略（`docker-compose.yml` 中 `restart: unless-stopped`），
确保容器异常退出时自动恢复。若需要手动管理启停：

```bash
# 启动所有服务
docker compose -f docker-compose.yml -f docker-compose.thingsboard.yml \
               -f profiles/small.yml up -d

# 查看状态
docker compose ps

# 停止（保留数据卷）
docker compose down

# 在 systemd 中管理 Docker Compose 自身（可选）
# 参见下方 Option B 的 systemd 例子
```

**选项 B：systemd 服务（传统服务器部署）**

如果要在 systemd 中管理 Go 服务（Docker Compose 仍由 systemd 或 systemd-timer 管理）：

1. **编写 systemd 单元文件**

创建 `/etc/systemd/system/yeweiyi.service`：

```ini
[Unit]
Description=Yeweiyi Cloud Server
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
# 工作目录（离线包解开或项目目录）
WorkingDirectory=/path/to/yeweiyi-offline-xxx
# 环境变量：强凭据通过 .env 文件或 EnvironmentFile 注入
EnvironmentFile=/etc/yeweiyi/server.env
ExecStart=/path/to/yeweiyi-offline-xxx/bin/server -config config.yaml
Restart=always
RestartSec=5s
# 日志输出到 systemd journal（自动轮转）
StandardOutput=journal
StandardError=journal
# 安全选项（可选）
User=yeweiyi
PrivateTmp=yes
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

2. **创建凭据文件**（不提交到 git）

`/etc/yeweiyi/server.env`（权限 600）：

```bash
JWT_SECRET=your-strong-secret-here
TB_PASSWORD=tenant-password-here
DATABASE_URL=postgres://yeweiyi:strongpass@localhost:5432/yeweiyi?sslmode=disable
REDIS_ADDR=localhost:6379
```

3. **启用和启动**

```bash
sudo systemctl daemon-reload
sudo systemctl enable yeweiyi          # 开机自启
sudo systemctl start yeweiyi           # 立即启动
sudo systemctl status yeweiyi          # 查看状态
sudo journalctl -u yeweiyi -f          # 实时日志
```

4. **Docker Compose 的 systemd 管理（可选）**

若要 systemd 托管 Docker Compose，参考 [Compose Service Manager](https://docs.docker.com/compose/production/)
或使用简单的 timer：

```ini
[Unit]
Description=Yeweiyi Docker Compose Stack
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/path/to/yeweiyi-offline-xxx
ExecStart=/usr/bin/docker-compose -f docker-compose.yml -f docker-compose.thingsboard.yml \
                                   -f profiles/small.yml up -d
ExecStop=/usr/bin/docker-compose down
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
```

---

## MQTT/TLS

```bash
# 主机名必须写全：设备连哪个名字，证书里就得有哪个名字，否则设备会因主机名不匹配而拒连。
./gen-certs.sh tb.example.mil 10.0.0.10

docker compose -f docker-compose.yml -f docker-compose.thingsboard.yml \
               -f docker-compose.thingsboard-tls.yml up -d

# 验证
openssl s_client -connect <主机>:8883 -CAfile certs/ca.pem </dev/null | grep "Verify return code"
./bin/smoke -mqtt ssl://<主机>:8883 -cacert certs/ca.pem
```

**离线内网没有公网 CA，也无法做 ACME 续期，所以用自签 CA。** 信任链靠把 `ca.pem` 预置到每台
设备建立 —— 这是这类部署的常规做法。`ca.key` **不要分发**，离线保管，只在签发新证书时用。

| 文件 | 去向 |
|---|---|
| `ca.pem` | **分发到每台设备**，设备用它校验服务端 |
| `ca.key` | **绝不分发**，离线保管 |
| `mqttserver.pem` / `mqttserver_key.pem` | 挂给 ThingsBoard（只读挂载 `/ssl`） |

有效期：CA 10 年，服务端证书约 2.25 年。**服务端证书到期前需重签并重启 ThingsBoard**，
CA 不变则设备侧无需改动。到期时间见 `gen-certs.sh` 输出。

### 两个实测得出、容易踩的点

**私钥必须是 PKCS#8 且不加口令。** 三种组合只有一种能被 ThingsBoard 读取：

| 格式 | 结果 |
|---|---|
| 传统 PKCS#1（`BEGIN RSA PRIVATE KEY`） | ✗ |
| PKCS#8 + 口令（`BEGIN ENCRYPTED PRIVATE KEY`） | ✗ |
| PKCS#8 无口令（`BEGIN PRIVATE KEY`） | ✓ |

两种失败都只报同一句 `Unable to load private key neither from certFile nor from keyFile`，
**不提示是格式问题**，很容易被误判成路径或口令写错。`gen-certs.sh` 已固定为正确格式。

不加口令的保护来自 600 权限与只读挂载。加口令并不更安全 —— 口令得写在 compose 文件里服务
才起得来，等于钥匙和锁放进同一个抽屉。

**启用 TLS 不会自动关闭明文。** ThingsBoard 的 SSL 是 8883 上的独立监听器，与明文监听器并存。
要真正只允许 TLS，必须同时**不发布**明文端口 —— `docker-compose.thingsboard-tls.yml` 通过把
1884 改为只绑 `127.0.0.1` 实现（用了 `ports: !override`，因为 Compose 对 `ports` 是**追加**
合并而非替换，不加它两条规则会抢同一端口导致容器起不来）。若连本机明文也不需要，删掉那行即可。

### 设备侧

设备用 `ssl://<主机>:8883` 连接，并需内置 `ca.pem`。本仓库的模拟设备已支持：

```bash
./bin/tbdevicesim run -token <token> -broker ssl://<主机>:8883 -cacert certs/ca.pem
```

**没有"跳过校验"选项，这是有意的**：一台不校验服务端身份的设备 —— 它向那里上报液位、
从那里接收启泵命令 —— 并没有被 TLS 保护，只是对被动监听者加了密而已。

---

## ThingsBoard 侧配置（版本化）

ThingsBoard 的配置存在它自己的数据库里，不在仓库里。`deploy/tb-config/` 把其中可版本化的部分
（设备模板、规则链基线）纳入仓库，`tbconfig` 负责应用与导出：

```bash
./bin/tbconfig apply      # 把 tb-config/ 的定义写入 ThingsBoard（幂等，按名字创建或更新）
./bin/tbconfig export     # 导出实例现状到 tb-config/current/，用于回答“生产和仓库一致吗”
```

| 文件 | 作用 |
|---|---|
| `device-profile-yeweiyi-tank-terminal.json` | 本项目设备模板，**权威定义** |
| `rule-chain-root-baseline.json` | 出厂根规则链基线，仅作 diff 参照，`apply` 不会导入 |
| `current/` | 实例快照，`export` 生成，不入库 |

**忘了 apply 不会导致装不上。** 实测确认：用未知 type 建设备时 ThingsBoard 会自动创建同名模板，
所以设备照常开通，只是拿到 ThingsBoard 出厂默认值而非这里定义的。apply 是补齐设置，不是前置条件。

**`apply` 不导入规则链**：覆盖实例上的规则链图风险远高于设备模板（可能切断正在工作的数据流），
必须由人明确决定，不做成自动步骤。

关于将来把设备上报的报警转成 ThingsBoard **原生 Alarm 实体**：出厂根规则链里已经有
`Device Profile Node`，它负责评估**设备模板上定义的**告警规则。也就是说那件事不需要自定义规则链，
把规则写进 `device-profile-*.json` 的 `profileData.alarms` 即可 —— 链路出厂就是通的。
目前 `alarms` 为 `null`，因为是否改用原生 Alarm 会影响 `/api/alarms` 的语义与小程序，属产品决策。

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

### 备份验证与演练

定期执行备份恢复演练，确保备份可用：

```bash
# 1. 制作备份
./backup.sh /tmp/test-backup

# 2. 验证备份完整性
LATEST=$(ls -t /tmp/test-backup | head -1)
[[ -n "$LATEST" ]] || { echo "ERROR: No backup found in /tmp/test-backup"; exit 1; }
cd "/tmp/test-backup/$LATEST"
cat SHA256SUMS | sha256sum -c             # 逐一验证所有文件

# 3. 在测试环境恢复并验证
# （在独立的测试主机或虚拟机上）
./restore.sh /tmp/test-backup/20260902-003000

# 4. 运行冒烟测试验证功能可用
./bin/smoke -api http://localhost:8080

# 5. 验证完毕后清理测试数据
docker compose down -v
```

定期演练的好处：
- 及时发现备份损坏或脚本 bug（最坏的情况是真正需要时才发现备份不可用）
- 确保运维人员熟悉恢复流程
- 测量实际的恢复时间与资源占用

建议每月执行一次，并将演练结果记录在变更日志中。

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
| ThingsBoard 起不来 | `docker logs yeweiyi-thingsboard`。首启装 schema 需 2–4 分钟，期间 `starting` 属正常；若长期 `unhealthy` 而服务其实可访问，先怀疑健康检查本身（本镜像无 curl，只有 wget） |
| 设备连不上 MQTT | 确认 access token 正确（token 放 MQTT **username**，password 留空）；`docker logs` 看是否被拒 |
| 业务 API 返回 503 | ThingsBoard 不可达。`curl http://localhost:8081/login` 自查；503 表示平台故障而非请求有误 |
| 控制命令返回 409 | 物理动作总闸关闭。这是**默认且正确**的状态，设备端联锁验证完成前不要打开 |
| 遥测查得到但趋势图为空 | 检查设备是否发的是扁平键；嵌套 JSON 的聚合会**静默返回 0**，见 `docs/protocol-v1-reconciliation.md` §A |
| 冒烟测试卡在“遥测读回” | ThingsBoard 异步落盘，冒烟测试已内置 30 秒重试；若持续失败查其磁盘是否写满 |
| `/api/oil-in-out` 返回 502 且 `code=STOCK_EVENT_FORMAT_UNSUPPORTED` | 该设备仍以 `DetailInoutRecord.toString()` 上报出入库记录，不是 设备协议 V1 的 JSON（zhongchengyeweiyi#39）。响应里的 `sample` 就是原始值。若该设备**还没升级**，这是正确的报错；若车队**已经升级**，见下面"车队固件升级之后" |

### 车队固件升级之后：必须设置 `stock.legacyFormatBefore`

设备端把出入库改成 JSON 之后，**ThingsBoard 里已有的旧记录不会被转换**——那些调试字符串永远
读不出来。因此升级完成后如果不做任何配置，任何回看到升级之前的查询窗口仍会一直返回 502，接口
就从"暂时诚实地失败"变成"永久损坏"。

两种记录的载荷完全一样，只有**时间**能区分，所以这个边界必须由运维给出：

```yaml
stock:
  legacyFormatBefore: "2026-10-01T00:00:00+08:00"   # 车队完成固件升级的时刻，必须带时区偏移
```

或 `STOCK_LEGACY_FORMAT_BEFORE=2026-10-01T00:00:00+08:00`。设置后：

- 该时刻**之前**解析失败的记录 → 计入响应里的 `legacySkipped`，仍返回 200。它是已经封口的历史
  数据空洞，没有人需要为它做任何事，但数量会如实报出来，避免把不完整的列表当成完整的；
- 该时刻**之后**仍解析失败 → 继续 502。这说明**有设备该升级而没升级**，是当下的故障，必须有人
  去现场看，不能被一并勾销。

**取值要保守。** 宁可比实际升级时刻**晚**一点（多几条记录被当成故障、被人看见），也不要更早
（真正没升级的设备被静默写进历史空洞）。填错格式（缺时区、只写日期）会让后台**启动失败**而不是
按 UTC 猜——这是刻意的，一个被猜歪的边界最多能把它挪动一整天。

日志导出：`docker logs yeweiyi-thingsboard > tb.log`；业务后台日志走 stdout，由 systemd
或容器运行时收集。审计记录在业务库 `audit_log` 表，长期保留，不随遥测清理。

---

## 尚未完成

诚实列出，不要以为这些已经就绪：

- **双向 TLS（mTLS，客户端证书）未启用。** 当前设备身份靠 ThingsBoard 的 access token，
  传输由服务端证书保护。ThingsBoard 支持 X.509 客户端证书认证，若要求设备侧也出示证书需另配。
- **证书续期未自动化。** 服务端证书约 2.25 年到期，需人工重签并重启，尚无到期提醒机制。
- **报警未映射为 ThingsBoard 原生 Alarm 实体。** 目前作为遥测事件流转，因此没有告警确认
  （acknowledge）能力，而 issue #1 要求“报警实时提醒、确认和查询”。技术路径已探明（见上节
  ThingsBoard 侧配置），缺的是产品决策而非实现方案。
- **离线升级与回滚演练未做**（issue #6）。本手册覆盖安装、备份、恢复，不覆盖升级。
- **公有云档位未压测**（issue #5）。已实测的最大规模是 200 台，见
  [`../docs/capacity-test.md`](../docs/capacity-test.md)——小型私有化档（≤100 台）由此得到验证且有
  2 倍余量，1,000/10,000 台仍是推导值。压测工具 `loadtest` 已随包分发，可在真实硬件上复跑。
- **`preflight.sh` 只在 macOS 与 Linux 上试过**，未在国产化操作系统上验证。
