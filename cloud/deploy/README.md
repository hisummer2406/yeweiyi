# 离线部署与运维手册

面向**完全断网内网**的安装、备份、恢复与排障。GitHub issue #2 的交付物。

本目录既是仓库里的源文件，也是离线包解开后的根目录 —— 手册里出现的路径两种场景下都成立。

---

## 组件与端口

| 组件 | 镜像 | 端口（宿主机 → 容器） | 用途 |
|---|---|---|---|
| ThingsBoard CE | `thingsboard/tb-postgres:4.2.1.1` | `127.0.0.1:8081 → 9090` | 控制台 / REST / WebSocket |
| | | `8883` | 设备 MQTT over TLS（生产用） |
| | | `1884 → 1883` | 设备 MQTT 明文（启用 TLS 后仅绑环回） |
| 业务 PostgreSQL | `postgres:16` | `127.0.0.1:5432` | 命令、审计、用户、设备绑定 |
| Redis | `redis:7` | `127.0.0.1:6379` | 报警实时扇出 |
| Go 后台 | 本仓库构建 | `8080` | 业务 REST / WebSocket |

**对外只需开放 8080（业务 API）与 1884/8883（设备接入）。** 8081 是 ThingsBoard 控制台，
仅供运维调试，不应暴露给终端用户；5432 与 6379 不应离开本机。**这三个端口在 Compose 里默认只绑
`127.0.0.1`**，不依赖任何叠加文件；要从别的机器访问，用 SSH 隧道（`ssh -L 8081:localhost:8081 <主机>`），
不要改成 `0.0.0.0`。启用 TLS 后明文 1884 同样收回到环回（见下）。

启用 TLS 后设备走 `8883`，明文 `1884` 收回到环回地址（仅本机联调可用）。见下节。

---

## 安装（内网）

```bash
tar -xzf yeweiyi-offline-<版本>.tar.gz && cd yeweiyi-offline-<版本>

./preflight.sh          # 端口、内存、磁盘、Docker 版本；只读不改
./offline-load.sh       # 校验 SHA256SUMS 后导入镜像，全程不联网

# 业务 API 镜像随包导入，tag 记在包内 api-image.env。用容器方式部署时，把它并入 .env.prod，
# 并让 migrate 服务读到包内的迁移目录（离线包里叫 migrations，不是 db/migrations）：
cat api-image.env >> .env.prod
echo 'MIGRATIONS_DIR=./migrations' >> .env.prod

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
带 `-api` 时，绑定后会等待 31 秒，跨过归属变更的 30 秒历史保护窗口，再产生测试报警；报警送达计时从实际发送开始。

### 首次业务账号初始化

业务 API（Go 后台）与 ThingsBoard 是两套独立的身份体系。Go 后台的首次账号需要**首先创建**。

账号分属**客户**（租户）：数据库迁移会自动创建名为 `default` 的客户，并把升级前已有的用户和设备都
归入它——**私有化单客户部署不需要任何额外配置**，把所有用户建在 `default` 下即可。平台管理员不属于
任何客户（`customer_id` 必须为空），其余角色必须属于一个客户；数据库有 CHECK 约束强制这一点。

**只有第一个平台管理员需要 SQL 引导**，其余客户、用户、设备分配都用 API（均需 `Authorization: Bearer`）：

```bash
export PGPASSWORD=yeweiyi
PSQL="docker run --rm -i --network host -e PGPASSWORD postgres:16 psql -h localhost -U yeweiyi -d yeweiyi"

# 第一个平台管理员（不属于任何客户）——唯一需要手工 SQL 的一步
$PSQL -c "INSERT INTO users (username, password_hash, role) VALUES \
  ('admin', crypt('change-me-immediately', gen_salt('bf')), 'platform_admin') \
  ON CONFLICT (username) DO NOTHING;"
```

```bash
# 之后用平台管理员登录，拿 token，再管理其余一切
TOKEN=$(curl -s localhost:8080/api/login -d '{"username":"admin","password":"change-me-immediately"}' \
  -H 'Content-Type: application/json' | jq -r .token)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# 新增客户，记下返回的 id。注意 default 客户占用了 id 1，新客户不会是 1——不要硬编码
CID=$(curl -s "${H[@]}" localhost:8080/api/customers -d '{"name":"客户名称"}' | jq -r .id)

# 客户管理员：手机号 + 密码 + 客户（客户侧账号以手机号标识，没有用户名）
USER_ID=$(curl -s "${H[@]}" localhost:8080/api/users \
  -d "{\"phone\":\"13800138000\",\"password\":\"至少8位的密码\",\"role\":\"customer_admin\",\"customerId\":$CID}" \
  | jq -r .id)

# 员工：手机号 + 客户，**不带密码**（带了会 400 password_not_allowed）；员工只能经微信小程序登录
STAFF_ID=$(curl -s "${H[@]}" localhost:8080/api/users \
  -d "{\"phone\":\"13900139000\",\"role\":\"customer_user\",\"customerId\":$CID}" | jq -r .id)

curl "${H[@]}" -X PUT localhost:8080/api/devices/DEVICE_NAME/customer -d "{\"customerId\":$CID}"   # 分配/转移设备
curl "${H[@]}" -X PUT localhost:8080/api/users/$USER_ID -d '{"disabled":true}'                      # 停用用户（立即生效）
curl "${H[@]}" -X PUT localhost:8080/api/users/$USER_ID -d '{"password":"新的至少8位密码"}'          # 重置客户管理员密码
```

**账号的创建、停用/启用、重置密码只有平台管理员能做**，客户管理员调用这些接口一律 `403`（只能列出本
客户员工，手机号脱敏）。员工没有密码，对员工重置密码返回 `409 no_password_for_role`。网页管理员登录用
`{"phone": ..., "password": ...}`（客户管理员）或 `{"username": ..., "password": ...}`（平台管理员）。
升级到手机号账号之前创建的客户账号**不迁移**，需用上面的接口重建。转移设备或用户会**同时清除它们的设备绑定**（旧客户下的绑定不能带到新客户）。
设备需先归属客户，才能被该客户的用户绑定：安装人员以平台管理员调
`POST /api/devices/:device/pairing-secret`，请求体带 `{"customerId": <客户 id>}`；设备已属于另一个
客户时返回 409，不会被静默改归属（要转移请用上面的分配接口）。有历史记录的客户无法删除。

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
确保容器异常退出时自动恢复。

生产部署时业务 API 也作为容器运行：`docker-compose.prod.yml` 里的 `api` 服务使用 GHCR 镜像，
`migrate` 服务在它启动前执行数据库迁移。`.env.prod` 里需要 `API_IMAGE`（固定到具体 tag）、
`JWT_SECRET`、`TB_PASSWORD` 等，完整清单见该文件开头注释。采用这种方式时，不要再同时用下面的
选项 B 启动 systemd 服务，否则两个后台会抢 8080 端口。若需要手动管理启停：

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
REDIS_PASSWORD=<与 .env.prod 里的 REDIS_PASSWORD 相同；开发环境的 Redis 无密码，可不设>
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

## 生产部署：凭据、端口隔离与验收

标准安装（上文）用的是开发默认凭据（`yeweiyi:yeweiyi`、Redis 无密码）。**生产必须叠加
`docker-compose.prod.yml`**，它把两个密码设为必填，缺任何一个都会在 `up` 时直接报错，而不是悄悄用弱默认值起来：

```bash
# .env.prod 不要提交到 git，也不要把密码写在命令行里
cat > .env.prod <<'ENVEOF'
POSTGRES_PASSWORD=<强随机值>
REDIS_PASSWORD=<强随机值>
ENVEOF
chmod 600 .env.prod

docker compose --env-file .env.prod \
  -f docker-compose.yml -f docker-compose.prod.yml \
  -f docker-compose.thingsboard.yml -f docker-compose.thingsboard-tls.yml up -d
```

后台进程要用同样的值：`DATABASE_URL` 里的密码，以及环境变量 `REDIS_PASSWORD`（写进 systemd 的
`EnvironmentFile`，见上文“服务托管”）。Redis 密码走环境变量传进容器，`docker inspect` 的启动参数里看不到。

> **`POSTGRES_PASSWORD` 只在数据卷第一次初始化时生效。** 已有数据卷的机器改 `.env.prod` 不会改库里账号的
> 密码，会变成“compose 是新密码、库里是旧密码”。已有卷要改，先 `ALTER USER yeweiyi PASSWORD '新密码';`，
> 再改 `.env.prod` 和 `DATABASE_URL`。Redis 没有这个问题：密码在每次启动时读取。

### 端口隔离必须在目标机器上实测

仓库里有一个自动检查：`./deploy/check-compose.sh`（需要 docker compose 与 jq，CI 每次都跑）。它校验各种
Compose 组合能解析、生产叠加缺任何必填凭据会报错、5432/6379/8081/1884 只绑 `127.0.0.1`。**它只检查配置
文件，不能代替下面在目标机器上的实测**——宿主机防火墙、云厂商安全组、别的 Compose 项目都不在它的视野里。

不要用文件里的注释证明隔离：Compose 对 `ports` 是**追加**合并，叠加文件很容易悄悄撤销或重复。上线前：

```bash
# 1. 合并后的最终配置里，5432 / 6379 / 8081 / 1884 都只能绑 127.0.0.1，只剩 8883 对外
docker compose --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml \
  -f docker-compose.thingsboard.yml -f docker-compose.thingsboard-tls.yml config | grep -B1 -A3 'published:'

# 2. 在**另一台机器**上探测：前四个必须不可达（连接被拒或超时），8883 和业务 API 端口要通
for p in 5432 6379 8081 1884; do nc -zv -w 3 <目标主机> $p; done
nc -zv -w 3 <目标主机> 8883
```

Compose 容器已配置 `restart: unless-stopped`，Docker 服务随开机启动即可恢复。业务 API 不在 Compose 里，
用 systemd 托管（见上文）。

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

## 遥测保留（ThingsBoard 原始遥测 TTL）

`docker-compose.thingsboard.yml` 通过 `SQL_TTL_TS_TS_KEY_VALUE_TTL` 设置 ThingsBoard 原始遥测的保留期
（`TS_RETENTION_SECONDS`）。ThingsBoard 自身默认是 0，即永不过期。

> **本项目默认 0（不清理），在 #29、#30 完成前不要开启。** 产品要求业务系统保留全部出入库、报警记录和
> 小时级汇总（ThingsBoard 里的数据可以删），而这三类数据目前只存在 ThingsBoard：出入库与报警是以遥测
> 形式进来的（`stockEvent`、`alarm`），汇总是查询时现算的。开启 TTL 会把它们永久删除，设备端又长期保留
> 这些记录，云端就会与设备端不一致。落库与回补（#29：出入库、报警；#30：小时汇总）完成并验证后，再把
> `TS_RETENTION_SECONDS` 设为 `604800`（7 天）。

2026-10-06 在 CE 4.2.1.1 上实测（issue #23）：写入 40/9/8/6 天前和现在的点，开启 7 天后 8 天及更早的被
删，6 天前的保留；客户端属性不受影响。清理按行进行，不是整月分区，尽管分区粒度是 MONTHS。

- 清理每天跑一次（`SQL_TTL_TS_EXECUTION_INTERVAL`，可用 `TS_CLEANUP_INTERVAL_MS` 覆盖，毫秒），所以过期
  数据最多多留约一天。
- 业务库里的命令、审计、报警处置不在 ThingsBoard，不受此 TTL 影响；它们没有自动清理，属长期保留。
- **限制：每个 key 的“最新值”（`ts_kv_latest`）不随 TTL 清除。** 历史查询对已过期的 key 返回空，但
  “最新值”查询仍返回那个旧点。设备不再上报的 key（例如减少了罐数）会一直显示最后一个值。
  **`LatestSnapshot` 也受影响：** 它以 `tankCount` 的时间戳为锚点回查历史点，设备停报超过保留期后该点
  已被删。#28 的处理：`/telemetry/latest` 返回 `404`，`code` 区分两种状态——`telemetry_not_reported`（从未上报）
  与 `telemetry_expired`（上报过但已超过保留期，响应带 `lastReportedAt`）。**不返回旧值**：遥测不得复制进业务库，
  按 key 取最新值再拼会让已删的罐复活。需要最后已知值的场景只能靠缩短不上报的窗口（或调大保留期）；
  直接按 key 取最新值的调用方需自行判断时间戳。
- 开启后，`telemetry/history` 对早于保留期的窗口会返回 200 加空序列，与“没有数据”无法区分，由 #30 解决
  （超出 7 天读小时汇总，不可用的粒度返回 400）。
- ThingsBoard 自带的审计日志 TTL（`SQL_TTL_AUDIT_LOGS_SECS`）默认也是 0，会缓慢增长；本项目的
  审计在业务库，这份是平台自己的，容量规划时留意，未在此处调整。
- 验证方法：在测试环境用 `TS_RETENTION_SECONDS=604800 TS_CLEANUP_INTERVAL_MS=60000`，向测试设备 POST 带
  历史 `ts` 的遥测，用 `docker compose ... up -d` **重建**容器（`restart` 不会应用环境变量改动），等一两
  分钟再查询；日志里有 `Total telemetry removed stats by TTL`。验证后去掉这两个变量并再次重建。

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

**业务库里的出入库与报警是长期事实来源**（issue #29）：`stock_events`、`alarm_events`、`record_rejects`
三张表（迁移 000017）都在 `business.sql.gz` 里，且**只在这里有永久副本**——ThingsBoard 里的同类记录
可以被 TTL 或人工清理删掉。因此备份业务库不是可选项；ThingsBoard 备份只对在途遥测与设备凭据有意义。
恢复后这三张表随业务库一起回来，无需再回补；若恢复的是比较旧的业务库备份，重启服务后每台设备会按
`ingest.recordReconcileLookback` 自动从 ThingsBoard 补回备份之后的记录，更早的缺口见下节。

建议每日一次并异地留存；`manifest.txt` 记录了 ThingsBoard 镜像版本与业务库 schema 版本，
恢复时据此判断目标环境是否匹配。

**备份与设备彻底删除（cloud#83，[契约](../docs/device-purge-contract.md)）**：平台管理员彻底删除设备只清在线数据，
已有的备份里仍有该设备的记录，按备份轮转周期自然过期，云端不宣称清除了它们。**恢复一份早于删除的备份会把被删
设备连同历史一起带回来**（备份里也没有那条删除任务）：恢复后对照 `device_purges` 的导出或删除审计，逐台再发起一次删除。

**被删设备以同一编号重新接入**：ThingsBoard 设备实体已随删除一起删掉，旧凭据失效。先在 ThingsBoard 按出货 CSV 流程
重新预登记该编号，设备重新领取凭据，再由平台管理员 `POST /api/devices` 登记；它是全新设备，没有任何旧历史与授权。

### 启用 ThingsBoard 遥测 TTL 之前：回补出入库与报警

`TS_RETENTION_SECONDS` 必须保持 `0`，直到下面几步做完（CLAUDE.md 约束 5）。TTL 一开，ThingsBoard
里更早的出入库与报警就永久消失，业务库里没有的就再也找不回来。

1. 服务已运行一段时间。尚无"全量回补完成标记"（`record_backfills`）的设备，订阅后会自动从头全量
   回补；有标记之后只回读最近 `ingest.recordReconcileLookback`（默认 8 天）。没有标记期间，
   `/api/oil-in-out` 返回 `partial:true`，`/api/alarms` 带响应头 `X-Records-Partial: true`。
2. 对全部设备显式再跑一次全量回补（幂等，可与服务同时运行，可重复；从不删除任何东西）：

   ```bash
   go run ./cmd/backfill -config config.yaml          # 全部设备
   go run ./cmd/backfill -config config.yaml -device DEVICE_NAME -from 2026-09-01T00:00:00+08:00
   ```

   有任何设备 `FAILED` 时进程以非零退出——此时不要开 TTL，排查后重跑直到干净。
3. 核对：对每台设备，比较 ThingsBoard 里的点数与业务库行数（`stock_events` 行数 + 其 `report_ts`
   基数之和 − 行数 = 被折叠的重复报告数；`record_rejects` 单独计数）。
4. 回补小时汇总（issue #30）：对现有原始遥测一次性全量补算（幂等，可重复；已有的完整小时不会被残缺值覆盖）：

   ```bash
   go run ./cmd/backfill -config config.yaml -telemetry                       # 默认从 ThingsBoard 里最早的原始点起
   go run ./cmd/backfill -config config.yaml -telemetry -from 2026-09-01T00:00:00+08:00
   ```

   不带 `-from` 时按设备探测 ThingsBoard 中最早的原始点，不论 TTL 关闭期间攒了多久都会补全；`-from` 仅用于
   只补某一段。服务运行时汇总任务每小时自动补算已结束的小时（首次对没有汇总的设备同样从最早的原始点起），并总是从"已汇总的最后一小时"起算，所以服务
   停机再久也不留洞——前提是在原始数据过期**之前**恢复。核对：`SELECT device_name, max(hour_start)
   FROM telemetry_hourly GROUP BY 1` 应当接近当前整点。
5. 把 `telemetry.retention`（环境变量 `TELEMETRY_RETENTION`）设成与 `TS_RETENTION_SECONDS` 相同的值。
   趋势接口据此决定从哪里起读汇总表；早于它的窗口只支持 `interval=1h`，其余粒度返回 400 并写明最早
   可查时刻。

以上几步做完才可开启 TTL。

### 小时汇总的容量预估（**纸面估算，未实测**）

每行 = (设备, 罐, 字段, 小时) 一个数值汇总，表见迁移 000018。每年 8760 小时，按 100 台 × 4 罐 × 9 个字段：
100 × 4 × 9 × 8760 ≈ **3150 万行/年**。按含主键索引每行约 150 字节估算，约 **4.7 GB/年**；罐数或设备数
翻倍则按比例翻倍。写入速率只有每小时 3600 行，不是瓶颈；成本在存储与备份体积。需要压缩时，优先
减少汇总的字段（`protocol.AggregatableTankFields`），其次按年分区。真实数字应在有负载的环境里
`SELECT pg_total_relation_size('telemetry_hourly')` 后回填到这里。

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

打包时必须设置 `API_IMAGE=ghcr.io/…:<具体 tag>`（不接受 latest；GHCR 包默认私有，需先 `docker login ghcr.io`），
脚本拉取后把实际摘要写进**包内**的 `images.txt`。其余镜像按 `images.txt` 拉取并**逐个校验摘要**后导出；摘要不符即中止 —— tag 可以被重新推送指向
不同内容，摘要不符意味着打进包里的东西和清单声称的不是同一个。同时静态编译
`server`/`smoke`/`tbdevicesim`/`tbconfig`/`loadtest`/`backfill` 六个 Linux 二进制（`backfill` 用于
开启 TTL 前的回补），内网侧不需要 Go 工具链。

把 tar 包与脚本末尾打印的 SHA256 **分开传递**（例如摘要走纸质或电话），内网侧用
`offline-load.sh` 校验后导入。

---

## 升级到迁移 000023：油站与大屏一对一（cloud#61）

迁移 `000023_station_one_device` 给 `devices.station_id` 加唯一约束。**升级前**在目标库只读检查是否已有一站多设备：

```sql
SELECT station_id, array_agg(device_name ORDER BY device_name) AS devices
FROM devices WHERE station_id IS NOT NULL
GROUP BY station_id HAVING count(*) > 1;
```

- 无结果：直接升级。
- 有结果：迁移会**失败并在错误里列出冲突**，不改任何数据（失败后 golang-migrate 记为 dirty，确认库结构未变后
  `migrate force 22` 再处理）。每个冲突油站须由业务方决定保留哪一台设备，其余通过
  `PUT /api/devices/:device/station {"stationId": null}` 解除关联（设备、记录与绑定都保留），然后重跑。
  不要在迁移里自动挑选或删除。
- 回滚（down）只删除唯一索引并恢复普通索引，不改数据。

换机（A → B）不迁移任何数据：B 必须先按完整出厂编码登记（`POST /api/devices`），再由平台/客户管理员调用
`POST /api/stations/:id/replace-device`。B 没有任何用户绑定，需重新授权；A 保留在原客户下为未分组设备。

## 升级到迁移 000024：员工按油罐授权（cloud#63）

迁移 `000024_tank_grants` 新增 `user_tank_grants` 并改写 `user_may_access_device`，**不转换**旧的 `user_devices` 绑定。
升级后：

- 客户普通用户（员工）在被平台管理员明确分配油罐之前看不到任何设备——这是有意的（旧设备级绑定不能当作"全部油罐"的放行依据）。
  升级窗口内请由平台管理员用 `PUT /api/users/:id/tank-grants/:device` 重新分配（罐号来自 `GET /api/devices/:device/tanks`，
  设备需已上报）。
- `POST /api/devices/bind` 恒返回 `410 bind_removed`；扫码改用 `POST /api/devices/resolve`（只定位，不授权）。旧版小程序
  在升级后扫码会收到 410，需同步发布新版。
- 员工只读：不能发命令，不读命令历史与审计。管理员行为不变。
- 回滚（down）恢复旧函数并删除 `user_tank_grants`；回滚后员工的可见性重新由 `user_devices` 决定。
- 升级前可只读核对影响面：
  `SELECT u.id, u.phone, count(*) FROM user_devices ud JOIN users u ON u.id = ud.user_id GROUP BY 1,2;`
  即将失去可见性、需要重新分配的员工与其绑定数。

## 微信小程序登录（可选，默认关闭）

员工（`customer_user`）和客户管理员在小程序里通过"微信手机号授权"登录。它**需要出网访问
`api.weixin.qq.com`**，因此是可选能力：`wechat.enabled` 默认 `false`，关闭时两个
`/api/auth/wechat/*` 接口返回 `503 wechat_unavailable`，管理员网页密码登录和服务启动完全不受影响。
完全离线的私有化部署保持关闭即可，**离线下员工没有任何登录方式**——若离线交付需要员工入口，需另行设计。

```bash
export WECHAT_ENABLED=true
export WECHAT_APP_ID=wx0123456789abcdef
export WECHAT_APP_SECRET=...        # 只能走环境变量；写进 config.yaml 会让服务拒绝启动
```

- Docker 生产部署：将上述三项填入服务器 `.env.prod`（权限 `600`）；
  `docker-compose.prod.yml` 会将它们传入 API 容器。修改后需用既有完整 Compose 文件组合
  执行 `up -d --no-deps api` 重建 API 容器，`restart` 不会应用环境变量改动。
  开启前先核对 AppID 与小程序一致，并验证服务健康及真实微信登录；密钥不提交到 Git。
- 开启前核实目标小程序的**手机号快速验证能力**资格与费用，并在小程序后台配置服务器域名。
- 启动时不访问微信；`access_token` 只存内存。AppSecret、access_token、openid、两类 code 不写日志、不进审计。
- 员工需先由平台管理员创建（手机号 + 客户）才能登录；未开通手机号 `404 phone_not_provisioned`，
  不会自动注册。微信身份一旦绑定不会自动换绑：员工换微信号目前只能由运维用 SQL 删除
  `user_wechat_identities` 中对应行（是否提供管理接口待产品决定，契约 Q3）。
- **仅用假微信服务器验证过**；真实小程序端到端联调尚未做。

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
| 控制命令返回 503，提示"审计不可用，命令未发送" | 业务库写不进审计表。**没有任何命令被发给设备**（命令行停在 `pending`，由清扫置为 `expired`），查业务库（磁盘、连接数、锁）后重试即可 |
| 日志出现 `control: AUDIT WRITE FAILED` | 一条审计记录没能写入业务库。该行日志带有完整内容（操作人、设备、命令、结果、详情），**这是唯一的一份**，请保存。命令本身的状态不受影响（已发出的命令不会因此回滚）。**必须对这个字符串配置日志告警** |
| 日志出现 `result NOT recorded` | 设备上报的命令结果没能写进业务库（数据库故障）。后台每 10 秒会从 ThingsBoard 回读未结束命令的结果并重试，命令有效期（默认 60 秒）内数据库恢复即可补上；超过有效期仍写不进，命令会被置为 `expired`，**此时设备可能已经执行**，以设备现场状态为准 |
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

## 持续部署（生产）

生产 API 只监听宿主机 127.0.0.1:8080，由 HTTPS 反向代理对外提供服务。参考
`deploy/nginx-api.conf.example`，先配置 ACME 验证和可信证书，再启用 HTTPS。设备 MQTT 使用独立的
8883/TLS；ThingsBoard 原始遥测 TTL 保持 0，物理命令保持关闭，直到对应验收完成。

`.github/workflows/deploy.yml` 仅允许从 main 手动运行，输入完整 40 位 `release_sha`。
工作流校验该提交属于 main，且同一提交最近的 main/push CI 和 Image 均成功；然后 checkout 该提交，
固定镜像到 digest 并同步对应迁移。当前私有仓库计划不支持分支保护，因此不依赖尚未配置的 Environment
审批或保护规则；不要为了部署公开仓库。

仓库 Settings → Secrets and variables → Actions 配置 Repository Secrets：
`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_PORT`（默认 22）、`DEPLOY_PATH`、`DEPLOY_SSH_KEY`、
`DEPLOY_KNOWN_HOSTS`。SSH 主机公钥需从已核实的 SSH 连接登记。部署用户需要已有免密码 sudo 权限，
脚本通过 sudo 调用 Docker，无需把用户加入 docker 组。CI 用专用密钥，勿复用运维个人密钥。

首次安装需在服务器准备 `.env.prod`（权限 600）、MQTT 证书、PG/Redis/ThingsBoard 基础服务以及业务管理员。
密钥只留服务器，`.env.prod` 不经过 CI。GHCR 包需要赋予此仓库 GitHub Actions 读取权限；工作流用本次
`GITHUB_TOKEN` 临时登录服务器的 `.docker-ci`，结束后清理，无需在服务器长期保存个人 PAT。

`remote-deploy.sh` 拉取失败不改变旧环境；每次发布显式执行迁移，然后切换 API，并同时检查运行镜像标识和
限时健康请求。迁移、启动、健康检查失败返回失败并尝试恢复旧 API。回滚不执行数据库 down，schema 必须
向后兼容；备份、首装、业务验收、ThingsBoard/TLS 检查独立进行。脚本使用 Linux flock 防止并发发布。
回滚路径的无容器回归：`python3 deploy/test-remote-deploy.py`。

发布时 Runner 会先按已验证的 digest 拉取 API 镜像，再经 SSH 传输镜像以预热 ECS 缓存，
核对两端镜像 ID 后，远端仍拉取原 digest。这样不依赖 ECS 跨境下载大镜像层的速度，
也不改变不可变版本门禁。SSH 配置连接超时与 30 秒保活，避免长操作无输出时空闲连接被中间网络断开。
