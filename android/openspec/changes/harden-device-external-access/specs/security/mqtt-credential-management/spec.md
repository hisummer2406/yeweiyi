## Purpose

消除 MQTT 连接凭据硬编码在源码中的风险，使其和其余设备凭据一样可以按环境配置、按需轮换，而不需要改代码重新编译。

## ADDED Requirements

### Requirement: MQTT 凭据不得硬编码在源码中
MQTT 客户端连接使用的用户名和密码 SHALL 从构建期注入的配置读取，源码中不得出现明文凭据字面量。

#### Scenario: 检查源码中的凭据
- **WHEN** 在 `service/BackService.java` 及相关代码中搜索 MQTT 连接凭据
- **THEN** 找不到硬编码的用户名/密码字符串字面量，只能找到对配置常量的引用

### Requirement: 缺失配置时明确告警而非静默使用默认值
若构建时未提供 MQTT 凭据配置，构建过程 SHALL 输出明确警告，运行时 SHALL NOT 回退到任何硬编码的默认账号密码。

#### Scenario: local.properties 未配置 MQTT 凭据
- **WHEN** 构建时 `local.properties` 中缺少 MQTT 凭据相关配置项
- **THEN** 构建日志中出现明确的配置缺失警告（呼应现有 `[配置缺失]` 警告模式），且运行时 MQTT 连接不会使用任何硬编码凭据回退
