## 1. 阶段 1：数据库

- [x] 1.1 新增 `db/migrations/000013_multitenancy.up.sql`：建 `customers`、插入 `default`；`users`/`devices` 加 `customer_id` 并回填；加 `(role='platform_admin') = (customer_id IS NULL)` CHECK；`control_commands`/`audit_log` 加可空 `customer_id` 并按操作者回填；加 `audit_log(customer_id, occurred_at DESC)` 索引；新表新列补中文注释
- [x] 1.2 新增对应 `.down.sql`
- [x] 1.3 应用迁移并验证 up → down → up 往返；确认存量用户与设备都落入 `default`
- [x] 1.4 更新 `db/queries/`：`UserMayAccessDevice`（单 SQL，含三种角色规则）、按角色列设备、`ListAuditLog` 增加客户过滤、命令/审计写入带客户快照、绑定时的归属比对
- [x] 1.5 执行 `sqlc generate`（不手改 `internal/store/db/`）

## 2. 阶段 1：判定与各接口

- [x] 2.1 `internal/api` 的 `userMayAccessDevice` 改为只调 `UserMayAccessDevice`；`requireDeviceOwnership` 与 `ws.go` 无需再各自判断角色
- [x] 2.2 `internal/control` 的 `authorize` 改用同一判定；命令行与审计写入带操作者客户快照
- [x] 2.3 `listDevicesHandler`：按角色返回；改为显式响应结构，不再直接序列化 `db.Device`
- [x] 2.4 `bindDeviceHandler`：先验密钥再比对归属，三种失败返回相同错误
- [x] 2.5 `mintPairingSecretHandler`：要求并写入目标 `customer_id`
- [x] 2.6 `listAuditHandler`：`customer_admin` 可不带 `device` 查本客户；`customer_user` 仍必须带 `device`
- [x] 2.7 确认授权先于存在性检查，四种拒绝情形响应一致（CLAUDE.md 约束 6）

## 3. 阶段 1：测试

- [x] 3.1 访问判定表驱动测试：三种角色 × {本客户已绑定, 本客户未绑定, 他客户, 未分配, 不存在}
- [x] 3.2 路由遍历测试：枚举 gin 路由，所有含 `:device`/`device` 的路由须登记并通过跨租户 403 断言，漏登记即失败
- [x] 3.3 WebSocket 跨客户订阅被拒、平台管理员订阅成功
- [x] 3.4 控制命令：跨客户被拒、不建命令行、写 `denied` 审计、快照正确
- [x] 3.5 用户被迁移后旧 token 立即失效于原客户（自动化）；迁移后存量用户与设备落入 default（用 v12 种子数据手工验证 up、CHECK、down/up 往返，无自动化测试）
- [x] 3.6 在带 PostgreSQL、Redis 与 ThingsBoard 4.2.1.1 的环境执行 `go test ./... -p 1 -count=1`：159 通过、0 失败、0 跳过；`go build ./... && go vet ./...` 通过
- [x] 3.7 Codex 评审（PR #18）两个 P1：铸造配对密钥改读数据库角色；命令列表按调用者客户过滤。两条都有回归测试，且已验证去掉修复后测试会失败
- [x] 3.8 Codex 第二轮评审：命令授权与客户快照合并为一条 SQL（`ResolveDeviceAccess`）；认证中间件统一拒绝停用/已删除用户（解除绑定此前漏检）；部署文档不再硬编码客户 id。三处都有回归测试，且已验证去掉修复后测试会失败
- [x] 3.9 Codex 第三轮评审：访问规则收进数据库函数 `user_may_access_device`（迁移 `000015`），命令插入语句自带校验与快照；中间件用数据库行填充角色与用户名；更新用户在事务内加锁重判；设备访问判定先于联锁。五处都有回归测试，且已验证去掉修复后测试会失败
- [x] 3.10 Codex 第四轮评审：创建用户在事务内锁创建者重判；迁移用户以锁下的行取来源；绑定的校验与插入合成一条带共享锁的语句；设备访问被拒先于方法校验。四处都有确定性的竞态回归测试，且已验证去掉修复后测试会失败
- [x] 3.11 Codex 第五轮评审：命令列表与审计列表把授权和客户范围放进读取语句本身；配对密钥写入自带所有权校验并加共享锁。有回归测试，且已验证去掉修复后测试会失败。命令插入加 `FOR SHARE` 的意见不采纳（理由见 journal）

## 4. 阶段 1：文档与跨仓库

- [x] 4.1 更新 `CLAUDE.md`：移除“多租户尚未完成”，改写为当前授权模型；记录“租户只在 Go 库、不映射 ThingsBoard customer”
- [ ] 4.2 向小程序负责人确认：`devices` 行是否暴露 `customer_id`、`platform_admin` 设备列表语义变化、`pairing-secret` 新增必填 `customerId`（文档已写入 `docs/protocol-v1-open-items.md` §D-租户；**等待负责人确认，未确认**）
- [x] 4.3 `docs/engineering-journal.md` 追加带日期条目，链接到本变更
- [x] 4.4 `deploy/README.md` 补充：升级自动创建 `default` 客户，私有化部署无需额外配置

## 5. 阶段 2：管理接口

- [x] 5.1 客户 CRUD（仅 `platform_admin`；`default` 受保护；被引用的客户返回 409）
- [x] 5.2 设备分配/改归属 `PUT /api/devices/:device/customer`：事务内清除 `user_devices`、旧新两个客户各记审计
- [x] 5.3 用户创建/列出/停用/启用/重置密码（`customer_admin` 限本客户 `customer_user`；他客户用户响应同“不存在”）；迁移 `000014` 加 `users.disabled_at`，访问判定与登录读它
- [x] 5.4 用户迁移客户 `PUT /api/users/:id/customer`：事务内清除绑定
- [x] 5.5 全部管理操作写审计（与变更同事务），路由遍历测试登记新路由，并补跨租户用例

## 6. 阶段 2：文档

- [x] 6.1 `CLAUDE.md`：多租户条目改为“阶段 1、2 均已落地”，补管理 API 与 `disabled_at`
- [x] 6.2 `deploy/README.md`：首个平台管理员仍需 SQL 引导，其余改用 API
- [x] 6.3 `docs/protocol-v1-open-items.md` §D-租户：登记新增的管理路由
- [x] 6.4 `docs/engineering-journal.md`：追加阶段 2 的决策记录
