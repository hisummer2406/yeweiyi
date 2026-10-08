## MODIFIED Requirements

### Requirement: 设备列表
`/console/assets/devices`（原 `/ops/devices`）SHALL 通过 `GET /api/devices` 显示业务库中调用者可见的已登记设备，行字段为后端实际返回的
`device_name`、`is_online`、`last_online_at`、`last_offline_at`、`updated_at`，以及「所属油站」（`station_id`，字段缺失显示「未知」，`null` 显示「未分组」）；
平台管理员下另显示「所属客户」（`customer_id`，字段缺失显示「未知」，`null` 显示「未分配」）。页面 SHALL NOT 显示后端没有返回的字段。
列表的整体角色与操作差异见 `console-asset-management`。

#### Scenario: 有设备
- **WHEN** 平台管理员打开 `/console/assets/devices` 且后端返回非空 `rows`
- **THEN** 页面按行显示设备名、所属客户、所属油站与在线状态，时间字段按本地时间显示，空值显示为占位符

#### Scenario: 没有设备
- **WHEN** 后端返回空 `rows`
- **THEN** 页面显示空状态，平台管理员下并提供「登记设备」入口

#### Scenario: 加载与失败
- **WHEN** 列表请求进行中或失败
- **THEN** 页面分别显示加载态或错误反馈，且不清空此前已显示的数据以外的内容，也不把失败当作空列表

### Requirement: 登记设备并分配客户
平台管理员 SHALL 能在 `/console/assets/devices` 的登记表单中填写出厂编号并选择客户，提交 `POST /api/devices`，请求体为
`{deviceName, customerId}`。`customerId` MUST 来自客户下拉的选择，页面 SHALL NOT 要求手填数字 id。
短码候选必须由管理员确认后才允许提交。登记成功后的「分配油站」动作见 `console-asset-management`。

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
- **WHEN** 客户管理员打开 `/console/assets/devices`
- **THEN** 页面不渲染「登记设备」入口（云端对非平台管理员的登记请求仍会拒绝，这里只是不展示）
