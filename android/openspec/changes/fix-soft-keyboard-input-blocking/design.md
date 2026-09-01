## Context

三处输入窗口的共同问题是没有显式声明键盘弹出时的窗口行为，但三处的宿主类型不同，键盘控制点也不同（见 proposal.md 表格）：`InputDialogActivity` 是独立 Activity，可以在 Manifest 声明；`LoginDialog`（`DialogFragment`）与 `OilCanSetFragment.showInputPsw`（`AlertDialog`）都是浮动窗口，各自拥有独立的 `Window`，宿主都是 `MainActivity`。

`MainActivity` 自身的 manifest 条目历史上有过 `windowSoftInputMode="adjustPan|stateHidden"`，现在是完整注释掉的死代码，且挂在一份已经不生效的重复 `MainActivity` 声明块上（当前生效的 `MainActivity` 声明在它下方，没有这个属性）。`MainActivity` 承载了这个 app 几乎所有的 Fragment 和弹窗，不止本变更涉及的三个——直接改动 `MainActivity` 的 manifest 条目会影响到本变更范围之外的所有输入场景，与硬约束1（现场设备回归成本高，不做大爆炸式改动）冲突。

## Goals / Non-Goals

**Goals:**
- 三处输入窗口在键盘弹出时，输入框与确认按钮都可见可用
- 改动范围严格限定在这三处窗口本身，不触碰 `MainActivity` 全局行为（**决策8追加时放宽**：`MainActivity.switchContent()` 的统一复位是唯一例外，理由见决策8——它是"防止状态泄漏到范围外页面"的收口点，而不是给范围外页面新增功能）
- 与三处窗口现有的确认逻辑（`OnClickListener`）复用同一条代码路径，不新写一份平行逻辑

**Non-Goals:**
- 不做 issue 提到的"中期方案"（内嵌式自定义数字小键盘），架构改动大，超出本变更范围
- 不改变任何输入校验逻辑（密码比对、备注长度限制等），只改窗口行为与视觉反馈
- 不清理 `MainActivity` 之外其它未声明 `windowSoftInputMode` 的 Activity（如未在 issue #12 与代码核实中被识别为受影响的场景），避免范围蔓延——**决策5-7追加时范围确实扩大了**，但扩大的是"同一根因（`AndroidAdjustResizeBugFix`）在真机验证中新发现的受影响页面"，不是主动去找范围外的新场景

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

---

**以下决策5-11是三处窗口修完、真机复测后追加的**——`OilCanSetFragment` 靠后的输入框仍被遮挡，排查发现根因和前四条决策完全不同，故单独成组记录。

### 决策5：修复 `AndroidAdjustResizeBugFix` 的高度计算 bug（历史遗留，与本变更三处窗口无关，但必须先修）

`MainActivity.onCreate()` 里一直无条件安装着 `AndroidAdjustResizeBugFix`（经典 "AndroidBug5497Workaround" 的本地实现，配合 `AppTheme` 的 `android:windowFullscreen="true"`——这正是触发该平台 bug 的场景，说明这个 workaround 从一开始就是为了绕开这个坑而写的）。它靠 `View.getWindowVisibleDisplayFrame()` 判断键盘是否弹出并手动收缩 `main_root` 的高度，这个判断**不受任何 Window 的 `softInputMode` 取值影响**——也就是说它和本变更三处窗口设不设 `adjustPan` 完全无关，是全局独立生效的机制。

但它的收缩计算写反了：

```java
// bug 版本：键盘弹出时，新高度只比未弹出时少 1px，形同没收缩
frameLayoutParams.height = usableHeightSansKeyboard - 1;
```

修复为：

```java
frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;  // 即 usableHeightNow
```

这个 bug 从代码写下的那天起就一直生效（形同虚设），意味着**在这个 workaround 存在的整个历史里，键盘弹出从未真正让任何页面的内容区收缩过**——三处窗口本身遮挡的原始问题、以及后面发现的 `OilCanSetFragment` 表单遮挡问题，表现上都是"键盘盖住输入框、界面纹丝不动"，根源是同一个。修好这一行之后，**全 App 所有页面**在键盘弹出时都会真正收缩，这也是决策6/7 需要覆盖 7 个页面而不只是本变更原定的三处窗口的原因。

替代方案：撤销这处修复、只保留三处窗口各自的 `adjustPan` — 放弃，因为 `AndroidAdjustResizeBugFix` 的收缩效果不受 `adjustPan` 门控，撤销它只会让 `OilCanSetFragment`/`SetBaseFragment` 的原始遮挡问题重新出现。

### 决策6：`adjustPan` 只加在 `OilCanSetFragment`/`SetBaseFragment`，不推广到其它含输入框的页面

排查了 `MainActivity` 挂载的全部含 `EditText` 的 Fragment 布局，只有这两个页面的输入框在决策5修复后**仍然可能被键盘直接盖住**（表单项多、纵向排布），其余 5 个（`SetPawFragment`/`BuLuFragment`/`JiLuFragment`/`OilTestFragment`/`CreateOilTableFragment`）字段少、且经真机验证问题只出在决策7要处理的底部操作栏上，输入框本身没有被遮挡的问题。新增 `KeyboardAdjustHelper.applyPanWhileVisible(Fragment)`，只在这两个 Fragment 的 `initData()`（首次可见）和 `onHiddenChanged(false)`（再次可见）里调用，在 `Activity.Window` 上临时设置 `adjustPan|stateHidden`。

不需要自己在离开页面时还原——见决策8。

替代方案：给全部 7 个页面都设 `adjustPan` — 放弃，另外 5 个页面本身没有遮挡问题，没必要扩大改动面。

### 决策7：`KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible` 覆盖全部 7 个含输入框的页面

这 7 个页面的布局都是同一种形状：`title_bar` + `weight=1` 的表单内容区 + 紧跟着一排**固定高度、不参与 weight 分配**的底部操作栏（保存数据/返回上页之类的按钮）。决策5修复后，键盘弹出时内容区真的会收缩，这排底部栏会跟着被顶到收缩区域最底部、卡在键盘上方——这个现象和是否设置 `adjustPan` 无关（决策5已说明收缩是全局的），所以 7 个页面都需要处理，不能只处理设了 `adjustPan` 的那两个。

`KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, actionBarViews...)` 监听页面根 View 的 `onGlobalLayout`，用 `rootView.getRootView().getHeight()`（DecorView 高度，全屏幕高度，不受 Fragment 自身多轮测量影响，是稳定基准）与 `rootView.getHeight()`（当前高度）比较，跌破 3/4 视为键盘弹出，隐藏这排按钮；恢复到 3/4 以上视为键盘收起，恢复显示。参照的正是决策5里 `AndroidAdjustResizeBugFix` 自己判断"是否弹出键盘"的同一种基准（`getRootView().getHeight()`），没有另起一套不稳定的判断方式（早期版本用"目前见过的最大高度"做基准，在 Fragment 首次多轮测量时有误判风险，已改用这个更稳的写法）。

### 决策8：`MainActivity.switchContent()` 切换前统一复位 softInputMode，取代各页面自己还原

决策6最初的实现是让 `OilCanSetFragment` 在 `onHiddenChanged(true)` 里自己把 `Window.softInputMode` 还原成进入前的值。真机测试发现这个"各自负责"的方式不可靠：从 `OilCanSetFragment` 经过若干步跳转后再进入其它页面，`adjustPan` 会残留下来，表现为跟输入框毫无关系的页面（例如通过 `SetFragment` → 密码校验 → `SetSeniorFragment` 这条路径）显示为键盘自动弹出、布局被顶起。

改为在 `switchContent(BaseFragment from, BaseFragment to)` 里，切到任何一个新页面之前，统一执行一次：

```java
getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED);
```

`switchContent` 是全 App 顶层 Fragment 切换的唯一入口，在这里统一复位不依赖任何一个具体页面"记得"要不要清理、清理得对不对——`OilCanSetFragment`/`SetBaseFragment` 因此简化为只保留"变为可见时设 `adjustPan`"这一半逻辑。

替代方案：让每个设置了 `adjustPan` 的页面自己在 `onHiddenChanged(true)` 里精确记录并还原 — 放弃，真机验证已经证明这条路径不可靠，且需要每个页面都正确实现，出错面更大。

### 决策9：`LoginDialog` 确认时主动收起键盘，不依赖弹窗 dismiss 顺带收起

决策8修好 softInputMode 的残留后，`SetSeniorFragment`（没有任何输入框）仍然会在密码校验通过后显示"键盘弹出、布局被顶起"。排查发现这和 `adjustPan` 无关：`SetFragment`/`MainActivity` 里 `LoginDialog` 的确认回调都是"先 `switchContent` 切换页面，再 `dialog.dismiss()`"，而 `LoginDialog` 的密码框在切换那一刻还占着焦点、键盘还开着——Android 不保证弹窗 `dismiss()` 时会同步收起一个仍有焦点 `EditText` 的键盘，于是键盘状态被带到了刚显示出来的新页面上。

修复：`LoginDialog` 确认按钮点击时（键盘"完成"键走的是同一个 `performClick()` 路径，一并覆盖），先 `InputMethodManager.hideSoftInputFromWindow()` 显式收起键盘，再回调 `onConfirm`，保证调用方后续的 `switchContent`/`dismiss()` 执行时键盘已经真正关闭。改在 `LoginDialog` 内部一处即可覆盖它的两个调用点（`SetFragment` 的"高级设置"入口、`MainActivity.main_but_set` 的"设置"入口），不用改调用方代码。

### 决策10：`fragment_bu_lu.xml` 补 `ScrollView`

决策5修复后，`BuLuFragment` 的表单输入框在内容区被压缩时不是变得可滚动，而是被压缩到接近消失。排查发现它的内容区是一个普通 `LinearLayout`（不是 `ScrollView`），被 `AndroidAdjustResizeBugFix` 压成一个精确的小高度后，这个高度会原样传给内部的 `wrap_content`/固定 `44dp` 高度的子 View——包括 `EditText`，标准 Android 控件在这种"精确测量模式（EXACTLY/AT_MOST）小于自身声明高度"的场景下会服从约束、真的把自己压小，而不是保持原高度溢出显示。`OilCanSetFragment` 等页面不受影响是因为它们的内容区本身包了 `ScrollView`——`ScrollView` 测量子 View 用的是不限制的 `UNSPECIFIED`，容器变小只影响"能看到多少"，不影响"内容本身多高"。

修复：给 `fragment_bu_lu.xml` 的内容区补一层 `ScrollView`（`fillViewport="true"`，保证空间够时和之前视觉一致），把原有的表单内容整体包进去，不改 Java 代码。

### 决策11：`OilCanSetFragment.showInputPsw` 确认为死代码，直接删除，而不是像原任务3那样修改它

原 tasks.md 任务3计划给 `OilCanSetFragment.showInputPsw`（连同它用到的 `input_psw.xml`）加 `adjustPan`/`imeOptions`。动手前用 `grep` 核实调用点时发现这个私有方法在全代码库范围内零调用——不是本变更相关页面的一部分。真正生效的"设置项密码校验"入口是 `MainActivity` 里另一个**同名但完全不同**的 `showInputPsw` 方法（两个类各自的私有方法，不冲突），且这个方法早已不弹密码框、只做 `switchContent` 页面跳转，方法名和实际行为对不上——这是独立于本变更的历史遗留问题，本次不处理，只在此记录，避免以后有人以为这两个方法是同一回事。

改为直接删除 `OilCanSetFragment.showInputPsw` 方法、只被它引用的 `dialog` 字段、以及只被它引用的 `input_psw.xml` 布局文件。

替代方案：按原计划修改 `showInputPsw` — 放弃，给死代码做键盘适配没有意义，且会让人误以为它是某个真实可达的入口。

## Risks / Trade-offs

- **[Risk]** `adjustPan` 在部分厂商定制 IME 上表现可能与标准 AOSP 键盘不同，模拟器无法复现 → **Mitigation**：正确性判据是真机验证（design 的 Goals 已声明），tasks.md 会包含在实际设备上逐一验证三处窗口的步骤，不依赖模拟器结论
- **[Risk]** `numberPassword` 替换 `phone` + `digits` 后，若后续代码依赖 `inputType="phone"` 触发的键盘布局差异（例如某处误用了电话键盘特有的按键）→ **Mitigation**：已读代码确认这两处只用 `et.getText().toString()` 取值做字符串比较，不依赖 `inputType` 具体取值之外的行为
- **[Risk]** `performClick()` 复用确认按钮逻辑时，若按钮当前不可点击（`disabled`/`invisible`）会导致 IME 完成键也无效 → **Mitigation**：三处按钮当前代码中始终可见可点击，没有条件禁用逻辑，不存在这个分支
- **[Risk]** 决策6的 `adjustPan` 与决策5的 `AndroidAdjustResizeBugFix` 手动收缩在 `OilCanSetFragment`/`SetBaseFragment` 上同时生效，理论上可能出现系统 pan 和手动 resize 叠加造成的跳动/双重挤压 → **Mitigation**：`AppTheme` 的 `windowFullscreen=true` 正是 `AndroidAdjustResizeBugFix` 这类 workaround 要绕开的平台场景，`adjustPan` + 手动 resize 是该 workaround 公开文档推荐的标准搭配（`adjustPan` 防止系统在 fullscreen 主题下再触发一次不可靠的原生 resize，真正的收缩全靠手动逻辑），不是两个机制意外冲突；真机验证尚未报告这两个页面有跳动现象，若后续验证发现异常再回头处理
- **[Risk]** 决策7的键盘可见性判断（当前高度 < `getRootView().getHeight()` 的 3/4）是启发式阈值，不是精确的"键盘是否弹出"信号，可能被其它导致窗口临时变矮的因素误触发 → **Mitigation**：采用和决策5 `AndroidAdjustResizeBugFix` 完全一致的判断基准和阈值比例，这套启发式已经是这个 workaround 从最初就在用、验证过的方式，没有引入新的不确定性

## Migration Plan

不涉及数据结构或持久化格式变化，纯 UI 行为改动：

1. 按决策1-11实施，跑 `./gradlew :app:assembleDebug` 确认编译通过
2. 真机安装，按 specs 里的 Scenario 逐一验证：issue #12 原三处窗口（备注输入、登录密码、设置密码校验，键盘弹出时输入框和确认按钮均可见可用；IME 完成键触发确认；密码逐字符掩码显示），以及追加范围的 7 个含输入框页面（各自表单输入框不被压缩/遮挡、底部操作栏键盘弹出时隐藏收起后恢复）、`LoginDialog` 两个调用点（进入 `SetFragment`/`SetSeniorFragment` 后确认没有键盘状态残留）
3. 验证通过后随下一次常规构建发布，无需单独的灰度或回滚窗口设计——纯客户端 UI 改动，问题一旦发现，回滚即为撤销对应 commit 并重新出包

## Open Questions

- `MainActivity` 里另一个同名的 `showInputPsw` 方法（决策11提到）方法名和实际行为（早已不弹密码框）不一致，是否需要重命名或补回密码校验——本变更不处理，留给后续单独评估
- `InputDialogActivity` 唯一的真实调用点 `MainActivity.main_but_commitwork` 挂在 `activity_main.xml` 的 `menu_right` 侧边菜单容器下，该容器写死 `android:visibility="gone"` 且全代码库没有任何代码把它设为 `VISIBLE`——是不可达的死链路，`InputDialogActivity` 因此没有任何用户能实际触发的入口（真机测试时按钮点了没反应，点的其实是另一个入口 `bar_but_commitwork`）。是否要恢复 `menu_right` 的入口、还是把这几个按钮方法一并当死代码清理——本变更不处理，留给后续单独评估
- （原有问题已解决：`MainActivity` 里那行死代码的注释块已在 tasks.md 收尾任务中清理）
