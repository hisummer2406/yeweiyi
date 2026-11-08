## ADDED Requirements

### Requirement: 设备当前回报与本机草稿分开
储罐设置与参数修正页 SHALL 显示设备上报的当前配置及上报时间，并与本机草稿分开；进入页面 SHALL NOT 用设备值覆盖草稿，也 SHALL NOT 把草稿显示为设备值。
用户 SHALL 能通过「导入设备值」把设备回报填入表单。设备从未上报 SHALL 显示"设备尚未上报配置"，`null` 显示"—"，0 照常显示。

#### Scenario: 进入页面
- **WHEN** 本机有该罐草稿、设备也上报了配置
- **THEN** 两者分开显示，表单仍是草稿内容

### Requirement: 管理员下发配置
设备已开放远程配置时，客户管理员与平台管理员 SHALL 能把表单与设备回报不同的开放字段下发；员工 SHALL 看不到下发入口。
原因 SHALL 必填；确认框 SHALL 列出设备、罐号与修改前后；确认时 SHALL 重读设备列表，设备不再是当前油站的设备则停止。请求 SHALL 带本次确认的
`requestId` 与 `expectedStationId`。密度、罐高、低水位、低温、物料颜色、介质文本 SHALL NOT 下发。

#### Scenario: 只发不同的字段
- **WHEN** 设备高液位 600，表单高液位 610、高高液位与设备相同
- **THEN** `args` 为 `{"tank":1,"alarmHighLevelMm":610}`

#### Scenario: 员工
- **WHEN** `customer_user` 打开储罐设置
- **THEN** 只有本机草稿与设备回报，没有下发按钮

### Requirement: 命令进度与回显
提交成功后 SHALL 打开操作记录页跟踪该命令：按 `terminal` 轮询，`received` 不表示完成；`succeeded` 后读回设备配置，回报时间不早于下发且字段一致才显示"设备回报值已一致"。
`expired` 与交付未确认 SHALL 显示"结果无法确认"；超时 SHALL 先按 `requestId` 找回，找不到只允许同编号重试，MUST NOT 换新编号自动重发。页面隐藏时 SHALL 停止轮询。

#### Scenario: 超时找回
- **WHEN** 提交超时，按 `requestId` 找到命令
- **THEN** 打开操作记录页跟踪该命令，不再次提交

### Requirement: 补录出入库
管理员 SHALL 能在补录页为当前油站设备的可见罐提交一条入库或出库补录（时间、前高度、升数、原因），时间不得晚于现在；成功或同 `requestId` 重放 SHALL 提示已补录；
员工 SHALL 看不到提交按钮。

#### Scenario: 补录入库
- **WHEN** 管理员填写 1 号罐、昨天 08:30、前高 1333.3 mm、540 L、原因"断电漏记"并确认
- **THEN** 发送 `POST /api/devices/:device/stock-events`，`direction=IN`、`time` 为带时区的 RFC 3339
