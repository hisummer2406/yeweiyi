## Why

GitHub issue #12（今日提交，`bug`/`high`）：系统软键盘弹出时会完全遮挡输入框和确认按钮，用户输入过程看不到任何反馈，密码输完后找不到能点的确认按钮，登录/备注/密码类输入功能事实上被键盘卡死。根因是这些输入类窗口都没有显式设置 `windowSoftInputMode`（或等价的 `Window.setSoftInputMode`），键盘弹出时窗口不做任何调整。这是影响所有依赖软键盘输入的现场操作（登录、交班备注、油罐设置密码校验）的功能性缺陷，不是外观打磨，现在做是因为它直接卡住操作员的正常操作路径。

## What Changes

读代码核实后，实际涉及三处独立的输入窗口，比 issue 原文列出的两处（`InputDialogActivity`、`LoginDialog`）多一处，且三处的键盘控制机制并不相同：

| 组件 | 类型 | 当前问题 | 键盘控制点 |
|---|---|---|---|
| `InputDialogActivity`（备注输入，`input_alertdialog.xml`） | 独立 Activity | Manifest 未配置 `windowSoftInputMode` | Manifest `android:windowSoftInputMode` |
| `LoginDialog`（登录密码，`dialog_login.xml`） | `DialogFragment`，挂载于 `MainActivity` | 未设置 softInputMode；`MainActivity` 自身 manifest 条目也没有（历史上有一处 `adjustPan\|stateHidden` 配置，现在是死代码，被完整注释掉，且挂在一个已废弃的重复 `MainActivity` 声明上，与当前生效的 `MainActivity` 声明无关） | `getDialog().getWindow().setSoftInputMode()` |
| `OilCanSetFragment.showInputPsw`（设置项密码校验，`input_psw.xml`） | 普通 `AlertDialog`，由 `OilCanSetFragment` 触发（该 Fragment 挂载于 `MainActivity`） | 同上，未设置 softInputMode | `dialog.getWindow().setSoftInputMode()`（`AlertDialog` 有自己的 Window，不依赖宿主 Activity 的 manifest 配置） |

具体改动：

- 三处均显式设置软键盘弹出时的窗口调整模式（resize 或 pan，具体选择见 design.md）+ `SOFT_INPUT_STATE_HIDDEN`，使键盘弹出时窗口内容让位，不再遮挡输入框与确认按钮
- 三处的密码/备注 `EditText` 加 `imeOptions="actionDone"` 并绑定 `setOnEditorActionListener`，让键盘的"完成"键直接触发对应确认逻辑，不完全依赖窗口调整生效
- `input_psw.xml`（`OilCanSetFragment.showInputPsw`）与 `dialog_login.xml`（`LoginDialog`）里的密码 `EditText` 当前 `inputType="phone"`，输入内容明文显示、无遮挡反馈；改为数字密码类型（`numberPassword` 或等效的 `PasswordTransformationMethod`），使已输入内容立即以圆点显示——这部分不改变校验逻辑，只改变输入即时反馈
- **不做**：issue 里的"中期方案"——内嵌式自定义数字小键盘弃用系统 IME——工作量与架构影响大，本变更不包含，留待 design.md 中作为 Non-Goal 记录

## Capabilities

### New Capabilities
- `ui/soft-keyboard-input`：输入类窗口（Activity/DialogFragment/AlertDialog）在系统软键盘弹出时的可用性——窗口是否让位、确认操作是否可达、密码输入是否有即时反馈

### Modified Capabilities
（无——现有 `ui/*` 能力（`main-actions`/`main-navigator`/`polling-coordinator`/`alarm-coordinator`，来自 `split-main-activity` 变更）覆盖的是导航与轮询协调，与软键盘遮挡无重叠）

## Impact

- **受影响代码**：`app/src/main/AndroidManifest.xml`（`InputDialogActivity` 条目）、`app/src/main/java/com/sansen/yeweiyi/view/InputDialogActivity.java`、`app/src/main/res/layout/input_alertdialog.xml`、`app/src/main/java/com/sansen/yeweiyi/fragment/LoginDialog.java`、`app/src/main/res/layout/dialog_login.xml`、`app/src/main/java/com/sansen/yeweiyi/fragment/OilCanSetFragment.java`（`showInputPsw` 方法）、`app/src/main/res/layout/input_psw.xml`
- **与三条硬约束的关系**：
  1. **respects** 不涉及重写——改动范围是 manifest 属性、`Window.setSoftInputMode` 调用、`imeOptions` 绑定、`inputType`，均为局部属性/监听器级别改动，不触及现有业务逻辑
  2. **respects** 本变更不touch协议解码层，也不新增可在 JVM 单测覆盖的业务逻辑（软键盘遮挡行为是 Android 系统级窗口行为，JVM 单测不可见）；正确性判据是真机手动验证，不是补单测
  3. **respects** 与协议正确性优先级不冲突——这不是"锦上添花"的 UI 打磨，而是操作员输入功能被完全卡死的功能性缺陷，且改动量极小、风险极低，不占用协议层工作的优先级预算
- **不涉及** `serialport/`、`factory/`、`structbean/`，无需硬件协议真机验证；但仍需在实机（而非模拟器）上验证，因为不同厂商 IME 对 `adjustResize` 的实际渲染表现可能与模拟器不同
