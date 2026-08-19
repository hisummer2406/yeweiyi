## 1. `InputDialogActivity`（备注输入）

- [x] 1.1 `AndroidManifest.xml` 的 `InputDialogActivity` 条目补 `android:windowSoftInputMode="adjustPan|stateHidden"`（design.md 决策1）
- [x] 1.2 `input_alertdialog.xml` 的 `main_dialog_remark`（备注输入框）加 `android:imeOptions="actionDone"`
- [x] 1.3 `InputDialogActivity.java` 的 `onCreate` 里给 `dialog_remark` 加 `setOnEditorActionListener`，命中 `EditorInfo.IME_ACTION_DONE` 时调用 `dialog_ok.performClick()`（design.md 决策4，复用现有确认逻辑，不新写一份）

## 2. `LoginDialog`（登录密码）

- [x] 2.1 `dialog_login.xml` 的 `et_psw_input` 的 `android:inputType` 由 `phone` 改为 `numberPassword`（design.md 决策3），移除随之冗余的 `android:digits="0123456789"`（`numberPassword` 本身即限制数字输入）
- [x] 2.2 同一控件加 `android:imeOptions="actionDone"`
- [x] 2.3 `LoginDialog.java` 的 `onStart()` 里，在现有设置窗口大小/背景透明的代码旁，加 `getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)`（design.md 决策2，不改 `MainActivity` 的 manifest）
- [x] 2.4 给 `etInput` 加 `setOnEditorActionListener`，命中 `IME_ACTION_DONE` 时调用 `btnConfirm.performClick()`

## 3. `OilCanSetFragment.showInputPsw`（设置项密码校验）

- [x] 3.1 `input_psw.xml` 的 `et_psw` 的 `android:inputType` 由 `phone` 改为 `numberPassword`，移除冗余的 `android:digits="0123456789"`
- [x] 3.2 同一控件加 `android:imeOptions="actionDone"`
- [x] 3.3 `OilCanSetFragment.java` 的 `showInputPsw` 方法里，`dialog = builder.show()` 之后加 `dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)`
- [x] 3.4 给 `et` 加 `setOnEditorActionListener`，命中 `IME_ACTION_DONE` 时调用 `submit.performClick()`

## 4. 编译验证

- [x] 4.1 跑 `./gradlew :app:assembleDebug`，确认三处改动编译通过——**已完成，BUILD SUCCESSFUL**
- [x] 4.2 跑 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认无回归（本变更不触及可单测的业务逻辑，全绿只表示未破坏现有测试，不是本变更正确性的证据——真机验证才是，见第5组）——**已完成，81 个测试全部通过（0 failures / 0 errors，其中一个套件 7 个 skipped 与本变更无关），干净重跑非 UP-TO-DATE 缓存结果**

## 5. 真机验证（不可跳过，是本变更的正确性判据）

- [ ] 5.1 备注输入：真机打开 `InputDialogActivity`，唤起键盘，确认输入框与"确定"按钮均可见可点；输入内容后点击键盘"完成"键，确认与点击"确定"按钮效果一致
- [ ] 5.2 登录密码：真机打开登录密码框，唤起键盘，确认密码框与"点击进入"按钮均可见可点；输入数字时逐位显示掩码圆点；按键盘"完成"键效果与点击"点击进入"一致
- [ ] 5.3 设置项密码校验：真机在油罐设置页触发密码校验弹窗，唤起键盘，确认密码框与"确定"/"取消"按钮均可见可点；输入数字时逐位显示掩码圆点；按键盘"完成"键效果与点击"确定"一致
- [ ] 5.4 确认 `probeType=0`/其它未改动页面的输入框行为无变化（本变更范围外，不应受影响）

## 6. 收尾

- [x] 6.1 清理 `AndroidManifest.xml` 中已注释掉、挂在废弃重复 `MainActivity` 声明块上的死代码 `<!-- android:windowSoftInputMode="adjustPan|stateHidden" /-->`（design.md Context 已确认与当前生效的 `MainActivity` 声明无关，纯历史遗留）——**已完成，`assembleDebug` 重跑确认无破坏**
- [ ] 6.2 关闭 GitHub issue #12，附上真机验证结果（第5组）——**阻塞：依赖第5组真机验证结果，本机无 adb/真机可用**
- [x] 6.3 若 `docs/重构方案.md` 有对应的已知问题登记条目，更新为已修复——**已确认无对应条目（`grep` 未命中"软键盘"/"windowSoftInputMode"/"键盘遮挡"/"盲输"），本条不适用**
