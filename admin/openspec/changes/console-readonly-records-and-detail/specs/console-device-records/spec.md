## ADDED Requirements

### Requirement: 选择设备后查看记录
报警记录（`/console/records/alarms`）、入库记录（`/console/records/stock-in`）、出库记录（`/console/records/stock-out`）三个页面 SHALL 在顶部提供设备选择，
选项来自 `GET /api/devices`；URL 带 `?device=` 时 SHALL 直接选中该设备。云端记录接口必须带 `device`，页面 SHALL NOT 在未选设备时发请求，也 SHALL NOT 提供「全部设备」。

#### Scenario: 直达某台设备
- **WHEN** 用户打开 `/console/records/alarms?device=D1` 且 `D1` 在可见设备列表里
- **THEN** 设备选择显示 `D1`，并请求 `D1` 的报警记录

#### Scenario: 没有可见设备
- **WHEN** `GET /api/devices` 返回空 `rows`
- **THEN** 页面显示「还没有绑定设备」，不请求任何记录

#### Scenario: URL 里的设备不在可见列表
- **WHEN** `?device=` 指向用户看不到的设备
- **THEN** 页面不请求该设备的记录，提示设备不可见，并保留设备选择让用户重选

#### Scenario: 切换设备
- **WHEN** 用户在下拉里改选另一台设备
- **THEN** 页面请求新设备的记录，并把 `?device=` 更新为新设备

### Requirement: 查询窗口与排序
记录请求 SHALL 带显式的 `beginTime`、`endTime`（RFC3339）、`sort=false`（最新在前）和 `limit=500`。默认窗口 SHALL 是最近 24 小时，用户 SHALL 能通过范围选择器修改。
页面 SHALL 固定提示：设备转移给当前客户之前的记录不会显示。

#### Scenario: 默认请求
- **WHEN** 用户首次进入某设备的报警记录
- **THEN** 请求带 `device`、最近 24 小时的 `beginTime`/`endTime`、`sort=false`、`limit=500`，表格最新记录在最前

#### Scenario: 修改时间范围
- **WHEN** 用户选择新的起止时间
- **THEN** 以新窗口重新请求，表格回到第一页

#### Scenario: 转移前的记录
- **WHEN** 页面显示记录
- **THEN** 页面同时显示「设备转移给当前客户之前的记录不会显示」的说明

### Requirement: 前端分页与截断提示
记录 SHALL 一次取窗口内最新 500 条，在前端分页显示。报警满 500 条，或出入库响应 `truncated=true`，页面 SHALL 提示「只显示最新 500 条，请缩小时间范围」，
SHALL NOT 假装这就是窗口内的全部记录。

#### Scenario: 不满 500 条
- **WHEN** 报警返回 37 条
- **THEN** 表格分页显示这 37 条，不显示截断提示

#### Scenario: 报警满 500 条
- **WHEN** 报警返回恰好 500 条
- **THEN** 显示截断提示

#### Scenario: 出入库被截断
- **WHEN** 出入库响应 `truncated=true`
- **THEN** 显示截断提示

### Requirement: 报警记录展示
报警记录 SHALL 显示时间、油罐号、类型、级别、状态、说明、数值。类型、级别、状态 SHALL 使用与小程序一致的中文文案；映射表里没有的值 SHALL 显示原值，SHALL NOT 显示为空白。
响应带 `X-Records-Partial: true` 时，页面 SHALL 提示「首次同步尚未完成，记录可能不完整」。

#### Scenario: 已知枚举
- **WHEN** 一条报警的 `type=HIGH_LEVEL`、`severity=CRITICAL`、`status=ACTIVE`
- **THEN** 分别显示「高液位」「紧急」「报警中」

#### Scenario: 未知枚举
- **WHEN** 一条报警的 `type` 是映射表里没有的 `NEW_TYPE`
- **THEN** 该列显示 `NEW_TYPE`

#### Scenario: 记录可能不完整
- **WHEN** 报警响应带 `X-Records-Partial: true`
- **THEN** 页面显示不完整提示，同时照常显示已有记录

#### Scenario: 没有报警
- **WHEN** 窗口内没有报警且没有不完整标记
- **THEN** 显示空状态「该时间范围内没有报警」

### Requirement: 出入库记录展示
入库、出库页面 SHALL 只显示对应方向（`IN`/`OUT`）的记录，过滤在前端完成。每行 SHALL 显示结束时间、油罐号、油品、来源（自动/手动）、
实际进出量 `deliveredVolumeL`、净变化 `netVolumeL`、起止液位与起止容积。

#### Scenario: 按方向过滤
- **WHEN** 响应里有 3 条 `IN` 与 2 条 `OUT`，用户在入库页
- **THEN** 只显示 3 条 `IN`

#### Scenario: 来源文案
- **WHEN** 一条记录 `source=AUTO`、另一条 `source=MANUAL`
- **THEN** 分别显示「自动」「手动」

### Requirement: 出入库降级如实提示
页面 SHALL 如实展示出入库响应里的降级信息，SHALL NOT 把不完整或不可读显示成「没有记录」。

- `partial=true`：提示「首次同步尚未完成，记录可能不完整」。
- `legacySkipped > 0`：提示「有 N 条升级前的旧记录无法显示」。
- `invalidSource > 0`：提示有 N 条记录来源异常未显示。
- `folded > 0`：显示说明「有 N 条更正记录已合并」。
- HTTP 502 且 `code=STOCK_EVENT_FORMAT_UNSUPPORTED`，或响应 `unparsable > 0`：以醒目的错误提示「设备固件尚未升级，部分记录无法读取」，并显示云端返回的设备与样例，
  SHALL NOT 显示为空表。502 响应里仍带 `rows` 时，SHALL 同时显示这些已读到的行。

#### Scenario: 旧记录被跳过
- **WHEN** 响应 200 且 `legacySkipped=4`
- **THEN** 显示「有 4 条升级前的旧记录无法显示」，已解析的行照常显示

#### Scenario: 设备固件未升级
- **WHEN** 响应 502、`code=STOCK_EVENT_FORMAT_UNSUPPORTED`、`rows` 为空
- **THEN** 显示醒目错误「设备固件尚未升级」，不显示成「没有出入库记录」的空状态

#### Scenario: 502 但带有已读到的行
- **WHEN** 响应 502 且 `rows` 有 2 行
- **THEN** 同时显示错误提示和这 2 行

#### Scenario: 真的没有记录
- **WHEN** 响应 200，`rows` 为空，且 `partial`、`unparsable`、`legacySkipped` 均为空/0
- **THEN** 显示空状态「该时间范围内没有出入库记录」

### Requirement: 加载、失败与自动刷新
记录页 SHALL 显示加载态；请求失败 SHALL 显示错误并提供重试，SHALL NOT 把失败当作空列表。页面 SHALL 每 30 秒自动刷新一次，并提供手动刷新。
鉴权失败（401、账号已失效的 403）已由全局处理回登录页，页面 SHALL NOT 再重复提示。设备切换或窗口变化后，迟到的旧请求响应 SHALL 被丢弃。

#### Scenario: 请求失败
- **WHEN** 记录请求返回 500
- **THEN** 显示错误提示和重试按钮，表格不显示成空状态

#### Scenario: 旧响应迟到
- **WHEN** 用户切换设备后，上一台设备的响应才返回
- **THEN** 旧响应被丢弃，表格显示新设备的数据

#### Scenario: 鉴权失败
- **WHEN** 记录请求因 401 失败
- **THEN** 页面不弹出任何额外错误提示

#### Scenario: 自动刷新
- **WHEN** 页面停留 30 秒
- **THEN** 以当前设备与窗口重新请求一次
