## Why

GitHub issue #12（今日提交，`bug`/`high`）：系统软键盘弹出时会完全遮挡输入框和确认按钮，用户输入过程看不到任何反馈，密码输完后找不到能点的确认按钮，登录/备注/密码类输入功能事实上被键盘卡死。根因是这些输入类窗口都没有显式设置 `windowSoftInputMode`（或等价的 `Window.setSoftInputMode`），键盘弹出时窗口不做任何调整。这是影响所有依赖软键盘输入的现场操作（登录、交班备注、油罐设置密码校验）的功能性缺陷，不是外观打磨，现在做是因为它直接卡住操作员的正常操作路径。

**范围在真机验证阶段大幅扩大**：修完 issue #12 原文的三处窗口后，真机复测发现 `OilCanSetFragment` 自身表单里靠后的输入框依然被遮挡——根因不是这三处窗口的问题，而是 `MainActivity` 里早已存在、全局生效的 `AndroidAdjustResizeBugFix`（一个手写的 "Android bug 5497" workaround，配合 `AppTheme` 的 `windowFullscreen=true`）本身有计算错误，导致它从未真正让内容区随键盘收缩过——不管是这三处窗口还是任何其它页面。修好这个全局 bug 之后，`MainActivity` 挂载的另外 6 个含输入框的 Fragment（`SetBaseFragment`/`SetPawFragment`/`BuLuFragment`/`JiLuFragment`/`OilTestFragment`/`CreateOilTableFragment`）新暴露出两类问题：固定高度的底部操作栏被顶到键盘上方、以及（`BuLuFragment`）非 `ScrollView` 容器被压缩到失去正常高度。这些追加的修复和三处窗口的修复根因相关、验证方式一致（都靠真机而非模拟器/单测判断正确性），故归入同一个 change 而不是另开新的。

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

真机验证过程中追加的改动（详见 design.md 决策5-11）：

- 修复 `AndroidAdjustResizeBugFix.java` 的高度计算 bug（`usableHeightSansKeyboard - 1` 应为 `usableHeightSansKeyboard - heightDifference`）——这是历史遗留的独立 bug，键盘弹出时从未真正让内容区收缩过，本次工作中发现并顺带修复
- `OilCanSetFragment`/`SetBaseFragment`（两个页面自身表单的输入框会被键盘直接遮挡）在可见期间临时把 `Window.softInputMode` 设为 `adjustPan`（新增 `KeyboardAdjustHelper.applyPanWhileVisible`），不改 manifest；`MainActivity.switchContent()` 在切换到任意页面前统一把 softInputMode 复位为系统默认值，取代"各页面自己在 `onHiddenChanged` 里还原"（后者在真机测试中被发现会把设置泄漏到下一个页面）
- 全部 7 个含 `EditText` 的 Fragment（`OilCanSetFragment`/`SetBaseFragment`/`SetPawFragment`/`BuLuFragment`/`JiLuFragment`/`OilTestFragment`/`CreateOilTableFragment`）用新增的 `KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible` 在键盘弹出、内容区收缩时隐藏各自固定高度的底部操作栏（保存/返回上页等按钮），收起后恢复——因为触发收缩的 `AndroidAdjustResizeBugFix` 是全局生效的，与某个页面是否设置 `adjustPan` 无关，只要页面上有输入框弹出键盘，所有此类页面的底部操作栏都会被顶起
- `LoginDialog`（登录/高级设置密码校验）确认按钮点击时先主动 `InputMethodManager.hideSoftInputFromWindow()` 收起键盘，再回调确认逻辑——根因是调用方（`SetFragment`/`MainActivity`）拿到密码后通常立刻 `switchContent` 切换页面，键盘若未被主动收起会遗留到下一个页面（典型现象：`SetSeniorFragment` 本身没有任何输入框，却在密码校验通过后显示为"键盘自动弹出、布局被顶起"）
- `fragment_bu_lu.xml` 的表单内容区补一层 `ScrollView`（`fillViewport="true"`）——该内容区不是 `ScrollView`，被 `AndroidAdjustResizeBugFix` 压缩到较小高度时会把这个精确高度直接传给内部 `EditText`，导致输入框被压缩到接近消失，而不是像其它页面（内容区本身包了 `ScrollView`）那样只是变成可滚动
- `OilCanSetFragment.showInputPsw`（原 tasks.md 任务3打算修改的方法）核实后确认是**死代码**（全局零调用），直接删除，连带删除只被它引用的 `input_psw.xml` 布局；真正生效的设置项密码校验入口是 `MainActivity` 里另一个同名但完全不同的 `showInputPsw` 方法，且该方法早已不弹密码框、只做页面跳转——这是命名和行为不一致的历史遗留问题，本次不处理，只记录

## Capabilities

### New Capabilities
- `ui/soft-keyboard-input`：输入类窗口（Activity/DialogFragment/AlertDialog）在系统软键盘弹出时的可用性——窗口是否让位、确认操作是否可达、密码输入是否有即时反馈

### Modified Capabilities
（无——现有 `ui/*` 能力（`main-actions`/`main-navigator`/`polling-coordinator`/`alarm-coordinator`，来自 `split-main-activity` 变更）覆盖的是导航与轮询协调，与软键盘遮挡无重叠）

## Impact

- **受影响代码（issue #12 原三处窗口）**：`app/src/main/AndroidManifest.xml`（`InputDialogActivity` 条目）、`app/src/main/java/com/sansen/yeweiyi/view/InputDialogActivity.java`、`app/src/main/res/layout/input_alertdialog.xml`、`app/src/main/java/com/sansen/yeweiyi/fragment/LoginDialog.java`、`app/src/main/res/layout/dialog_login.xml`
- **受影响代码（真机验证阶段追加）**：
  - `app/src/main/java/com/sansen/yeweiyi/utils/AndroidAdjustResizeBugFix.java`（高度计算 bug 修复）
  - `app/src/main/java/com/sansen/yeweiyi/utils/KeyboardAdjustHelper.java`（新增）
  - `app/src/main/java/com/sansen/yeweiyi/activity/MainActivity.java`（`switchContent` 统一复位 softInputMode）
  - `app/src/main/java/com/sansen/yeweiyi/fragment/{OilCanSetFragment,SetBaseFragment,SetPawFragment,BuLuFragment,JiLuFragment,OilTestFragment,CreateOilTableFragment}.java`
  - `app/src/main/res/layout/fragment_bu_lu.xml`（补 `ScrollView`）
  - 删除：`app/src/main/java/com/sansen/yeweiyi/fragment/OilCanSetFragment.java` 里的 `showInputPsw` 方法、`app/src/main/res/layout/input_psw.xml`
- **与三条硬约束的关系**：
  1. **respects** 不涉及重写——改动范围是 manifest 属性、`Window.setSoftInputMode` 调用、`imeOptions` 绑定、`inputType`，均为局部属性/监听器级别改动，不触及现有业务逻辑
  2. **respects** 本变更不touch协议解码层，也不新增可在 JVM 单测覆盖的业务逻辑（软键盘遮挡行为是 Android 系统级窗口行为，JVM 单测不可见）；正确性判据是真机手动验证，不是补单测
  3. **respects** 与协议正确性优先级不冲突——这不是"锦上添花"的 UI 打磨，而是操作员输入功能被完全卡死的功能性缺陷，且改动量极小、风险极低，不占用协议层工作的优先级预算
- **不涉及** `serialport/`、`factory/`、`structbean/`，无需硬件协议真机验证；但仍需在实机（而非模拟器）上验证，因为不同厂商 IME 对 `adjustResize` 的实际渲染表现可能与模拟器不同
