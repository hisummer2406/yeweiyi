## Purpose

承担 `MainActivity` 内响应报警状态变化、驱动报警相关 UI 更新的职责，与导航、轮询、业务动作解耦。

## ADDED Requirements

### Requirement: 报警响应行为与拆分前一致
报警协调器 SHALL 产生与拆分前 `MainActivity` 完全相同的报警 UI 响应行为（提示、跳转、状态展示）。

#### Scenario: 收到一次新报警
- **WHEN** 系统检测到一次新报警
- **THEN** 报警协调器触发的 UI 响应（提示展示、相关页面刷新）与拆分前一致

### Requirement: 数据依赖显式声明
若报警协调器落地时 `AlarmRepository`（另一变更 `layer-data-and-hardware-boundaries` 的产出）尚未就绪，报警协调器 SHALL 明确标注其对 `DBDataManager` 的临时依赖，不得掩盖这一依赖关系。

#### Scenario: AlarmRepository 尚未就绪时的临时状态
- **WHEN** `layer-data-and-hardware-boundaries` 的报警领域任务组尚未完成
- **THEN** 报警协调器代码中带有清晰标注（如 TODO 注释）说明其仍依赖 `DBDataManager`，而非静默直接调用
