## Purpose

承担 `MainActivity` 内定时轮询触发的数据刷新职责，与导航、报警响应、业务动作解耦。

## ADDED Requirements

### Requirement: 轮询触发行为与拆分前一致
轮询器 SHALL 产生与拆分前 `MainActivity` 完全相同的定时刷新行为（触发频率、刷新的数据范围）。

#### Scenario: 定时轮询周期到达
- **WHEN** 轮询周期到达触发时间
- **THEN** 轮询器触发的数据刷新与拆分前 `MainActivity` 中对应逻辑的行为一致

### Requirement: 生命周期绑定正确
轮询器 SHALL 在 `MainActivity` 生命周期结束（销毁）时正确停止，不产生内存泄漏或对已销毁 Activity 的引用。

#### Scenario: Activity 销毁时轮询停止
- **WHEN** `MainActivity` 被销毁
- **THEN** 轮询器的定时任务被取消，不再持有对已销毁 Activity 的引用
