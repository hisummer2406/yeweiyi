## ADDED Requirements

### Requirement: 客户管理
`platform_admin` SHALL 能创建、列出、重命名、删除客户；其他角色 MUST NOT 访问这些接口，且被拒绝的尝试 SHALL 记入审计。名为 `default` 的客户 MUST NOT 被重命名或删除（部署文档与初始化 SQL 按名称引用它）。客户名称唯一。仍被用户、设备、命令或审计记录引用的客户 MUST NOT 被删除。

#### Scenario: 非平台管理员管理客户
- **WHEN** `customer_admin` 或 `customer_user` 调用创建或列出客户
- **THEN** 返回 403，且每次尝试在其所属客户名下记一条 `denied` 审计

#### Scenario: 名称冲突
- **WHEN** 创建或重命名为已有客户的名称
- **THEN** 返回 409

#### Scenario: 保护默认客户
- **WHEN** 重命名或删除 `default`
- **THEN** 返回 409

#### Scenario: 删除仍被引用的客户
- **WHEN** `platform_admin` 删除仍有设备或用户的客户
- **THEN** 返回 409 且客户保留

#### Scenario: 删除空客户
- **WHEN** `platform_admin` 删除没有任何引用的客户
- **THEN** 返回 204，再次删除返回 404

### Requirement: 设备分配
`platform_admin` SHALL 能通过 `PUT /api/devices/:device/customer` 把设备分配给客户，或在客户间转移。转移 MUST 在同一事务内清除该设备全部 `user_devices` 绑定并写审计；审计 SHALL 在旧、新两个客户名下各记一条（未分配设备只记新客户一条）。分配到设备已属的客户 SHALL 是无变化的成功。其他角色 MUST 被拒绝。

#### Scenario: 转移设备
- **GIVEN** 设备 D 属于 A 且已绑定一名用户
- **WHEN** `platform_admin` 把 D 转给 B
- **THEN** 该绑定被删除，旧绑定用户与 A 的管理员立即失去访问，B 的管理员获得访问，A、B 名下各有一条审计

#### Scenario: 认领未分配设备
- **WHEN** `platform_admin` 把未分配设备分配给 A
- **THEN** A 的管理员获得访问，且只写一条审计

#### Scenario: 不存在的设备或客户
- **WHEN** 分配一个不存在的设备，或分配给不存在的客户
- **THEN** 分别返回 404 与 400

### Requirement: 用户创建与列出
`platform_admin` SHALL 能创建任意角色的用户；`customer_admin` SHALL 只能在本客户内创建 `customer_user`；`customer_user` MUST NOT 创建或列出用户。创建者设置初始密码（8–72 字节），响应与审计 MUST NOT 含密码或哈希。平台管理员账号不得带客户，其余角色必须带客户。用户列表：`platform_admin` 可见全部（可按客户过滤），`customer_admin` 只见本客户。

#### Scenario: 客户管理员越权创建
- **WHEN** `customer_admin` 创建 `customer_admin`、`platform_admin`，或在他客户创建用户
- **THEN** 返回 403

#### Scenario: 输入校验
- **WHEN** 密码过短或超过 72 字节、角色未知、用户名为空、缺少客户、客户不存在，或平台管理员带客户
- **THEN** 返回 400；用户名重复返回 409

#### Scenario: 新用户可登录
- **WHEN** 创建成功后用初始密码登录
- **THEN** 登录成功

### Requirement: 用户停用与密码重置
`platform_admin` SHALL 能停用/启用任意用户并重置其密码；`customer_admin` SHALL 只能对本客户的 `customer_user` 这样做。停用 MUST 立即生效：被停用用户无法登录，且已签发未过期的 token 在**每一个**受保护接口上都被拒绝（由认证中间件读库统一强制，包括不涉及设备访问判定的接口，如解除绑定）。已删除用户的 token 同样被拒绝。任何用户 MUST NOT 能停用自己，以免把自己锁在外面。对他客户用户的操作 MUST 返回与“用户不存在”一致的 404；对本客户内非 `customer_user` 的操作返回 403。重置后的新密码 MUST NOT 写入审计。

#### Scenario: 停用立即生效
- **GIVEN** 用户持有停用前签发的 token
- **WHEN** 管理员停用该用户
- **THEN** 该 token 访问设备返回 403，登录返回 401；重新启用后访问恢复

#### Scenario: 他客户的用户与不存在的用户不可区分
- **WHEN** `customer_admin` 停用他客户的用户，或停用一个不存在的 id
- **THEN** 两者返回完全相同的 404

#### Scenario: 管理平级账号
- **WHEN** `customer_admin` 停用本客户另一个 `customer_admin`
- **THEN** 返回 403

#### Scenario: 被停用的管理员
- **WHEN** 已被停用的 `customer_admin` 用旧 token 创建用户
- **THEN** 返回 403

### Requirement: 用户迁移客户
`platform_admin` SHALL 能把非平台管理员用户迁到另一客户，迁移 MUST 在同一事务内清除其全部 `user_devices` 绑定，并在旧、新客户名下各写一条审计。迁移到当前所属客户是保留绑定的无变化成功；迁移平台管理员返回 400；其他角色 MUST 被拒绝。

#### Scenario: 迁移用户
- **GIVEN** 用户在 A 且绑定了设备
- **WHEN** `platform_admin` 把该用户迁到 B
- **THEN** 绑定被清空，其旧 token 对 A 与 B 的设备均返回 403，A、B 名下各有一条审计

### Requirement: 管理操作审计
所有客户、用户、设备分配的变更 SHALL 写入 `audit_log`，且与变更同一事务。客户级操作（创建/重命名/删除客户）不归属任何客户，以免新建的客户因审计外键而无法删除；其余操作归属受影响的客户（平台管理员操作取目标的客户）。

#### Scenario: 创建用户产生审计
- **WHEN** `customer_admin` 创建一名 `customer_user`
- **THEN** 审计中有一条 `user.create`，归属该管理员所属客户
