## Purpose

让报警/卸油事件的 MQTT 上报结果诚实反映实际投递情况，且网络故障导致的失败不等于数据永久丢失——这条路径是小程序报警实时推送依赖的同一条通道。

## ADDED Requirements

### Requirement: 上报结果如实反映投递状态
`sendAlarms`/`sendUnloadOils` SHALL 仅在消息确实成功发布到 MQTT 时返回成功，发布失败或客户端未连接时 SHALL 返回失败，不得无条件返回成功。

#### Scenario: MQTT 客户端未连接时尝试上报
- **WHEN** 设备与 MQTT broker 处于断连状态，此时触发一次报警上报
- **THEN** 上报方法返回失败，而不是像此前那样无条件返回成功

#### Scenario: 发布过程抛出异常
- **WHEN** MQTT 发布调用过程中抛出 `MqttException` 或 `MqttPersistenceException`
- **THEN** 上报方法返回失败，异常被记录而不是被静默吞掉

### Requirement: 失败的上报进入本地补投队列
上报失败时，设备 SHALL 将该条消息保存到本地持久化队列，且 SHALL NOT 因为上报失败而丢弃这条数据。

#### Scenario: 断连期间产生一条报警
- **WHEN** 设备在与 MQTT broker 断连期间检测到一次新报警并尝试上报
- **THEN** 该条报警被写入本地队列，而不是既不上报也不留痕

### Requirement: 连接恢复后自动补投队列中的消息
设备与 MQTT broker 重新建立连接后，SHALL 自动尝试补投本地队列中所有未成功投递的消息，成功后 SHALL 将其从队列中移除。

#### Scenario: 网络恢复
- **WHEN** 设备与 MQTT broker 的连接从断开恢复为已连接
- **THEN** 本地队列中此前未成功投递的报警/卸油事件被自动重新发布，成功的条目从队列中移除，失败的条目保留以待下次重试
