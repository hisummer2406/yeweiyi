## Purpose

让 PC（局域网直连）与云端（经 MQTT）都能修改设备本地的油罐/探针配置（`OilCanSet` 字段），并让设备据此更新自己的本地数据。

## ADDED Requirements

### Requirement: LAN 写接口同步返回应用结果
设备 SHALL 提供一个本机 HTTP 写接口，接受油罐/探针配置更新请求，并在同一次 HTTP 响应中返回该配置是否成功应用。

#### Scenario: PC 修改高油位报警阈值
- **WHEN** PC 通过 LAN 直连向设备发起一次油罐配置更新请求（如修改 `heightOilAlarm`）
- **THEN** 设备在同一次 HTTP 响应中返回更新后的实际生效值，调用方无需二次查询即可确认结果

### Requirement: MQTT 配置下发被正确处理
设备 SHALL 处理 `thing/service/requestTankSet` 与 `thing/service/requestProbeSet` 主题收到的配置更新消息，将其应用到本地 `OilCanSet` 数据，不再静默忽略。

#### Scenario: 云端下发一条探针配置更新
- **WHEN** 设备通过 MQTT 收到 `requestProbeSet` 主题的一条探针配置更新消息
- **THEN** 设备将对应字段更新到本地数据库，并按 `command-acknowledgement` 能力的要求发布确认应答

### Requirement: 重复投递的配置命令不产生副作用
同一条配置更新命令被重复投递时，设备 SHALL 产生与只应用一次完全相同的最终状态。

#### Scenario: MQTT 重传导致同一命令到达两次
- **WHEN** 设备因网络重传收到两条内容完全相同的配置更新消息
- **THEN** 应用后的最终配置值与只收到一条时完全一致，不产生叠加或异常
