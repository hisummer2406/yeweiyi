## Context

三处输入窗口的共同问题是没有显式声明键盘弹出时的窗口行为，但三处的宿主类型不同，键盘控制点也不同（见 proposal.md 表格）：`InputDialogActivity` 是独立 Activity，可以在 Manifest 声明；`LoginDialog`（`DialogFragment`）与 `OilCanSetFragment.showInputPsw`（`AlertDialog`）都是浮动窗口，各自拥有独立的 `Window`，宿主都是 `MainActivity`。

`MainActivity` 自身的 manifest 条目历史上有过 `windowSoftInputMode="adjustPan|stateHidden"`，现在是完整注释掉的死代码，且挂在一份已经不生效的重复 `MainActivity` 声明块上（当前生效的 `MainActivity` 声明在它下方，没有这个属性）。`MainActivity` 承载了这个 app 几乎所有的 Fragment 和弹窗，不止本变更涉及的三个——直接改动 `MainActivity` 的 manifest 条目会影响到本变更范围之外的所有输入场景，与硬约束1（现场设备回归成本高，不做大爆炸式改动）冲突。

## Goals / Non-Goals

**Goals:**
- 三处输入窗口在键盘弹出时，输入框与确认按钮都可见可用
- 改动范围严格限定在这三处窗口本身，不触碰 `MainActivity` 全局行为
- 与三处窗口现有的确认逻辑（`OnClickListener`）复用同一条代码路径，不新写一份平行逻辑

**Non-Goals:**
- 不做 issue 提到的"中期方案"（内嵌式自定义数字小键盘），架构改动大，超出本变更范围
- 不改变任何输入校验逻辑（密码比对、备注长度限制等），只改窗口行为与视觉反馈
- 不清理 `MainActivity` 之外其它未声明 `windowSoftInputMode` 的 Activity（如未在 issue #12 与代码核实中被识别为受影响的场景），避免范围蔓延

## Decisions

### 决策1：三处窗口统一用 `adjustPan`，不用 `adjustResize`

`InputDialogActivity`（`Theme.Holo.Light.Dialog.MinWidth`，`wrap_content` 高度居中面板）、`LoginDialog`、`showInputPsw` 的 `AlertDialog` 都是**浮动小窗口**，不是铺满屏幕的可伸缩布局。`adjustResize` 需要窗口的根视图能被安全地缩小重新测量，对 `wrap_content` 居中的浮动窗口效果不可靠（常见 Android 平台行为：浮动 Dialog 窗口用 `adjustResize` 时可能不生效或裁切异常）。`adjustPan` 直接把整个窗口内容平移到键盘上方，行为对这类小窗口更可预测，也和历史上那行被注释掉的 `adjustPan|stateHidden` 遗留配置一致，说明这本来就是团队的既有选择。

替代方案：`adjustResize` — 放弃，理由同上。

### 决策2：不改 `MainActivity` 的 manifest 条目，改为在 `LoginDialog`/`showInputPsw` 各自的 `Window` 上单独设置

`getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)`（`LoginDialog.onStart()`，与现有设置窗口大小/背景透明的代码放在一起）、`dialog.getWindow().setSoftInputMode(...)`（`showInputPsw` 里 `builder.show()` 之后）。`InputDialogActivity` 走 Manifest `android:windowSoftInputMode` 属性，因为它是独立 Activity 条目，改动只影响它自己。

这样三处改动互不影响，也不触碰 `MainActivity` 本身或其它未被本变更覆盖的输入场景。

替代方案：恢复 `MainActivity` 那行被注释掉的 `adjustPan|stateHidden` — 放弃，因为 `MainActivity` 挂载了本变更范围外的大量 Fragment 和弹窗，全局改动的回归验证成本超出本变更的"三处输入窗口"范围，违反硬约束1。

### 决策3：密码掩码用声明式 `inputType="numberPassword"`，不用 `PasswordTransformationMethod` + 手动过滤

`input_psw.xml`（`OilCanSetFragment`）与 `dialog_login.xml`（`LoginDialog`）当前是 `android:inputType="phone"` + `android:digits="0123456789"`（限制只能输入数字）。`numberPassword` 本身就限制为数字输入并自带逐字符掩码显示，覆盖现有 `digits` 属性的约束效果，改动是纯声明式的一行属性替换，不需要额外的 Java 代码。`et.getText().toString()` 取到的仍然是明文数字字符串，`TokenUtil.getToken()` 的比对逻辑（纯字符串相等）不受影响。

`InputDialogActivity` 的 `isPsw` 分支已经在用 `PasswordTransformationMethod.getInstance()`，本身已有逐字符掩码，不需要改动。

替代方案：给 `input_psw.xml`/`dialog_login.xml` 也套 `PasswordTransformationMethod`（编程方式） — 放弃，`numberPassword` 声明式属性能达到同样效果且改动更小。

### 决策4：IME 完成键复用现有确认按钮的点击逻辑，不新写一份

三处均给密码/备注 `EditText` 加 `android:imeOptions="actionDone"`，并在 Java 里用 `setOnEditorActionListener`，命中 `EditorInfo.IME_ACTION_DONE` 时调用确认按钮的 `performClick()`（而不是复制一份确认逻辑），确保键盘完成键和点击按钮永远走同一条代码路径，不会出现两条路径行为不一致的问题。

## Risks / Trade-offs

- **[Risk]** `adjustPan` 在部分厂商定制 IME 上表现可能与标准 AOSP 键盘不同，模拟器无法复现 → **Mitigation**：正确性判据是真机验证（design 的 Goals 已声明），tasks.md 会包含在实际设备上逐一验证三处窗口的步骤，不依赖模拟器结论
- **[Risk]** `numberPassword` 替换 `phone` + `digits` 后，若后续代码依赖 `inputType="phone"` 触发的键盘布局差异（例如某处误用了电话键盘特有的按键）→ **Mitigation**：已读代码确认这两处只用 `et.getText().toString()` 取值做字符串比较，不依赖 `inputType` 具体取值之外的行为
- **[Risk]** `performClick()` 复用确认按钮逻辑时，若按钮当前不可点击（`disabled`/`invisible`）会导致 IME 完成键也无效 → **Mitigation**：三处按钮当前代码中始终可见可点击，没有条件禁用逻辑，不存在这个分支

## Migration Plan

不涉及数据结构或持久化格式变化，纯 UI 行为改动：

1. 按决策1-4实施，跑 `./gradlew :app:assembleDebug` 确认编译通过
2. 真机安装，按 specs 里的三个 Scenario 逐一验证（备注输入、登录密码、设置密码校验，键盘弹出时输入框和确认按钮均可见可用；IME 完成键触发确认；密码逐字符掩码显示）
3. 验证通过后随下一次常规构建发布，无需单独的灰度或回滚窗口设计——纯客户端 UI 改动，问题一旦发现，回滚即为撤销对应 commit 并重新出包

## Open Questions

（无——`MainActivity` 里那行死代码的注释块是否清理属于收尾细节，已在 tasks.md 中作为收尾任务处理，不影响本设计的方案选择）
