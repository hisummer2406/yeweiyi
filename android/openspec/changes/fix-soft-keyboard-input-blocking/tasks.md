## 1. `InputDialogActivity`（备注输入）

- [x] 1.1 `AndroidManifest.xml` 的 `InputDialogActivity` 条目补 `android:windowSoftInputMode="adjustPan|stateHidden"`（design.md 决策1）
- [x] 1.2 `input_alertdialog.xml` 的 `main_dialog_remark`（备注输入框）加 `android:imeOptions="actionDone"`
- [x] 1.3 `InputDialogActivity.java` 的 `onCreate` 里给 `dialog_remark` 加 `setOnEditorActionListener`，命中 `EditorInfo.IME_ACTION_DONE` 时调用 `dialog_ok.performClick()`（design.md 决策4，复用现有确认逻辑，不新写一份）

## 2. `LoginDialog`（登录密码）

- [x] 2.1 `dialog_login.xml` 的 `et_psw_input` 的 `android:inputType` 由 `phone` 改为 `numberPassword`（design.md 决策3），移除随之冗余的 `android:digits="0123456789"`（`numberPassword` 本身即限制数字输入）
- [x] 2.2 同一控件加 `android:imeOptions="actionDone"`
- [x] 2.3 `LoginDialog.java` 的 `onStart()` 里，在现有设置窗口大小/背景透明的代码旁，加 `getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)`（design.md 决策2，不改 `MainActivity` 的 manifest）
- [x] 2.4 给 `etInput` 加 `setOnEditorActionListener`，命中 `IME_ACTION_DONE` 时调用 `btnConfirm.performClick()`

## 3. `OilCanSetFragment.showInputPsw`（设置项密码校验）—— ⚠️ 计划变更，见下方说明

> **本组任务 3.1-3.4 原计划是给 `showInputPsw` 加 `adjustPan`/`imeOptions`，动手前用 `grep` 核实调用点，发现这个私有方法在全代码库范围内零调用——是死代码，不是任何真实可达的设置项密码校验入口（真正生效的入口是 `MainActivity` 里另一个同名但完全不同、早已不弹密码框的 `showInputPsw` 方法，见 design.md 决策11 与 Open Questions）。给死代码做键盘适配没有意义，故改为直接删除，原 3.1-3.4 不再执行。**

- [x] 3.1（变更为）删除 `OilCanSetFragment.java` 的 `showInputPsw` 方法及只被它引用的 `dialog` 字段
- [x] 3.2（变更为）删除只被 `showInputPsw` 引用的 `input_psw.xml` 布局文件
- [x] 3.3（变更为）清理因删除而失效的 import（`android.support.v7.app.AlertDialog`、`com.sansen.yeweiyi.utils.TokenUtil`、`android.view.WindowManager`、`android.view.inputmethod.EditorInfo`）

## 3a. 追加范围：`AndroidAdjustResizeBugFix` 全局收缩 bug（design.md 决策5）

真机复测三处窗口后发现 `OilCanSetFragment` 表单靠后的输入框仍被遮挡，排查到 `MainActivity` 全局安装的 `AndroidAdjustResizeBugFix` 计算写反，从未真正生效过，见 design.md 决策5。

- [x] 3a.1 `AndroidAdjustResizeBugFix.java` 键盘弹出分支的 `frameLayoutParams.height = usableHeightSansKeyboard - 1;` 改为 `frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;`

## 3b. 追加范围：`OilCanSetFragment`/`SetBaseFragment` 输入框遮挡 + 全部 7 个页面的底部操作栏（design.md 决策6/7）

- [x] 3b.1 新增 `com.sansen.yeweiyi.utils.KeyboardAdjustHelper`：`applyPanWhileVisible(Fragment)`（页面可见期间把 `Activity.Window` 设为 `adjustPan|stateHidden`）、`hideActionBarWhileKeyboardVisible(View rootView, View... actionBarViews)`（用 `rootView.getRootView().getHeight()` 为基准判断键盘是否弹出，跌破 3/4 隐藏、恢复则显示）
- [x] 3b.2 `OilCanSetFragment.java`：`initData()`/`onHiddenChanged(false)` 里调用 `applyPanWhileVisible`；`findView()` 里对 `ly_baoCunData`/`ly_copyData`/`ly_upper_page` 调用 `hideActionBarWhileKeyboardVisible`
- [x] 3b.3 `SetBaseFragment.java`：同上补 `applyPanWhileVisible`（新增 `onHiddenChanged` 覆写）+ 对 `ly_saveData`/`ly_upper_page` 的 `hideActionBarWhileKeyboardVisible`
- [x] 3b.4 `SetPawFragment.java`：对 `ly_saveData`/`ly_upper_page` 调用 `hideActionBarWhileKeyboardVisible`（不需要 `applyPanWhileVisible`，该页面输入框本身未被遮挡）
- [x] 3b.5 `BuLuFragment.java`：同上，对 `ly_saveData`/`ly_upper_page`
- [x] 3b.6 `JiLuFragment.java`：同上，对 `ly_upper_page`（这排只有它一个按钮）
- [x] 3b.7 `OilTestFragment.java`：同上，对 `ly_baoCunData`/`ly_upper_page`
- [x] 3b.8 `CreateOilTableFragment.java`：同上，对同一排 6 个按钮（`createtable_bt_build`/`createtable_bt_copyoilcan`/`createtable_bt_chaKan`/`createtable_bt_saveoilcan`/`createtable_bt_writeoilcan`/`ly_upper_page`）一起传入

## 3c. 追加范围：softInputMode 泄漏 + `LoginDialog` 键盘残留（design.md 决策8/9）

- [x] 3c.1 `MainActivity.switchContent()` 里，切到任意新页面前统一 `getWindow().setSoftInputMode(SOFT_INPUT_STATE_UNSPECIFIED | SOFT_INPUT_ADJUST_UNSPECIFIED)`，取代 `OilCanSetFragment` 自己在 `onHiddenChanged(true)` 里还原（该实现已随之简化/移除）
- [x] 3c.2 `LoginDialog.java`：确认按钮点击时先 `InputMethodManager.hideSoftInputFromWindow()` 收起键盘，再回调 `onConfirm`（IME "完成"键走同一 `performClick()` 路径，一并覆盖）

## 3d. 追加范围：`fragment_bu_lu.xml` 输入框被压缩（design.md 决策10）

- [x] 3d.1 `fragment_bu_lu.xml` 表单内容区补一层 `ScrollView`（`fillViewport="true"`），把原有"左右两栏表单 + 底部提示文字"整体包进去

## 4. 编译验证

- [x] 4.1 跑 `./gradlew :app:assembleDebug`，确认三处改动编译通过——**已完成，BUILD SUCCESSFUL**
- [x] 4.2 跑 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认无回归（本变更不触及可单测的业务逻辑，全绿只表示未破坏现有测试，不是本变更正确性的证据——真机验证才是，见第5组）——**已完成，81 个测试全部通过（0 failures / 0 errors，其中一个套件 7 个 skipped 与本变更无关），干净重跑非 UP-TO-DATE 缓存结果**
- [x] 4.3 追加范围（3a-3d）每一步之后都重跑 `./gradlew :app:assembleDebug`，全部 BUILD SUCCESSFUL

## 5. 真机验证（不可跳过，是本变更的正确性判据）

**issue #12 原三处窗口：**
- ~~5.1 备注/密码输入~~：`InputDialogActivity` 唯一的真实调用点 `MainActivity.main_but_commitwork` 挂在 `activity_main.xml` 的 `menu_right` 侧边菜单容器下，该容器写死 `android:visibility="gone"`，全代码库没有任何地方把它设为 `VISIBLE`（`grep -rn "menu_right"` 仅命中这一处 XML，Java 端零引用）——按钮永远不可达，用户点不到，`InputDialogActivity` 因此是**不可达死链路**，真机验证时点击的"交班"按钮实际是另一个入口 `bar_but_commitwork`（标签"交班记录"，跳转 `CommitWorkRecortFragment`，与 `InputDialogActivity`/密码输入无关），确认作废，不需要真机验证，从清单移除
- [x] 5.2 登录密码：真机打开登录密码框，唤起键盘，确认密码框与"点击进入"按钮均可见可点；输入数字时逐位显示掩码圆点；按键盘"完成"键效果与点击"点击进入"一致——**已真机验证通过**
- ~~5.3 设置项密码校验~~：原场景描述的是 `OilCanSetFragment.showInputPsw` 弹窗，已确认为死代码并删除（见任务组3），确认作废，从清单移除；`MainActivity.main_but_set` 的密码校验入口在 5.6 覆盖

**追加范围（决策5-11）：**
- [x] 5.4 `OilCanSetFragment`：底部按钮栏（`ly_baoCunData`/`ly_copyData`/`ly_upper_page`）键盘弹出隐藏、收起恢复——已验证有效；表单靠后输入框的遮挡修复——已验证有效（触发本轮排查的原始问题）
- [x] 5.5 `SetBaseFragment`/`SetPawFragment`/`BuLuFragment`/`JiLuFragment`/`OilTestFragment`/`CreateOilTableFragment`：各自底部操作栏键盘弹出隐藏、收起恢复——已验证有效（`BuLuFragment` 曾发现输入框被压缩到消失，已定位为缺 `ScrollView` 并修复，见3d.1，修复本身已在5.7真机复测通过）
- [x] 5.6 `LoginDialog` 两个调用点（`SetFragment`→`SetSeniorFragment`"高级设置"、`MainActivity.main_but_set`→`SetFragment`"设置"按钮）：确认密码后新页面没有键盘残留/布局被顶起——**已真机验证通过**
- [x] 5.7 `fragment_bu_lu.xml` 补 `ScrollView` 后复测：键盘弹出时输入框保持正常高度（44dp）、变为可滚动而非压缩；键盘收起后布局恢复原状——**已真机验证通过**
- [x] 5.8 全局回归：任意两个页面之间来回切换多次（含经过 `LoginDialog` 密码校验的路径），确认没有任何页面出现"键盘状态从上一页面残留"的现象——**已真机验证通过**
- [x] 5.9 确认其余未改动页面（无 `EditText` 的页面、或不在上述 7+`SetSeniorFragment` 名单内的页面）输入框行为无变化——**已真机验证通过**

## 6. 收尾

- [x] 6.1 清理 `AndroidManifest.xml` 中已注释掉、挂在废弃重复 `MainActivity` 声明块上的死代码 `<!-- android:windowSoftInputMode="adjustPan|stateHidden" /-->`（design.md Context 已确认与当前生效的 `MainActivity` 声明无关，纯历史遗留）——**已完成，`assembleDebug` 重跑确认无破坏**
- [ ] 6.2 关闭 GitHub issue #12，附上真机验证结果（第5组）——第5组已全部完成（5.1/5.3 确认作废并说明原因，其余全部真机验证通过），可以关闭；本机无 adb/真机，实际去 GitHub 关闭 issue 需要你操作
- [x] 6.3 若 `docs/重构方案.md` 有对应的已知问题登记条目，更新为已修复——**已确认无对应条目（`grep` 未命中"软键盘"/"windowSoftInputMode"/"键盘遮挡"/"盲输"），本条不适用**
- [x] 6.4 code review（`/code-review high`，对比 `main`）覆盖 issue #12 三处窗口 + 追加范围全部改动，5 条 finding 里 3 条采纳修复（`KeyboardAdjustHelper` 的键盘可见性判断基准改用 `getRootView().getHeight()`、清理 `OilCanSetFragment` 删除 `showInputPsw` 后残留的 `AlertDialog`/`TokenUtil` import），1 条判定为不需要改动（`adjustPan` 与 `AndroidAdjustResizeBugFix` 同时生效，已在 design.md Risks 记录为已知的标准搭配而非冲突），1 条是本次 openspec 文档补写（即本次更新）
- [x] 6.5 补写本次真机验证阶段发现并追加的 design.md 决策5-11、proposal.md 范围说明、tasks.md 任务组3a-3d 与扩充后的第5组验证项，使文档与实际代码一致
