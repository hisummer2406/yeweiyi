## Purpose

让 PC 与云端都能对设备上已存在的 `OilInOut`（出入库/卸油）、`CommitWork`（交班）历史记录做按 id 修正，而不仅仅是新增。

## ADDED Requirements

### Requirement: 按 id 定位并更新已有记录
设备 SHALL 支持根据 `(deviceName, 本地id)` 定位一条已存在的 `OilInOut` 或 `CommitWork` 记录并更新其字段，不得因为收到修正请求而误创建一条新记录。

#### Scenario: 修正一条出入库记录的体积字段
- **WHEN** 收到一条针对某个已存在 `OilInOut.id` 的修正请求，包含修改后的 `endVolum`
- **THEN** 设备更新该条已存在记录的 `endVolum` 字段，数据库中该 id 对应的记录数量不变（不新增行）

### Requirement: 目标记录不存在时明确失败
若修正请求引用的 id 在设备本地不存在，设备 SHALL 返回明确的失败结果，不得静默忽略，也不得创建一条新记录顶替。

#### Scenario: 修正请求引用了一个不存在的 id
- **WHEN** 收到一条修正请求，其 id 在设备本地数据库中查不到对应记录
- **THEN** LAN 路径返回明确的失败状态码；MQTT 路径按 `command-acknowledgement` 能力发布状态为失败的确认应答，且都不产生新记录

### Requirement: 两条写入路径共用同一套字段契约
LAN 直连修正请求与 MQTT 修正请求 SHALL 使用相同的字段命名与类型，不得为两条路径各自定义不同的数据结构。

#### Scenario: 同一修正操作通过两条路径发起
- **WHEN** 分别通过 LAN 接口和 MQTT 主题对同一条记录发起内容相同的修正
- **THEN** 两次请求使用的字段名、类型完全一致，应用后的最终记录状态也完全一致
