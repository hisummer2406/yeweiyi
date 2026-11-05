## Purpose

定义设备上所有依赖系统软键盘的输入窗口与页面（备注输入、登录密码、`MainActivity` 挂载的设置类 Fragment）在键盘弹出时必须保持可用——输入内容看得见、确认操作够得着，不因键盘遮挡而卡死操作员的输入流程；同时不同页面之间的键盘窗口状态不能互相残留。

## ADDED Requirements

### Requirement: 软键盘弹出时输入框与确认控件保持可见可达
输入类窗口（`InputDialogActivity` 备注输入、`LoginDialog` 登录/密码校验）在系统软键盘弹出时，SHALL 调整窗口布局使当前正在编辑的输入框与该窗口的确认按钮不被键盘遮挡。

> 原 issue #12 里的第三处窗口 `OilCanSetFragment.showInputPsw`（设置项密码校验弹窗）经代码核实为零调用的死代码，已删除，不再是本能力覆盖的场景——见 design.md 决策11。

#### Scenario: 备注输入时键盘弹出
- **WHEN** 用户在 `InputDialogActivity` 的备注输入框中点击并唤起系统软键盘
- **THEN** 输入框保持可见，用户输入的字符实时可见，且"确定"按钮仍在屏幕可见区域内并可点击

#### Scenario: 登录密码输入时键盘弹出
- **WHEN** 用户在登录密码框中点击并唤起系统软键盘
- **THEN** 密码框与"点击进入"按钮不被键盘遮挡，均可见可点击

### Requirement: `MainActivity` 挂载的设置类页面表单在键盘弹出时不被压缩/遮挡
`OilCanSetFragment`/`SetBaseFragment` 这两个输入框较多、纵向排布的设置页面，SHALL 在系统软键盘弹出时让当前正在编辑的输入框保持可见可用，不被键盘直接盖住。

#### Scenario: 油罐设置页表单靠后的输入框弹出键盘
- **WHEN** 用户在 `OilCanSetFragment` 表单里靠后位置的某个 `EditText` 上点击并唤起系统软键盘
- **THEN** 该输入框不被键盘遮挡，内容可见可编辑

#### Scenario: 基础设置页输入框弹出键盘
- **WHEN** 用户在 `SetBaseFragment` 的某个 `EditText` 上点击并唤起系统软键盘
- **THEN** 该输入框不被键盘遮挡，内容可见可编辑

### Requirement: 含输入框页面的固定底部操作栏在键盘弹出时让位
`MainActivity` 挂载的、含 `EditText` 且布局末尾有一排固定高度底部操作栏（保存/返回上页等按钮）的页面（`OilCanSetFragment`/`SetBaseFragment`/`SetPawFragment`/`BuLuFragment`/`JiLuFragment`/`OilTestFragment`/`CreateOilTableFragment`），SHALL 在软键盘弹出、页面内容区被收缩时隐藏这排底部操作栏，不得让它跟随内容区被顶到键盘正上方与输入框拥挤在一起；键盘收起后 SHALL 恢复显示。

#### Scenario: 表单页面键盘弹出时底部操作栏让位
- **WHEN** 用户在上述任一页面的某个 `EditText` 上点击并唤起系统软键盘，导致页面内容区被收缩
- **THEN** 该页面底部的固定操作栏（保存数据/返回上页等按钮）隐藏，不显示在收缩后的内容区下方

#### Scenario: 键盘收起后底部操作栏恢复
- **WHEN** 用户在上述场景后收起软键盘
- **THEN** 该页面底部的操作栏恢复显示，位置与键盘弹出前一致

#### Scenario: `BuLuFragment` 表单内容区被收缩时输入框保持正常高度
- **WHEN** 用户在 `BuLuFragment` 的某个 `EditText` 上点击并唤起系统软键盘，导致表单内容区被收缩
- **THEN** 该内容区变为可滚动，内部输入框仍保持声明的高度（不被压缩变形），可通过滚动查看

### Requirement: 页面切换后不残留上一页面设置的键盘窗口状态
`OilCanSetFragment`/`SetBaseFragment` 在可见期间临时调整了 `Activity` 的软键盘窗口行为，SHALL 在离开时不影响 `MainActivity` 切换到的下一个页面；密码校验弹窗（`LoginDialog`）确认后 SHALL 不把软键盘状态遗留给确认后显示的新页面。

#### Scenario: 从设置表单页面切换到其它页面不残留键盘设置
- **WHEN** 用户从 `OilCanSetFragment` 或 `SetBaseFragment` 切换到 `MainActivity` 挂载的任意其它页面
- **THEN** 新页面的软键盘窗口行为与未经过这两个页面时一致，不受它们此前临时设置的影响

#### Scenario: 密码校验通过后进入的页面没有键盘残留
- **WHEN** 用户在 `LoginDialog` 密码框中输入正确密码并确认，弹窗关闭并显示校验通过后的目标页面（如 `SetSeniorFragment`）
- **THEN** 目标页面不显示软键盘、布局不被顶起，即使目标页面本身没有任何输入框

### Requirement: 键盘完成键直接触发确认操作
输入类窗口的密码/备注输入框 SHALL 响应系统软键盘的"完成"（IME action done）按键，触发与点击该窗口确认按钮等价的确认逻辑，不要求用户必须先收起键盘再手动点击确认按钮。

#### Scenario: 按键盘完成键提交备注
- **WHEN** 用户在 `InputDialogActivity` 备注输入框中输入内容后，点击软键盘上的"完成"键
- **THEN** 触发与点击"确定"按钮相同的确认逻辑（回传输入内容并关闭输入窗口）

#### Scenario: 按键盘完成键提交登录密码
- **WHEN** 用户在登录密码框中输入内容后，点击软键盘上的"完成"键
- **THEN** 触发与点击"点击进入"按钮相同的确认逻辑

### Requirement: 数字密码输入即时以掩码方式显示
登录密码框的密码输入控件 SHALL 在用户每输入一位数字时立即以掩码字符（如圆点）显示该位，不得明文显示，也不得在整个输入过程中不给出任何逐字符反馈。

#### Scenario: 输入密码单个数字
- **WHEN** 用户在密码输入框中输入一位数字
- **THEN** 该输入框中立即显示一个掩码字符（如圆点）对应这次输入，而不是明文数字，也不是没有任何变化
