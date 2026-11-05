## Context

- 后端以**单个** ThingsBoard 租户管理员身份访问 ThingsBoard（`internal/tbclient/client.go`）；ThingsBoard 里没有 customer 概念被使用。`docs/thingsboard-ce-baseline.md` 明确“ThingsBoard 侧 tenant/customer 隔离未验证，Go 侧租户模型属于 issue #10”。
- 现有授权：`users.role`（`platform_admin`/`customer_admin`/`customer_user`）+ `user_devices`。判定逻辑散在三处：`userMayAccessDevice`（REST + WS）、`control.Service.authorize`（命令）、`listDevicesHandler`（`ListDevicesForUser`）。PR #11 第 4 轮评审已出现过这种分散导致的漂移。
- `devices` 行在首次铸造配对密钥时由 `EnsureDevice` 惰性创建（仅 `platform_admin` 可铸造）；绑定靠“持有设备二维码 = 授权”。
- `alarms`/`oil_in_out` 等本地事件表已在迁移 `000011` 删除；告警、出入库从 ThingsBoard 读取。因此**需要加租户列的业务表只有** `users`、`devices`、`control_commands`、`audit_log`。
- 审计日志 `device_name` 刻意不做外键（被拒绝的越权尝试可能指向不存在的设备）。
- 生产代码没有创建用户的路径；测试直接 `INSERT INTO users`。

## Goals / Non-Goals

**Goals:**
- 任何用户无法读取、订阅、控制其他客户的设备；该性质由自动化测试对**所有设备范围路由**强制保证，而非靠人逐个检查。
- 判定逻辑只有一份，改一处即全局生效。
- 公有云多客户与私有化单客户共用同一模型，私有化部署零额外配置。

**Non-Goals:** 多客户用户、跨客户共享、RLS、ThingsBoard customer 映射、遥测保留/汇总策略。

## Decisions

### D1 租户只存在于 Go 业务库，不映射 ThingsBoard customer
后端是 ThingsBoard 的唯一客户端，设备也只连 ThingsBoard，Go 已是全部访问的必经之路。若同时在 ThingsBoard 建 customer，就有两份“设备属于谁”的事实来源，且 ThingsBoard CE 的 customer 隔离需要为每个客户建 ThingsBoard 用户，与“Go 后台不建第二套账号体系”相违背。
*替代方案*：ThingsBoard customer 映射——拒绝，原因如上。

### D2 数据模型
- `customers(id, name UNIQUE, created_at)`，新表与新列补中文注释。
- `users.customer_id` 可空外键；CHECK：`(role = 'platform_admin') = (customer_id IS NULL)`。把“平台管理员不属于客户、其他人必属于客户”固化在数据库。
- `devices.customer_id` 可空外键：`NULL` = 已登记未分配。未分配设备对非平台管理员不可见、不可绑定。
- 外键 `ON DELETE RESTRICT`：有用户或设备的客户不能删。

### D3 访问判定：一条 SQL，决策来自数据库，JWT 不带租户
```
platform_admin                      -> 任意设备
customer_admin                      -> devices.customer_id = users.customer_id
customer_user                       -> 上一条成立 且 存在 user_devices 绑定
```
实现为单个 sqlc 查询 `UserMayAccessDevice(user_id, device_name)`，现有 `userMayAccessDevice` 与 `control.authorize` 都只调它（同样是一次数据库往返，不增加开销）。
**不把 `customer_id` 放进 JWT**：现有设计已承认角色变更要等 token 过期（24h）才生效；租户边界若同样走 token，被迁移的用户在旧租户上还有最长 24h 访问权。判定读库则即时生效。方法级权限（`roleAllows`）仍用 token 里的 role，保持不变。
*替代方案*：JWT 带 `customer_id`（最初草稿）——拒绝，边界陈旧。

### D4 历史记录快照一个客户，而不是 JOIN 设备
`control_commands.customer_id`、`audit_log.customer_id` 在写入时取：**操作者所属客户**；操作者是平台管理员（无客户）时取**设备当时的客户**（设备不存在则为 `NULL`）；设备/系统触发的行（`control.result`、`control.expire`）取对应命令的客户。理由：① 设备改归属后历史应留在当时的客户；② 审计要能记录指向不存在设备的越权尝试；③ 平台管理员若记 `NULL`，客户就永远看不到平台运维对自己设备做过什么——最初草稿取“操作者客户”正好有这个洞。
外键 `ON DELETE RESTRICT`：有历史的客户不能被删除，否则审计里会留下指向已不存在客户的悬空 id（阶段 2 的“删除客户”因此需要先改为停用，见 Open Questions）。`audit_log` 补 `(customer_id, occurred_at DESC)` 索引；`control_commands` 现有 `(device_name, issued_at)` 索引足够，不加。
审计查询对非平台管理员**总是**按其所属客户过滤（即使指定了设备），这样设备易主后，新主人看不到旧主人的历史。

### D5 配对与分配
- 铸造（仍仅 `platform_admin`）必须带目标 `customer_id`，写入 `devices.customer_id`；对已分配给其他客户的设备重新铸造视为改归属，走第二阶段的分配流程，不在铸造里静默改。
- 绑定：先验证配对密钥，再比对 `devices.customer_id = users.customer_id`；**密钥错误、设备属于他客户、设备未分配返回完全相同的错误**，保持 CLAUDE.md 约束 6/7（不枚举设备、配对密钥与设备凭据隔离）。

### D6 不做数据库 RLS
RLS 要求每个事务 `SET LOCAL` 当前客户，而 pgx 连接池 + sqlc 的无状态查询、`ingest` 等系统进程都没有“当前客户”；引入需要改所有事务边界，收益是防“忘记加过滤”。这个风险改由 D7 的路由遍历测试覆盖，成本低得多。
*替代方案*：RLS 作第二道防线——推迟；如果日后出现无法被路由测试覆盖的访问路径再评估。

### D7 防漏检：路由遍历测试
测试枚举 gin 路由表，凡含 `:device` 参数或 `device` 查询参数的路由，必须在“跨租户用户”场景下返回与“未绑定用户”相同的 403，并且该测试在新增此类路由而未登记时失败。这是把原草稿里“易遗漏检查点”的风险变成构建期失败。

### D8 分两阶段交付
- **阶段 1（安全关键）**：D2–D5 的 schema、判定、各接口落实、测试。可独立上线，客户/用户暂由运维 SQL 或脚本维护（与现状一致）。
- **阶段 2（管理）**：客户、用户、设备分配 API。拆开是因为阶段 1 才承载 issue #3 的验收标准，管理 API 的设计决策（见 Open Questions）可以并行讨论而不阻塞隔离。

## Risks / Trade-offs

- [某接口漏掉租户检查] → D3 单一判定 + D7 路由遍历测试。
- [存量部署升级后设备“消失”] → 迁移把现有非管理员用户与全部设备回填到 `default` 客户，行为不变。
- [`devices` 行直接序列化进 REST，新增 `customer_id` 外泄给小程序] → 阶段 1 把 `listDevicesHandler` 改为显式响应结构，是否暴露该字段由小程序负责人决定。
- [`platform_admin` 设备列表语义变化] → 在 proposal 标注 BREAKING，随跨仓库确认发布。
- [集成测试依赖 PostgreSQL/ThingsBoard，缺基础设施时会被跳过] → 按 CLAUDE.md，跳过不等于通过；验收必须在带基础设施的环境用 `go test ./... -p 1` 实跑。
- [token 内 role 仍会陈旧] → 既有行为，本变更不改；租户边界不依赖它（D3）。
- [WebSocket 只在握手时授权，握手后被撤销权限的用户保留连接直到断开] → 既有行为，本变更未扩大；如需修复应在推送循环里定期复核，另行立项。

## Migration Plan

1. 迁移 `000013_multitenancy`：建 `customers`；插入 `default`；`users`/`devices` 加 `customer_id` 并回填（非 `platform_admin` 用户与全部设备 → `default`）；加 CHECK；`control_commands`/`audit_log` 加可空 `customer_id`，对存量行按操作者回填；加索引与注释。
2. 同一迁移的 `.down.sql` 删列删表；按 CLAUDE.md 验证 up → down → up 往返。
3. 更新 `db/queries/`，`sqlc generate`；先部署 schema，再部署代码（旧代码忽略新列，可回滚）。
4. 回滚：回滚代码即可；新列留着无害，必要时执行 down。

## Phase 2 decisions

阶段 2 按下列默认值实现（Open Questions 1–3 当时没有被推翻）：
- 创建用户时由创建者设置初始密码（8–72 字节），“重置密码”即设一个新密码；没有邮件/邀请流程。
- `customer_admin` 只能创建 `customer_user`；`customer_admin` 由 `platform_admin` 创建。
- 设备可在客户间转移，仅 `platform_admin`，同事务清除绑定并写审计。
- 停用用户需要 `users.disabled_at`（迁移 `000014`）。访问判定、设备列表、绑定检查、登录、审计查询、所有管理接口都读它，所以停用对已签发 token 立即生效。
- 客户级审计（创建/重命名/删除）不归属任何客户：若归属新客户，历史外键 RESTRICT 会让它一出生就删不掉。用户、设备相关的管理审计归属受影响的客户。
- 用户名全局唯一，所以 `customer_admin` 创建用户时遇到 409 能得知该用户名在别处被占用——这是全局唯一约束的固有代价，接受。

## Open Questions

1. 客户停用：有历史记录的客户因外键 RESTRICT 永远删不掉，目前没有“停用客户”。是否需要 `customers.disabled_at`，以及停用后其用户/设备如何处理？
2. 用户自助改密码、忘记密码流程不在本变更内。
3. `devices.customer_id` 当前不对小程序暴露；是否暴露待小程序负责人确认。
