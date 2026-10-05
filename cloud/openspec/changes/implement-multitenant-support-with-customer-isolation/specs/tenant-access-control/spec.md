## ADDED Requirements

### Requirement: 统一设备访问判定
系统 SHALL 用同一个判定函数决定“用户能否访问某设备”，REST、WebSocket 与控制命令 MUST 都调用它，且判定 MUST 读取数据库当前的角色与客户归属，而非 JWT 中的租户信息。规则：`platform_admin` 可访问任意设备；`customer_admin` 可访问本客户全部设备；`customer_user` 仅可访问本客户内且已绑定的设备。

#### Scenario: 客户管理员无需绑定即可访问本客户设备
- **GIVEN** `customer_admin` 属于客户 A，设备 D 属于 A 且未绑定该用户
- **WHEN** 该用户请求 D 的遥测
- **THEN** 请求被允许

#### Scenario: 客户普通用户只能访问已绑定设备
- **GIVEN** `customer_user` 属于客户 A，设备 D1 已绑定、D2 同属 A 但未绑定
- **WHEN** 该用户分别请求 D1、D2
- **THEN** D1 被允许，D2 返回 403

#### Scenario: 跨客户访问被拒绝
- **GIVEN** 用户属于客户 A，设备 D 属于客户 B
- **WHEN** 该用户（即使是 `customer_admin`）请求 D
- **THEN** 返回 403

#### Scenario: 被迁移的用户立即失去原客户访问权
- **GIVEN** 用户持有迁移前签发、尚未过期的 token
- **WHEN** 该用户被改归属到另一客户后再请求原客户的设备
- **THEN** 返回 403

### Requirement: 拒绝不泄露设备是否存在
授权检查 SHALL 先于任何“设备是否存在”的检查；设备不存在、属于他客户、未绑定、未分配这四种情形对非平台管理员 MUST 返回完全相同的响应。

#### Scenario: 四种拒绝响应一致
- **WHEN** 同一用户分别请求不存在的设备、他客户的设备、本客户未绑定的设备、未分配的设备
- **THEN** 状态码与响应体完全一致

### Requirement: 设备列表按租户范围返回
`GET /api/devices` SHALL 按角色返回：`platform_admin` 全部设备；`customer_admin` 本客户全部设备；`customer_user` 本客户内已绑定设备。未分配设备 MUST NOT 出现在非平台管理员的结果中。

#### Scenario: 平台管理员看到全部设备
- **GIVEN** 客户 A、B 各有设备，另有一台未分配设备
- **WHEN** `platform_admin` 列设备
- **THEN** 返回全部设备

#### Scenario: 客户管理员只看到本客户设备
- **WHEN** 客户 A 的 `customer_admin` 列设备
- **THEN** 结果只含归属 A 的设备

### Requirement: WebSocket 订阅隔离
告警 WebSocket 握手 SHALL 使用统一判定；跨客户订阅 MUST 被拒绝，且 MUST NOT 向订阅者推送任何其无权访问设备的事件。

#### Scenario: 跨客户订阅被拒
- **WHEN** 客户 B 的用户订阅客户 A 设备的告警
- **THEN** 握手被拒绝

#### Scenario: 平台管理员订阅未绑定设备
- **WHEN** `platform_admin` 订阅任意设备的告警
- **THEN** 握手成功

### Requirement: 控制命令租户隔离
控制命令的授权 SHALL 先过“物理命令联锁”与角色检查，再过统一设备访问判定；被拒绝的尝试 MUST 写入审计且命令行 MUST NOT 创建。命令与审计记录 SHALL 带操作者客户快照。

#### Scenario: 向他客户设备下发命令
- **WHEN** 客户 A 的 `customer_admin` 向客户 B 的设备下发命令
- **THEN** 返回 403、不创建命令行，并写入一条 `denied` 审计

### Requirement: 命令历史按客户隔离
`GET /api/devices/:device/commands` SHALL 只返回归属调用者所属客户的命令；`platform_admin` 可见该设备的全部命令。设备转移后，新客户 MUST NOT 读到旧客户的命令（含操作者、原因、参数与结果）。

#### Scenario: 设备转移后新主人看不到旧历史
- **GIVEN** 客户 A 的管理员对设备 D 下发过命令，之后 D 被转给客户 B
- **WHEN** B 的管理员列出 D 的命令
- **THEN** 结果不含 A 的命令；B 自己随后下发的命令可见；平台管理员两者都能看到

### Requirement: 命令的授权与客户快照来自同一数据库状态
控制命令的设备访问判定与其客户快照 SHALL 在同一条 SQL 中得出，使并发的用户或设备转移不可能出现“按新客户授权、却记在旧客户名下”。两处写出的访问规则（`UserMayAccessDevice` 与 `ResolveDeviceAccess`）MUST 由测试保证对所有角色与设备组合一致。

#### Scenario: 两条规则一致
- **WHEN** 对每个用户（含已停用与不存在的）与每台设备（含未分配与不存在的）同时求两条规则
- **THEN** 允许与否完全一致，且快照取用户自己的客户，平台管理员则取设备的客户

### Requirement: 铸造配对密钥读数据库角色
`POST /api/devices/:device/pairing-secret` SHALL 按调用者在数据库中的当前角色与停用状态授权，而不是 JWT 里的角色。已停用或已降级的平台管理员持旧 token MUST 被拒绝，且 MUST NOT 创建或认领设备。

#### Scenario: 停用或降级后铸造
- **GIVEN** 平台管理员持有未过期的 token
- **WHEN** 该账号被停用，或被降为 `customer_user`，再调用铸造
- **THEN** 返回 403，设备归属不变

### Requirement: 审计查询范围
`GET /api/audit` SHALL 按角色限定范围：`platform_admin` 可查全部（可按客户过滤）；`customer_admin` 可不指定设备查看本客户审计；`customer_user` MUST 指定其可访问的设备。

#### Scenario: 客户管理员查看本客户审计
- **WHEN** `customer_admin` 不带 `device` 参数查询
- **THEN** 只返回客户快照为本客户的记录

#### Scenario: 客户普通用户不带设备查询
- **WHEN** `customer_user` 不带 `device` 参数查询
- **THEN** 返回 403

### Requirement: 配对绑定的租户校验
绑定 SHALL 要求配对密钥有效**且**设备归属与用户所属客户一致；密钥错误、设备属于他客户、设备未分配 MUST 返回相同的错误。

#### Scenario: 他客户设备的有效二维码
- **GIVEN** 客户 B 的用户持有客户 A 设备的有效二维码
- **WHEN** 其调用绑定
- **THEN** 返回的错误与“密钥错误”完全一致，且不创建绑定

#### Scenario: 未分配设备不可绑定
- **WHEN** 非平台管理员绑定一台未分配设备
- **THEN** 返回与“密钥错误”一致的错误

### Requirement: 设备范围路由的自动化覆盖
每个含 `:device` 路径参数或 `device` 查询参数的路由 SHALL 被一个路由遍历测试覆盖，该测试断言跨客户用户得到与未绑定用户相同的 403；新增此类路由而未登记 MUST 使测试失败。

#### Scenario: 新增未覆盖的设备路由
- **WHEN** 开发者新增一个带 `:device` 的路由但未加入跨租户测试
- **THEN** 路由遍历测试失败
