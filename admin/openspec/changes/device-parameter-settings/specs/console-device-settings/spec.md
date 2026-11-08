## ADDED Requirements

### Requirement: 参数设置与操作记录页签
设备详情 SHALL 提供"参数设置"（`?tab=settings`）与"操作记录"（`?tab=commands`）页签，后台对平台管理员与客户管理员可见。
"远程控制"菜单（`/console/issue-command`）SHALL 列出调用者可见的设备，并链接到对应设备的参数设置页签。

#### Scenario: 从远程控制菜单进入
- **WHEN** 客户管理员打开 `/console/issue-command` 并点击设备 D1
- **THEN** 打开 `/console/devices/D1?tab=settings`

### Requirement: 显示设备当前配置
参数设置 SHALL 按罐显示 `GET /api/devices/:device/tank-settings` 的镜像及其 `reportedAt`；字段为 `null` SHALL 显示"—"，`0` SHALL 照常显示；
从未上报 SHALL 提示"设备尚未上报配置"，读取失败 SHALL 显示错误而不是空表单。罐高、罐型、传感器长度、密度、低温报警 SHALL 只读。

#### Scenario: 未上报的字段
- **WHEN** 镜像里 `alarmHighTempC` 为 `null`、`alarmLowLowLevelMm` 为 `0`
- **THEN** 前者显示"—"，后者显示 0

### Requirement: 远程配置开关
页面 SHALL 显示该设备远程配置是否已开放。平台管理员 SHALL 能经 `PUT /api/devices/:device/config-commands` 开放或关闭，原因必填；
客户管理员 SHALL 只看到状态。设备未开放时 SHALL 不提供下发按钮并说明原因。

#### Scenario: 客户管理员看到未开放
- **WHEN** 客户管理员打开未开放设备的参数设置
- **THEN** 显示"该设备未开放远程配置"，没有下发按钮，也没有开关

### Requirement: 编辑与确认下发
表单 SHALL 只提交相对当前配置改动过的可编辑字段；没有改动 SHALL 不可提交。提交前 SHALL 做范围与四级液位顺序预检。
确认弹窗 SHALL 显示设备完整编号、油站、罐号、每个字段修改前后与单位，原因必填；取消 SHALL 不发请求。确认时 SHALL 重新读取设备列表，
油站与表单打开时不同则停止提交并提示刷新。请求 SHALL 带本次确认生成的 `requestId` 与表单打开时的 `expectedStationId`；提交中 SHALL 禁止重复点击。

#### Scenario: 只发改动
- **WHEN** 用户只把高液位从 600 改为 610
- **THEN** 请求 `args` 为 `{"tank":1,"alarmHighLevelMm":610}`

#### Scenario: 打开表单后换机
- **WHEN** 确认时设备列表显示设备已不在原油站
- **THEN** 不发送命令，提示设备归属已变化并刷新

### Requirement: 跟踪命令与核对回显
提交成功后页面 SHALL 按 `terminal` 轮询命令状态，`received` SHALL 显示"设备已接收，等待执行结果"而不是成功。`succeeded` 后 SHALL 读回设备配置：
`reportedAt` 不早于命令下发时间且提交字段全部相等才显示"设备回报值已一致"；否则显示等待或不一致。`rejected`/`failed` SHALL 显示设备原因，
`expired` SHALL 显示"结果无法确认，请核对设备当前值"。页面隐藏时 SHALL 暂停轮询。

#### Scenario: 成功但还没回报
- **WHEN** 命令 `succeeded`，镜像 `reportedAt` 仍早于下发时间
- **THEN** 显示"执行成功，等待设备回报"，不显示已生效

### Requirement: 超时与重复提交
提交请求超时或云端答 `command_delivery_unconfirmed` 时，页面 SHALL 先按 `requestId` 找回命令并继续跟踪；找不到 SHALL 显示"结果未确认"，
只提供"用同一提交编号重试"，MUST NOT 自动生成新编号重发。`config_command_in_flight` SHALL 提示并跟踪在途命令；`device_station_changed`
SHALL 丢弃表单并刷新；`config_commands_disabled` SHALL 按 `scope` 区分系统未开放与设备未开放。

#### Scenario: 超时后找回
- **WHEN** 提交超时，按 `requestId` 查到一条 `received` 的命令
- **THEN** 页面跟踪该命令，不再次提交

### Requirement: 整机设置
页面 SHALL 显示 `GET /api/devices/:device/device-settings` 的出入库判定阈值与上报时间；只有平台管理员 SHALL 能编辑并下发
`config.setDeviceSettings`，规则同罐设置。

#### Scenario: 客户管理员
- **WHEN** 客户管理员查看整机设置
- **THEN** 只读显示，没有编辑入口

### Requirement: 操作记录
操作记录页签 SHALL 分页列出 `GET /api/devices/:device/commands`：时间、方法、对象、参数、操作人、原因、状态与错误/结果；状态 SHALL 以云端为准。

#### Scenario: 被设备拒绝的命令
- **WHEN** 一条命令 `rejected`，`errorCode=INVALID_PARAMS`
- **THEN** 该行显示"被设备拒绝"和设备给出的原因
