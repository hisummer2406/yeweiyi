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
访问规则 SHALL 只有一份定义（数据库函数 `user_may_access_device`），REST、WebSocket、命令授权、命令插入都调用它。控制命令的**插入语句本身** SHALL 再次校验该规则并在同一条语句内取得客户快照：用户或设备在早期判定之后、写入之前被转移、降级或停用，插入 MUST 失败且 MUST NOT 留下任何命令或向设备发送任何东西，使“按新状态授权、却记在旧客户名下”不可能发生。设备列表查询是规则唯一以列表形式写出的地方，MUST 由测试保证与该函数一致。

#### Scenario: 授权之后设备易主
- **GIVEN** 客户 A 的管理员已通过早期判定
- **WHEN** 设备在写入命令之前被转给客户 B
- **THEN** 插入返回“不再允许”，不产生命令行，也不向设备发送

#### Scenario: 插入时的快照
- **WHEN** 允许的命令被插入
- **THEN** 其客户为操作者所属客户，平台管理员则为设备当时的客户

#### Scenario: 列表与函数一致
- **WHEN** 对每个用户（含已停用）与每台存在的设备同时求设备列表与访问函数
- **THEN** 一台设备出现在列表里，当且仅当函数允许

### Requirement: 方法级角色读数据库
命令的方法级权限（`control` 与 `admin` 两档）SHALL 按用户在数据库中的当前角色判定，而不是 JWT 里的角色声明。认证中间件 SHALL 用数据库行填充请求上下文中的角色与用户名。

#### Scenario: 降级后的管理员
- **GIVEN** 客户管理员持有降级之前签发的 token，且仍绑定着某台设备
- **WHEN** 其被降为 `customer_user` 后下发 `admin` 级方法
- **THEN** 返回 403；`control` 级方法仍可下发

### Requirement: 未授权调用者先得到 403
控制命令的设备访问判定 SHALL 先于方法是否存在、理由是否填写、联锁（物理命令开关）与方法级角色检查。对没有权限的调用者，无论方法是否为物理命令、联锁是否关闭，响应 MUST 是同一个 403；只有已获授权的调用者才会得知联锁关闭（409）。

#### Scenario: 未知方法
- **WHEN** 没有设备访问权的调用者下发一个不存在的方法
- **THEN** 返回 403 并记入其所属客户的 `denied` 审计；已获授权的调用者得到 400

#### Scenario: 联锁关闭时的未授权调用者
- **GIVEN** 物理命令被禁用（默认）
- **WHEN** 他客户的用户、未绑定的用户、对未分配或不存在设备的调用者下发物理方法
- **THEN** 均返回 403；已授权的调用者返回 409

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
绑定 SHALL 要求配对密钥有效**且**设备归属与用户所属客户一致；密钥错误、设备属于他客户、设备未分配 MUST 返回相同的错误。客户校验与插入 SHALL 是同一条语句，并对用户行与设备行加共享锁，使正在进行的设备或用户转移必然先于或后于它完整生效：转移清除绑定之后，MUST NOT 再出现按转移前状态通过校验的绑定。

#### Scenario: 他客户设备的有效二维码
- **GIVEN** 客户 B 的用户持有客户 A 设备的有效二维码
- **WHEN** 其调用绑定
- **THEN** 返回的错误与“密钥错误”完全一致，且不创建绑定

#### Scenario: 绑定与转移竞态
- **GIVEN** 一次设备转移正持有该设备行的锁
- **WHEN** 此时发起绑定，转移提交（设备改归属并清除绑定）
- **THEN** 绑定按转移后的状态被拒绝（与密钥错误相同的 401），不留下任何绑定行

#### Scenario: 未分配设备不可绑定
- **WHEN** 非平台管理员绑定一台未分配设备
- **THEN** 返回与“密钥错误”一致的错误

### Requirement: 设备范围路由的自动化覆盖
每个含 `:device` 路径参数或 `device` 查询参数的路由 SHALL 被一个路由遍历测试覆盖，该测试断言跨客户用户得到与未绑定用户相同的 403；新增此类路由而未登记 MUST 使测试失败。

#### Scenario: 新增未覆盖的设备路由
- **WHEN** 开发者新增一个带 `:device` 的路由但未加入跨租户测试
- **THEN** 路由遍历测试失败
