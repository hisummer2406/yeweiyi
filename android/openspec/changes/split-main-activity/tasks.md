## 1. 前置：onClick 转换为编译期可见绑定（不可跳过、不可与拆分混在一起提交）

> **2026-08-13 复核补充**：34 个绑定点的**分布**比数量更危险，见 1.1。
> **2026-08-19 实施补充**：实际执行中发现的分布与原计划不完全一致，见 1.1/1.2 的执行记录——原计划把"18 方法名/34 引用"当成同质风险来处理，实际是三类性质完全不同的绑定混在一起。

- [x] 1.1 列出全部绑定点清单。**实际枚举结果（`grep -rn 'android:onClick=' app/src/main/res/layout`，执行时状态）**：起点 34 处、18 个方法名，但这不是最终要转换的数字——继续排查发现：
  - **7 处彻底不可达**：`activity_main.xml` 里的 `menu_right` 侧边菜单容器 `android:visibility="gone"` 且全项目 Java 代码从未引用（`main_but_main`/`main_but_driver`/`main_but_set`/`main_but_commitwork`/`main_but_sidelea`/`main_but_print`/`main_but_alarm`），其中 4 个指向的方法在 `MainActivity` 里根本不存在。用户决定（见对话记录）整体删除该容器及其 7 个按钮，而不是保留死代码或者只转换。删除后连带清理了 `MainActivity` 中只服务于这个死菜单的 7 个 findViewById 字段、三语言 setBackgroundResource 分支、`refreshBut()`、已彻底不可达的 `main_but_commitwork(View v)` 方法，以及 `tempButton`/`switchButtonState` 的初始化逻辑（改为可空、新增 `clearButtonState()`，保持"返回主页/设置后取消底部栏高亮"这一行为不变）。`fragment_container` 上原有的 `layout_toLeftOf="@id/menu_right"` 一并删除（该约束此前一直在为不可见的菜单预留屏幕空间，是死菜单的连带影响，非独立决策）。
  - **2 处是孤儿布局**：`activity_login.xml`（原计划已知，`onClick="login"` 指向的方法全项目不存在，且该布局未被任何 Java/Manifest 引用）与**新发现的** `fragment_recode_storage.xml`（`git grep -F` 确认全项目零引用，同一性质，一并删除）。
  - **6 处 XML 属性已被同 View 上后设置的 `setOnClickListener` 覆盖，从未真正触发**：`fragment_paw_set.xml` 的 `main_but_main`（该 id `ly_saveData` 实为"保存数据"按钮，早已被 `SetPawFragment` 正确接到保存逻辑）、`fragment_recode_inout/alarm/out.xml` 各 1 处 `but_daoChuRuKu`、`fragment_recode_stock.xml` 2 处 `but_daoChuRuKu`、`fragment_recode_commitwork.xml` 的 `but_daoChuJiaoBan`（`ly_jiaoBanData` 早已被 `CommitWorkRecortFragment` 接到真实的 Excel 导出逻辑）——这 6 处**曾被误判为"方法不存在会崩溃"的活跃地雷**，排查后确认 Android 的 `setOnClickListener` 覆盖语义（Fragment 在 `findView()` 里对同一 View 调用 `setOnClickListener` 发生在 `android:onClick` 反射解析之后，后者胜出）使其从未真正被触发。已就地向用户更正此前的错误结论。
  - **18 处真正存活、需要转换**：`activity_main.xml` 7 处（`btn_jieJing`/`bar_but_alarm`/`bar_but_saveoil`/`bar_but_inoil`/`bar_but_commitwork`/`btn_kuCunRecord`/`main_but_set`）+ 7 个 fragment 里各 1 处 `main_but_main`（`fragment_recode_save_oil/inout/alarm/stock/out/commitwork.xml` 与 `fragment_set.xml`）+ `fragment_recode_save_oil.xml` 的 `but_daoChuRuKu`（转换后确认是 pre-existing 的空实现桩，"导出数据"按钮本就不做任何事，行为未变）+ `fragment_recode_commitwork.xml` 的 `but_startJiaoBan`/`but_endJiaoBan`。
- [x] 1.2 上述 18 处真正存活的绑定已改用 `setOnClickListener`：`activity_main.xml` 的 7 处直接在 `MainActivity.findView()` 用方法引用（如 `this::bar_but_alarm`）接线；7 个 fragment 的 `main_but_main` 均新增 `android:id="@+id/ly_backHome"`，在各自 Fragment 的 `findView()` 里用 `main.main_but_main(v)` 接回 `MainActivity`（方法本体仍留在 `MainActivity`，职责搬移是第 2-5 组的工作，本组只解决反射可见性），消除了「XML 在 Fragment、方法在 Activity」的隐式引用；`but_startJiaoBan`/`but_endJiaoBan` 同样接回 `MainActivity`。上述 6 处已被覆盖的死绑定与 7 处死菜单绑定，直接删除 `android:onClick` 属性，不新增任何监听（新增监听会与已存在的正确监听冲突/无意义）
- [x] 1.3 `activity_login.xml`：确认（`git grep -F`）后已删除。`fragment_recode_storage.xml` 同性质，一并确认删除（见 1.1）
- [x] 1.4 `./gradlew :app:assembleDebug` BUILD SUCCESSFUL；`./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` 81 个用例、0 失败，与基线一致（读取 XML 结果确认，未只信退出码）
- [ ] 1.5 真机逐个点击回归——本机无 Android 设备，无法完成，如实留空。重点覆盖：18 处新绑定（含 7 个 fragment 的返回主页按钮）、`main_but_main`/`main_but_set` 触发时底部栏高亮是否正确清除（`clearButtonState()` 新逻辑）、确认删除 `menu_right` 后主内容区宽度变化（原先为不可见菜单预留的空白现在会被内容占满，属预期的连带视觉变化，非缺陷）
- [x] 1.6 已作为独立提交（不含第 2-5 组任何拆分改动）

## 2. 导航器（MainNavigator）

> **2026-08-19 实施补充**：`mContent` 与 17 个 Fragment 缓存字段（`mainFragment`/`setFragment`/...）在
> `fragment/` 包下被外部直接读写共 100+ 处（`main.mContent` 57 处、`main.switchContent(...)` 17 个文件、
> 各 Fragment 缓存字段合计 100+ 处，如 `main.setSeniorFragment` 26 处、`main.setFragment` 43 处），把它们
> 搬进导航器会波及全项目数十个文件，远超本组"搬 Fragment 切换机制"的范围。核实这些字段/`switchContent`
> 的 `from` 实参在全部内部+外部调用点都严格等于 `mContent`（`grep` 零反例）后，采用以下拆分：
> **搬入 MainNavigator** 的是纯机制——`FragmentManager`、`fragment_container` 派生的
> `RelativeLayout.LayoutParams`、`radio_bar` 显隐、`switchContent`/`setDefaultFragment`/`setMainButton`
> 方法体；**留在 MainActivity** 的是 `mContent` 与全部 17 个 Fragment 缓存字段（外部依赖太广，且并非
> "导航"这一个职责该管，而是全项目共享的 fragment 池）。`MainActivity.switchContent(BaseFragment, BaseFragment)`
> 保留为委托方法（签名不变，内部转发给 `navigator.switchContent` 并按返回值更新 `mContent`），
> 因此全部 17 个外部调用文件与 57 处 `main.mContent` 引用**零改动**。
- [x] 2.1 新建 `activity/MainNavigator.java`，承接 `switchContent`/`setDefaultFragment`/`setMainButton`
  三个方法体与其依赖的 `FragmentManager`/`RelativeLayout.LayoutParams`/`radio_bar`
- [x] 2.2 `MainActivity` 移除 `fragmentManager`/`fragment_container`/`transaction`/`params`/`radio_bar` 字段
  （`fragment_container`/`radio_bar` 降级为 `findView()` 内的局部变量，仅用于构造 `navigator`），
  持有 `public MainNavigator navigator` 并在 `findView()` 里构造；`switchContent` 改为委托方法；
  `setDefaultFragment()`/`main_but_main`/`main_but_set` 等 6 处 `setMainButton(...)` 调用点改为
  `navigator.setMainButton(...)`。顺带删除 `main_but_main` 里两行与 `switchContent` 内部逻辑完全重复、
  等价于 no-op 的 `params.addRule(...)`（`mainFragment` 恒为 `MainFragment` 实例，
  `switchContent` 的 `to instanceof MainFragment` 分支本就会做同样的事）。移除 4 个不再使用的 import
  （`Fragment`/`FragmentManager`/`FragmentTransaction`/`ViewGroup`）
- [x] 2.3 `./gradlew :app:assembleDebug` BUILD SUCCESSFUL；`cleanTestDebugUnitTest :app:testDebugUnitTest`
  81 个用例、0 失败，与基线一致
- [ ] 2.4 真机回归：走一遍全部主要导航路径（各 Fragment 入口 + 返回），重点关注 `main_but_main` 里
  删掉的两行 `params.addRule` 是否确如分析所料是 no-op（预期无可观察差异）——本机无法验证，如实留空

## 3. 轮询器（PollingCoordinator）

> **2026-08-19 实施补充**：MainActivity 里实际有两条独立的定时机制——`MyTimeTask`（20 分钟一次的
> TEST_DATA 标志位看门狗，`task`/`setTimer`/`stopTimer`）与一条独立的 1 秒周期 Handler 循环
> （`REFLASH_TIME`，更新 `SerialPortEntrity.time` 并条件触发 `setTestModel()`）。3.1 明确点名只对接
> `MyTimeTask`，且 Handler 循环耦合了 MainActivity 自身的 `handler` 字段与 `setTestModel`/`setTest`
> 业务方法，不是同一个自然可提取的单元，本组未覆盖它——已在 `PollingCoordinator` 类注释里记录。
> 顺带发现该 Handler 循环在 `onDestroy` 里从未被显式取消（`sendEmptyMessageDelayed` 持续自我重新调度，
> Handler 隐式持有外部类引用），是一个既有、独立于本次拆分的生命周期问题，本组不处理，仅记录。
- [x] 3.1 新建 `activity/PollingCoordinator.java`，承接 `MyTimeTask` 的启动（`start()`）
- [x] 3.2 搬移 `setTimer`/`stopTimer` 的方法体；`task` 字段移入 `PollingCoordinator`；`MainActivity`
  持有 `public PollingCoordinator pollingCoordinator` 并在 `findView()` 构造、`onDestroy()` 里
  调用 `pollingCoordinator.stop()`（原 `stopTimer()` 私有方法零外部调用，直接删除，未保留委托包装）
- [x] 3.3 `./gradlew :app:assembleDebug` BUILD SUCCESSFUL；`cleanTestDebugUnitTest :app:testDebugUnitTest`
  81 个用例、0 失败，与基线一致
- [ ] 3.4 真机回归：观察 TEST_DATA 标志位看门狗行为（20 分钟周期，难以在短时间真机回归里完整验证，
  建议至少确认 App 冷启动到销毁重建过程中无崩溃/无泄漏告警）——本机无法验证，如实留空

## 4. 业务动作（MainActions）

- [ ] 4.1 新建业务动作类，承接出入库业务动作与密码校验逻辑
- [ ] 4.2 搬移相关方法（若 `layer-data-and-hardware-boundaries` 的 `OilTankRepository`/`StockRepository` 已就绪，直接对接；否则暂时保留对 `DBDataManager` 的依赖并加 TODO）
- [ ] 4.3 跑 `assembleDebug` + 现有单测
- [ ] 4.4 真机回归：执行一次完整出入库动作、一次密码校验失败场景

## 5. 报警协调器（AlarmCoordinator）—— 放最后，可能依赖阶段2产出

- [ ] 5.1 检查 `layer-data-and-hardware-boundaries` 的 `AlarmRepository` 是否已落地
- [ ] 5.2 新建报警协调器类，承接报警响应 UI 逻辑；按 5.1 的结果对接 `AlarmRepository` 或临时依赖 `DBDataManager`（后者需加清晰 TODO 标注，见 spec 的"数据依赖显式声明"要求）
- [ ] 5.3 跑 `assembleDebug` + 现有单测
- [ ] 5.4 真机回归：触发一次真实报警场景，确认 UI 响应与拆分前一致

## 6. 收尾

- [ ] 6.1 确认 `MainActivity` 只保留 Activity 生命周期方法与四个协作类的组装代码，不再直接实现四类职责的业务逻辑
- [ ] 6.2 更新 `docs/重构方案.md` 阶段3状态为已完成，记录实测结果（编译/单测/真机回归）
- [ ] 6.3 更新 `CLAUDE.md`："Known landmines" 中 `android:onClick` 反射绑定的条目改为记录"已消除"，避免误导后来者以为地雷还在
