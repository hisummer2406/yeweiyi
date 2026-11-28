## ADDED Requirements

### Requirement: TB 保存软件二进制并提供设备下载鉴权
系统 SHALL 只用 TB CE 公开 API 管理 SOFTWARE 包，业务库只保存发布元数据和分配意图/审计。MUST NOT 直接读写 TB 数据库、依赖 PE 分组或建立第二套公开 APK 下载库。

#### Scenario: 未分配或凭据错误的设备下载
- **WHEN** 设备请求非其当前分配包或使用错误 token
- **THEN** TB 拒绝，代理不能绕过 TB 设备鉴权

### Requirement: 发布管理仅允许平台管理员
包导入/查询和设备分配/撤销/状态管理 SHALL 使用现有业务 JWT 与数据库当前角色。非平台管理员 SHALL 在解析请求体和查对象前被拒绝；新增路由 SHALL 纳入当前租户测试分类。业务账号凭据与设备 token MUST NOT 混用。

#### Scenario: 客户管理员尝试分配包
- **WHEN** 本客户管理员调用设备更新管理接口
- **THEN** 按角色拒绝并写 denied 审计，不改变 TB 分配

#### Scenario: 账号停用后重放请求
- **WHEN** 已停用平台管理员使用历史 JWT 与 requestId
- **THEN** 重新鉴权失败，不返回历史操作内容或继续写入

### Requirement: 包导入须有界、可核对且幂等
导入 SHALL 校验 requestId、真实文件大小/摘要与发布描述，限制暂存配额和并发，并只在 TB 上传后读回核对成功时置 ready。相同 requestId 与同内容 SHALL 幂等，不同内容 SHALL 返回 409。相同 title/code MUST NOT 绑定不同文件。401 重试 SHALL 重开暂存文件而非复用已消费的 reader。

#### Scenario: TB 上传完成但响应丢失
- **WHEN** Go 连接超时后恢复
- **THEN** 按已保存 packageId 和唯一版本读回核对，不重复创建或误删可能成功的包

#### Scenario: 超过大小限额
- **WHEN** 请求体超过配置上限
- **THEN** 有界拒绝，暂存最终清理，不把完整请求体读进内存

### Requirement: 分配意图和审计须先耐久保存
设备分配/撤销 SHALL 带 requestId、expectedRevision 和 reason，SQL SHALL 复核当前角色、设备状态及删除栅栏。意图与 allowed 审计 SHALL 同事务保存；202 SHALL 仅表示已受理。worker SHALL 依据租约/代次与步骤对账，不在长数据库事务内等待 TB。

#### Scenario: 两个管理员并发改配
- **WHEN** 两个请求使用相同 expectedRevision 修改同一设备
- **THEN** 至多一个新意图生效，另一请求得到 revision 或同步中冲突

#### Scenario: Go 在保存意图后重启
- **WHEN** TB 尚未同步完成
- **THEN** worker 从耐久步骤恢复，不能只因 API 受理就显示安装完成

### Requirement: 更新 TB 设备必须保留无关字段
softwareId 更新 SHALL 读取完整原始设备对象并保留未知字段，仅改变预期字段并带最新 TB version。409 SHALL 重新读取并有界重试；MUST NOT 用精简 Device DTO 覆盖整个设备。

#### Scenario: 设备含固件与额外配置
- **WHEN** 分配 SOFTWARE 包
- **THEN** firmwareId、deviceProfileId、additionalInfo 等无关值保持不变

### Requirement: 标准分配与发布说明必须收敛后生效
分配 SHALL 先写过渡 enabled=false，再修改 softwareId，等待标准 sw_* 匹配，最后写匹配 enabled=true 的 appUpdateManifest 并读回。失败 SHALL 保留 pending/reconciling 或明确 failed，不盲目补偿。succeeded 审计 SHALL 仅表示分配同步完成。

#### Scenario: TB 包已分配但 manifest 写入失败
- **WHEN** Go 操作中途失败
- **THEN** 设备不能开始该不完整目标，worker 可对账收敛，API 不声称设备已更新

### Requirement: 撤销须阻止后续开始并明确在途边界
撤销 SHALL 先设置 enabled=false，再清除单设备 softwareId 并检查实际属性和 profile 继承。首版 SHALL 检查专用 profile 无默认软件包。MUST NOT 把清除覆盖后继承默认包视为成功撤销；MUST NOT 声称撤销可终止已提交系统安装。

#### Scenario: 设备已下载但未安装
- **WHEN** 管理员完成撤销且设备再次复核
- **THEN** 安装被阻止，本地文件不再具备可安装资格

#### Scenario: 系统安装已提交后撤销
- **WHEN** Android 已提交 PackageInstaller 会话
- **THEN** 管理响应说明在途状态未知或可能继续，保留实际设备报告

### Requirement: 状态查询须区分期望、观察与设备结果
服务端 SHALL 分别返回业务期望分配、TB 实际分配/属性、完整设备 appUpdateReport 和新鲜度。设备报告 SHALL 按 revision/attemptId/reportSeq 关联，MUST NOT 将多个不同时间的 latest key 拼成一次成功结果。TB 不可达、无报告、不支持、报告过期与未确认 SHALL 可区分。

#### Scenario: 新目标分配后旧 UPDATED 仍在 TB
- **WHEN** 查询新目标
- **THEN** 旧结果不证明新目标成功，界面和 API 能标出不匹配或尚未报告

### Requirement: 设备删除与换客户须处理更新意图
新分配表 SHALL 纳入设备删除及残留验证清单。未完成删除 SHALL 拒绝新分配；worker MUST NOT 在删除后重新写入设备关联。换客户 SHALL 使旧更新安排失效并走撤销对账；全局发布包不随单个设备删除。

#### Scenario: 对账期间设备被删除
- **WHEN** worker 的延迟响应返回
- **THEN** SQL 代次/删除栅栏阻止其回写，数据库无新增残留

### Requirement: HTTPS 代理与大包限制须按路由配置
公网或内网 HTTPS 入口 SHALL 仅代理所需设备 GET attributes/software，保持 TB 管理端口隔离，并脱敏 token 路径与错误日志。导入路由 SHALL 同时满足 Nginx、Go、TB 请求体和 multipart 的限额与超时要求，其他业务路由保持既有限额。

#### Scenario: 代理收到 TB 管理 API 请求
- **WHEN** 外部调用 TB 登录、包管理或其他非白名单路径
- **THEN** 不得通过设备代理访问 TB 管理接口

#### Scenario: 导入大于 62 MiB 且不超限的 APK
- **WHEN** 发布工具通过管理 API 上传
- **THEN** 各层均能处理且内存/暂存有界，不因遗留 1 MiB 或 multipart 默认限制失败

### Requirement: 更新必须支持无互联网的私有部署
在设备可访问内网 TB/Go/HTTPS 代理时，检查、分块下载、安装和结果回报 SHALL 不依赖互联网、公网下载或在线许可证。私有 CA、配置和发布工具 SHALL 可离线交付。

#### Scenario: 关闭外网，仅保留内网连接
- **WHEN** 内网已导入正式软件包并分配给测试设备
- **THEN** 可完成完整更新闭环并保留审计和结果
