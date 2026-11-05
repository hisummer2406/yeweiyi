## ADDED Requirements

### Requirement: 设备详情页签
`/console/devices/:deviceName` SHALL 提供三个页签：实时数据、报警记录、出入库记录；`?tab=` 取值 `snapshot`、`alarms`、`stock` 决定初始页签，缺省或非法值为实时数据。
切换页签 SHALL 更新 `?tab=`。报警和出入库页签 SHALL 复用与记录页相同的组件，设备取自路由，不显示设备选择。
设备名 SHALL 按 URL 解码后使用，并显示在页面标题。

#### Scenario: 默认页签
- **WHEN** 用户打开 `/console/devices/D1`
- **THEN** 显示实时数据页签

#### Scenario: 指定页签
- **WHEN** 用户打开 `/console/devices/D1?tab=alarms`
- **THEN** 显示报警记录页签，请求的是 `D1` 的报警

#### Scenario: 非法页签
- **WHEN** `?tab=foo`
- **THEN** 回退到实时数据页签

#### Scenario: 特殊字符的设备名
- **WHEN** 路由是 `/console/devices/ZCT%2F01`
- **THEN** 页面使用解码后的 `ZCT/01` 请求数据，请求路径中再次转义

### Requirement: 实时数据页签
实时数据页签 SHALL 通过 `GET /api/devices/:device/telemetry/latest` 显示每个油罐的最新遥测，使用与设备总览相同的油罐卡片（共享组件）。
404 表示「已授权但从未上报」，SHALL 显示「该设备还没有上报过数据」而不是错误。页面 SHALL 显示上报时间，并每 30 秒自动刷新。

#### Scenario: 有遥测
- **WHEN** 接口返回 2 个油罐
- **THEN** 显示 2 张油罐卡片和上报时间

#### Scenario: 从未上报
- **WHEN** 接口返回 404
- **THEN** 显示「该设备还没有上报过数据」，不显示错误

#### Scenario: 加载失败
- **WHEN** 接口返回 500
- **THEN** 显示错误与重试按钮

#### Scenario: 无权访问
- **WHEN** 接口返回 403（不是账号失效）
- **THEN** 显示无权查看该设备的提示，不显示成「从未上报」

### Requirement: 共享油罐卡片
设备总览与设备详情的实时数据页签 SHALL 使用同一个油罐卡片组件，显示油罐号、油品、告警状态、探针未连接标记，以及液位、容积、水高、温度、质量。
抽出共享组件 SHALL NOT 改变设备总览现有的显示和点击行为。

#### Scenario: 总览行为不变
- **WHEN** 用户在设备总览点击某张油罐卡片
- **THEN** 仍然跳转到该设备的详情页

#### Scenario: 异常标记
- **WHEN** 油罐 `alarmState` 不是 `NORMAL` 且 `probeConnected=false`
- **THEN** 卡片显示告警标签和「探针未连接」标签
