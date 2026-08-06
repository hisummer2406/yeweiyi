## Purpose

承担 `MainActivity` 内出入库等业务动作按钮处理与密码校验职责，与导航、轮询、报警响应解耦。

## ADDED Requirements

### Requirement: onClick 绑定必须编译期可见
所有原先通过 `android:onClick="方法名"` 字符串反射绑定、且属于业务动作职责的方法，SHALL 改为在代码中用 `setOnClickListener` 显式绑定。

#### Scenario: 移动一个业务动作按钮的处理方法
- **WHEN** 开发者把某个业务动作按钮的处理方法移动到业务动作类中或改名
- **THEN** 编译失败，直到调用点被相应更新

### Requirement: 业务动作行为与拆分前一致
业务动作类 SHALL 产生与拆分前 `MainActivity` 完全相同的出入库动作与密码校验行为。

#### Scenario: 密码校验失败
- **WHEN** 用户在需要密码校验的操作中输入错误密码
- **THEN** 校验失败的提示与阻止后续动作的行为与拆分前一致
