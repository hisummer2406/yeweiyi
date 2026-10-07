## ADDED Requirements

### Requirement: 设备列表
`/ops/devices` SHALL 通过 `GET /api/devices` 显示业务库中已登记的设备，行字段为后端实际返回的
`device_name`、`is_online`、`last_online_at`、`last_offline_at`、`updated_at`。页面 SHALL NOT 显示后端没有返回的字段（如客户归属）。

#### Scenario: 有设备
- **WHEN** 平台管理员打开 `/ops/devices` 且后端返回非空 `rows`
- **THEN** 页面按行显示设备名与在线状态，时间字段按本地时间显示，空值显示为占位符

#### Scenario: 没有设备
- **WHEN** 后端返回空 `rows`
- **THEN** 页面显示空状态并提供「登记设备」入口

#### Scenario: 加载与失败
- **WHEN** 列表请求进行中或失败
- **THEN** 页面分别显示加载态或错误反馈，且不清空此前已显示的数据以外的内容，也不把失败当作空列表

### Requirement: 出厂编号规范化
系统 SHALL 把设备出厂编号作为字符串处理：仅去除首尾空白并转为大写，SHALL NOT 转为数字，前导零 SHALL 保留。
编号规则由 `src/utils/deviceCode.ts` 集中实现，且 SHALL NOT 被写成跨型号的协议：云端不强制前缀或长度，
默认前缀 `ZCT` 是待产品确认的前端常量。

- 匹配 `^[A-Z]{3}\d+$` 的输入视为完整编号，原样使用。
- 全为数字的输入视为短码，SHALL 给出候选完整编号（默认前缀 + 短码）并标记为「需确认」。
- 其他输入（空、含空白、含 `/ ? # \`、其他格式）SHALL 判为无效。

#### Scenario: 直接输入完整编号
- **WHEN** 管理员输入 `zct000001`
- **THEN** 规范化结果为 `ZCT000001`，无需确认

#### Scenario: 输入短码
- **WHEN** 管理员输入 `000001`
- **THEN** 候选完整编号为 `ZCT000001`，状态为需确认，前导零保留

#### Scenario: 不同设备不混淆
- **WHEN** 分别输入 `000001` 与 `000002`
- **THEN** 得到 `ZCT000001` 与 `ZCT000002` 两个不同结果，任何输入都不会落到固定的默认编号

#### Scenario: 无效输入
- **WHEN** 输入为空、`ZCT/01`、`12 34` 或 `ab`
- **THEN** 判为无效，页面禁止提交并说明原因

### Requirement: 登记设备并分配客户
平台管理员 SHALL 能在 `/ops/devices` 的登记表单中填写出厂编号并选择客户，提交 `POST /api/devices`，请求体为
`{deviceName, customerId}`。`customerId` MUST 来自客户下拉的选择，页面 SHALL NOT 要求手填数字 id。
短码候选必须由管理员确认后才允许提交。

#### Scenario: 首次登记成功
- **WHEN** 提交有效编号与已选客户，后端返回 201 `{deviceName,customerId,created:true}`
- **THEN** 表单关闭，列表刷新，页面提示该设备已登记并说明「后台将自动采集和回补，无需等待扫码」，且不显示任何落库进度

#### Scenario: 重复登记同一客户
- **WHEN** 提交的设备已属于所选客户，后端返回 200 `created:false`
- **THEN** 页面提示「设备已登记」，列表刷新，不重复创建，也不提示成功创建

#### Scenario: 未选客户
- **WHEN** 未选择客户
- **THEN** 提交被拒绝并提示选择客户，不发出请求

#### Scenario: 短码未确认
- **WHEN** 输入的是短码且管理员未确认候选完整编号
- **THEN** 提交被拒绝，不发出请求

#### Scenario: 提交防重
- **WHEN** 登记请求仍在进行时再次触发提交
- **THEN** 不发出第二个请求，提交按钮显示加载态

### Requirement: 冲突与权限处理
登记失败 SHALL 按后端状态码给出可区分的反馈。系统 SHALL NOT 自动迁移已属于其他客户的设备，也 SHALL NOT 提供迁移入口。

#### Scenario: 设备属于其他客户
- **WHEN** 后端返回 409
- **THEN** 页面提示设备已属于其他客户，表单保持打开且不改变任何归属

#### Scenario: 参数或客户无效
- **WHEN** 后端返回 400（如客户不存在、编号含非法字符）
- **THEN** 页面显示后端返回的 `error` 文本，表单保持打开

#### Scenario: 无权限
- **WHEN** 后端返回 403
- **THEN** 页面提示无权限并保持不变，不清除 token；401 仍按全局规则清 token 并回登录页

#### Scenario: 非平台管理员
- **WHEN** 非 `platform_admin` 角色访问 `/ops/devices`
- **THEN** 路由层按 `canPlatformAdmin` 拒绝，页面不渲染登记入口

### Requirement: 客户选择与内联创建
客户下拉 SHALL 读取 `GET /api/customers`（响应 `{customers:[{id,name,createdAt}]}`）。管理员 SHALL 能在登记表单内通过
`POST /api/customers`（`{name}`）创建客户，创建成功后列表刷新并自动选中新客户。

#### Scenario: 选择已有客户
- **WHEN** 客户列表非空
- **THEN** 下拉按名称显示客户，选中后以其 `id` 作为 `customerId`

#### Scenario: 没有客户
- **WHEN** 客户列表为空
- **THEN** 下拉显示空状态并提供创建客户的入口

#### Scenario: 内联创建成功
- **WHEN** 管理员提交客户名称，后端返回 201 `{id,name,createdAt}`
- **THEN** 客户列表刷新并自动选中该客户，登记表单中已填的编号保持不变

#### Scenario: 客户名称冲突
- **WHEN** 后端返回 409
- **THEN** 页面提示名称已存在，不创建，已填内容保持不变

### Requirement: 范围边界与依赖说明
本能力 SHALL NOT 生成或展示配对密钥、配对二维码或任何重打标签入口，SHALL NOT 调用
`POST /api/devices/:device/pairing-secret`，也 SHALL NOT 以扫码作为启动采集的条件。设备登记与用户绑定 SHALL 分开说明。
小程序扫码绑定的授权规则未确认前，页面与交付说明 SHALL NOT 声称扫码闭环已完成。

#### Scenario: 不触发密钥轮换
- **WHEN** 管理员执行任意登记操作（含重复登记、失败重试）
- **THEN** 前端只调用 `POST /api/devices`，不调用 pairing-secret 接口
